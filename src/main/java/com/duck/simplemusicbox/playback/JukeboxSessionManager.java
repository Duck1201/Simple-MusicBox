package com.duck.simplemusicbox.playback;

import com.duck.simplemusicbox.ModConfig;
import com.duck.simplemusicbox.SimpleMusicBox;
import com.duck.simplemusicbox.component.ModComponents;
import com.duck.simplemusicbox.component.TrackData;
import com.duck.simplemusicbox.item.Discs;
import com.duck.simplemusicbox.net.PlayTrackPayload;
import com.duck.simplemusicbox.net.StopTrackPayload;
import com.duck.simplemusicbox.net.TrackChunkPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.JukeboxBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * Controla as sessões de reprodução ativas (jukebox tocando um disco
 * customizado) e o envio dos arquivos de áudio em chunks. Todo o estado é
 * acessado apenas na thread do servidor.
 */
public class JukeboxSessionManager {
	private static class Session {
		final TrackData track;
		final Set<UUID> notified = new HashSet<>();
		long startedAtMs = System.currentTimeMillis();
		/** Posição congelada durante o pause; -1 = tocando. */
		long pausedPositionMs = -1;

		Session(TrackData track) {
			this.track = track;
		}

		long elapsedMs() {
			return pausedPositionMs >= 0 ? pausedPositionMs : System.currentTimeMillis() - startedAtMs;
		}

		boolean isPaused() {
			return pausedPositionMs >= 0;
		}
	}

	private static final Map<GlobalPos, Session> SESSIONS = new HashMap<>();
	private static final Set<GlobalPos> PENDING_RESUME = new HashSet<>();
	private static final Map<UUID, Queue<TrackChunkPayload>> CHUNK_QUEUES = new HashMap<>();

	/** Chamado quando a sessão termina naturalmente, para desligar o estado vanilla da jukebox. */
	public interface VanillaStopCallback {
		void stopVanilla(ServerWorld world, BlockPos pos);
	}

	private static VanillaStopCallback vanillaStop = (world, pos) -> {
	};

	public static void setVanillaStopCallback(VanillaStopCallback callback) {
		vanillaStop = callback;
	}

	public static void init() {
		ServerTickEvents.END_WORLD_TICK.register(JukeboxSessionManager::tickWorld);
		ServerTickEvents.END_SERVER_TICK.register(server -> tickChunkQueues(server.getPlayerManager()::getPlayer));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.getPlayer().getUuid();
			CHUNK_QUEUES.remove(id);
			for (Session session : SESSIONS.values()) {
				session.notified.remove(id);
			}
		});
		// A sessão só existe em memória: depois de reiniciar o servidor (ou recarregar
		// o chunk), a jukebox volta com o disco e o estado vanilla "tocando", mas
		// sem áudio. Só anota aqui — o evento roda no meio do carregamento do chunk,
		// e mexer no mundo agora travaria o servidor; a retomada é no tick.
		ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, world) -> {
			if (blockEntity instanceof JukeboxBlockEntity jukebox && jukebox.getManager().isPlaying()
					&& jukebox.getStack().contains(ModComponents.TRACK)) {
				PENDING_RESUME.add(GlobalPos.create(world.getRegistryKey(), jukebox.getPos()));
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			SESSIONS.clear();
			PENDING_RESUME.clear();
			CHUNK_QUEUES.clear();
		});
	}

	public static void start(ServerWorld world, BlockPos pos, TrackData track) {
		start(world, pos, track, 0);
	}

	private static void start(ServerWorld world, BlockPos pos, TrackData track, long offsetMs) {
		GlobalPos key = GlobalPos.create(world.getRegistryKey(), pos);
		Session session = new Session(track);
		session.startedAtMs -= offsetMs;
		SESSIONS.put(key, session);
		notifyNearbyPlayers(world, pos, session);
		SimpleMusicBox.LOGGER.info("Jukebox at {} started playing {} ({})", pos.toShortString(),
				track.title(), track.videoId());
		if (!SimpleMusicBox.trackCache().has(track.videoId())) {
			redownload(world, pos, session);
		}
	}

	/**
	 * Disco cuja faixa não está nesta biblioteca (ex.: disco de antes da
	 * biblioteca por mundo, ou dado por /give): sem isso, quem não tem a faixa
	 * no cache local ficaria em silêncio. Baixa de novo pelo ID do YouTube e
	 * reenvia o "tocar" para quem está perto.
	 */
	private static void redownload(ServerWorld world, BlockPos pos, Session session) {
		String videoId = session.track.videoId();
		SimpleMusicBox.LOGGER.info("Track {} is not in this library; downloading it again", videoId);
		SimpleMusicBox.downloader().download("https://www.youtube.com/watch?v=" + videoId)
				.whenComplete((track, error) -> world.getServer().execute(() -> {
					if (error != null) {
						SimpleMusicBox.LOGGER.warn("Could not re-download track {}: {}", videoId,
								String.valueOf(error.getMessage()));
						return;
					}
					// Ainda a mesma sessão? Então avisa de novo quem já tinha sido avisado.
					if (SESSIONS.get(GlobalPos.create(world.getRegistryKey(), pos)) == session) {
						session.notified.clear();
						notifyNearbyPlayers(world, pos, session);
					}
				}));
	}

	public static void stop(ServerWorld world, BlockPos pos) {
		GlobalPos key = GlobalPos.create(world.getRegistryKey(), pos);
		if (SESSIONS.remove(key) == null) {
			return;
		}
		broadcastStop(world, pos);
	}

	private static void broadcastStop(ServerWorld world, BlockPos pos) {
		StopTrackPayload payload = new StopTrackPayload(pos);
		for (ServerPlayerEntity player : world.getPlayers()) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/** Retoma as jukeboxes anotadas no carregamento (chamado no tick do mundo). */
	private static void resumePending(ServerWorld world) {
		for (Iterator<GlobalPos> it = PENDING_RESUME.iterator(); it.hasNext();) {
			GlobalPos key = it.next();
			if (key.dimension().equals(world.getRegistryKey())) {
				it.remove();
				if (world.isChunkLoaded(ChunkSectionPos.getSectionCoord(key.pos().getX()),
						ChunkSectionPos.getSectionCoord(key.pos().getZ()))) {
					resume(world, key.pos());
				}
			}
		}
	}

	private static void resume(ServerWorld world, BlockPos pos) {
		if (isPlaying(world, pos)
				|| !(world.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox)
				|| !jukebox.getManager().isPlaying()) {
			return;
		}
		TrackData track = jukebox.getStack().get(ModComponents.TRACK);
		if (track == null) {
			return;
		}
		long offsetMs = jukebox.getManager().getTicksSinceSongStarted() * 50;
		if (offsetMs >= track.durationMs()) {
			advanceQueue(world, pos, track);
			return;
		}
		SimpleMusicBox.LOGGER.info("Resuming jukebox at {} at {} ms", pos.toShortString(), offsetMs);
		start(world, pos, track, offsetMs);
	}

	/** Alterna pause/retomada; retorna o novo estado (true = pausado). */
	public static boolean togglePause(ServerWorld world, BlockPos pos) {
		Session session = SESSIONS.get(GlobalPos.create(world.getRegistryKey(), pos));
		if (session == null) {
			return false;
		}
		if (session.isPaused()) {
			session.startedAtMs = System.currentTimeMillis() - session.pausedPositionMs;
			session.pausedPositionMs = -1;
			session.notified.clear();
			notifyNearbyPlayers(world, pos, session);
			// Religa o estado vanilla (partículas/allays) da jukebox.
			if (world.getBlockEntity(pos) instanceof net.minecraft.block.entity.JukeboxBlockEntity jukebox) {
				net.minecraft.block.jukebox.JukeboxSong.getSongEntryFromStack(
								world.getRegistryManager(), jukebox.getStack())
						.ifPresent(song -> jukebox.getManager().startPlaying(world, song));
			}
			return false;
		}
		session.pausedPositionMs = session.elapsedMs();
		broadcastStop(world, pos);
		if (world.getBlockEntity(pos) instanceof net.minecraft.block.entity.JukeboxBlockEntity jukebox) {
			jukebox.getManager().stopPlaying(world, world.getBlockState(pos));
		}
		return true;
	}

	public static boolean isPlaying(ServerWorld world, BlockPos pos) {
		return SESSIONS.containsKey(GlobalPos.create(world.getRegistryKey(), pos));
	}

	public static boolean isPlayingTrack(ServerWorld world, BlockPos pos, TrackData track) {
		Session session = SESSIONS.get(GlobalPos.create(world.getRegistryKey(), pos));
		return session != null && session.track.videoId().equals(track.videoId());
	}

	/** Estado atual para a GUI: faixa, posição em ms e pause. */
	public record SessionInfo(TrackData track, long positionMs, boolean paused) {
	}

	public static java.util.Optional<SessionInfo> sessionAt(ServerWorld world, BlockPos pos) {
		Session session = SESSIONS.get(GlobalPos.create(world.getRegistryKey(), pos));
		return session == null ? java.util.Optional.empty()
				: java.util.Optional.of(new SessionInfo(session.track,
						Math.min(session.elapsedMs(), session.track.durationMs()), session.isPaused()));
	}

	public static void handleRequest(ServerPlayerEntity player, String videoId) {
		var bytes = SimpleMusicBox.trackCache().readRawBytes(videoId);
		if (bytes.isEmpty()) {
			SimpleMusicBox.LOGGER.warn("{} requested unknown track {}", player.getName().getString(), videoId);
			return;
		}
		byte[] raw = bytes.get();
		int chunkSize = ModConfig.get().networkChunkSize;
		int total = (raw.length + chunkSize - 1) / chunkSize;
		Queue<TrackChunkPayload> queue = CHUNK_QUEUES.computeIfAbsent(player.getUuid(), id -> new ArrayDeque<>());
		for (int i = 0; i < total; i++) {
			int from = i * chunkSize;
			int to = Math.min(raw.length, from + chunkSize);
			byte[] slice = new byte[to - from];
			System.arraycopy(raw, from, slice, 0, slice.length);
			queue.add(new TrackChunkPayload(videoId, i, total, slice));
		}
	}

	private static void tickWorld(ServerWorld world) {
		if (!PENDING_RESUME.isEmpty()) {
			resumePending(world);
		}
		if (world.getTime() % 10 != 0 || SESSIONS.isEmpty()) {
			return;
		}
		List<GlobalPos> finished = new ArrayList<>();
		for (Map.Entry<GlobalPos, Session> entry : SESSIONS.entrySet()) {
			GlobalPos key = entry.getKey();
			if (!key.dimension().equals(world.getRegistryKey())) {
				continue;
			}
			Session session = entry.getValue();
			if (session.isPaused()) {
				continue; // posição congelada; nada a notificar
			}
			if (session.elapsedMs() > session.track.durationMs() + 1000) {
				finished.add(key);
				continue;
			}
			notifyNearbyPlayers(world, key.pos(), session);
		}
		for (GlobalPos key : finished) {
			Session session = SESSIONS.remove(key);
			advanceQueue(world, key.pos(), session.track);
		}
	}

	/**
	 * Fim natural da faixa: com loop ligado repete a mesma; sem loop, avança
	 * para a próxima do cache (ordem alfabética, circular) — a jukebox vira uma
	 * rádio que só para quando alguém pedir. O disco da vez é materializado
	 * dentro da jukebox, mantendo partículas/comparador/allays vanilla.
	 */
	private static void advanceQueue(ServerWorld world, BlockPos pos, TrackData ended) {
		// Chunk descarregado: a rádio não continua sozinha em área abandonada
		// (e não força o load do chunk). Volta ao inserir/tocar de novo.
		if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
			return;
		}
		if (!(world.getBlockEntity(pos) instanceof net.minecraft.block.entity.JukeboxBlockEntity jukebox)) {
			return;
		}
		boolean loop = jukebox instanceof JukeboxLoopAccess access && access.simple_musicbox$isLoop();
		ItemStack inside = jukebox.getStack();
		// setStack dispara o fluxo vanilla + o mixin, que abre a nova sessão.
		if (Discs.isPhysical(inside)) {
			// Disco físico se comporta como um vanilla: repete com loop, senão para
			// e continua na jukebox. Nunca é trocado pela fila.
			if (loop) {
				jukebox.setStack(inside);
			} else {
				vanillaStop.stopVanilla(world, pos);
			}
			return;
		}
		TrackData next = loop ? ended : nextInQueue(ended);
		if (next == null) {
			vanillaStop.stopVanilla(world, pos);
			return;
		}
		jukebox.setStack(Discs.virtual(next));
	}

	/** Pulo manual (botão Avançar): vai para a próxima da fila mesmo com loop ligado. */
	public static void skip(ServerWorld world, BlockPos pos) {
		if (!(world.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox)
				|| Discs.isPhysical(jukebox.getStack())) {
			return; // disco físico nunca é sobrescrito (a GUI o devolve antes)
		}
		Session session = SESSIONS.remove(GlobalPos.create(world.getRegistryKey(), pos));
		TrackData current = session != null ? session.track
				: jukebox.getStack().get(ModComponents.TRACK);
		TrackData next = nextInQueue(current);
		if (next != null) {
			jukebox.setStack(Discs.virtual(next));
		} else if (session != null) {
			vanillaStop.stopVanilla(world, pos);
		}
	}

	/** Próxima faixa do cache em ordem alfabética (circular); null se o cache está vazio. */
	private static TrackData nextInQueue(TrackData current) {
		List<TrackData> queue = SimpleMusicBox.trackCache().listTracks();
		if (queue.isEmpty()) {
			return null;
		}
		int index = 0;
		if (current != null) {
			for (int i = 0; i < queue.size(); i++) {
				if (queue.get(i).videoId().equals(current.videoId())) {
					index = (i + 1) % queue.size();
					break;
				}
			}
		}
		return queue.get(index);
	}

	private static void notifyNearbyPlayers(ServerWorld world, BlockPos pos, Session session) {
		double radius = ModConfig.get().audibleRadius;
		double notifyRange = radius + 16;
		double forgetRange = radius * 2 + 32;
		Vec3d center = Vec3d.ofCenter(pos);
		for (ServerPlayerEntity player : world.getPlayers()) {
			double distance = player.getPos().distanceTo(center);
			if (distance <= notifyRange) {
				if (session.notified.add(player.getUuid())) {
					ServerPlayNetworking.send(player,
							new PlayTrackPayload(pos, session.track, session.elapsedMs()));
					player.sendMessage(Text.translatable("simple_musicbox.now_playing",
							Text.literal(session.track.title()).formatted(Formatting.AQUA)), true);
				}
			} else if (distance > forgetRange) {
				// Se voltar a se aproximar, recebe o pacote de novo com o offset atual.
				session.notified.remove(player.getUuid());
			}
		}
	}

	private interface PlayerLookup {
		ServerPlayerEntity find(UUID uuid);
	}

	private static void tickChunkQueues(PlayerLookup players) {
		if (CHUNK_QUEUES.isEmpty()) {
			return;
		}
		int perTick = ModConfig.get().chunksPerTick;
		Iterator<Map.Entry<UUID, Queue<TrackChunkPayload>>> iterator = CHUNK_QUEUES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Queue<TrackChunkPayload>> entry = iterator.next();
			ServerPlayerEntity player = players.find(entry.getKey());
			if (player == null) {
				iterator.remove();
				continue;
			}
			Queue<TrackChunkPayload> queue = entry.getValue();
			for (int i = 0; i < perTick && !queue.isEmpty(); i++) {
				ServerPlayNetworking.send(player, queue.poll());
			}
			if (queue.isEmpty()) {
				iterator.remove();
			}
		}
	}
}
