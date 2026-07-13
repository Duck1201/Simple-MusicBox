package com.duck.simplemusicbox.client;

import com.duck.simplemusicbox.SimpleMusicBox;
import com.duck.simplemusicbox.audio.SmbFileFormat;
import com.duck.simplemusicbox.audio.TrackCache;
import com.duck.simplemusicbox.component.TrackData;
import com.duck.simplemusicbox.net.PlayTrackPayload;
import com.duck.simplemusicbox.net.RequestTrackPayload;
import com.duck.simplemusicbox.net.TrackChunkPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lado cliente: recebe os pacotes do servidor, mantém o cache local de faixas
 * e controla os sons em reprodução. Tudo roda na thread do cliente.
 */
public class ClientPlaybackManager {
	private static class PlayingTrack {
		final BlockPos pos;
		final TrackData track;
		CustomMusicSoundInstance instance;
		OpusAudioStream stream;
		Identifier streamLocation;
		/** Posição da faixa (ms) no momento em que o som atual começou. */
		long startOffsetMs;
		/** Relógio local de quando o som atual começou. */
		long startedLocalMs;

		PlayingTrack(BlockPos pos, TrackData track) {
			this.pos = pos;
			this.track = track;
		}

		long positionNowMs() {
			return startOffsetMs + (System.currentTimeMillis() - startedLocalMs);
		}

		/**
		 * O som atual morreu antes do fim da faixa? O fechamento do stream é o
		 * sinal imediato (o SoundSystem fecha ao parar por qualquer motivo —
		 * slider de volume zerado, /stopsound, master zerado...); o isPlaying
		 * do SoundManager fica de fallback para o caso de o play() nem ter
		 * chegado a abrir o stream.
		 */
		boolean interrupted(MinecraftClient client) {
			if (stream.isClosed()) {
				return !stream.isFinished();
			}
			return !client.getSoundManager().isPlaying(instance);
		}
	}

	private static class PendingPlay {
		final PlayTrackPayload payload;
		final long receivedAtMs = System.currentTimeMillis();

		PendingPlay(PlayTrackPayload payload) {
			this.payload = payload;
		}
	}

	private static class Download {
		final byte[][] chunks;
		int received;

		Download(int total) {
			this.chunks = new byte[total][];
		}
	}

	private static final Map<BlockPos, PlayingTrack> PLAYING = new HashMap<>();
	private static final Map<Identifier, AudioStream> PENDING_STREAMS = new HashMap<>();
	private static final Map<String, PendingPlay> WAITING_FOR_DATA = new HashMap<>();
	private static final Map<String, Download> DOWNLOADS = new HashMap<>();

	/** Margem para não confundir fim natural da faixa com interrupção. */
	private static final long END_MARGIN_MS = 1500;
	/** Carência após iniciar um som (o SoundSystem demora alguns ticks para reportar isPlaying). */
	private static final long START_GRACE_MS = 2500;

	private static TrackCache cache;

	public static void init(MinecraftClient client) {
		cache = new TrackCache(client.runDirectory.toPath().resolve("simple_musicbox/cache"));
	}

	public static void onPlay(MinecraftClient client, PlayTrackPayload payload) {
		String videoId = payload.track().videoId();
		PlayingTrack current = PLAYING.get(payload.pos());
		if (current != null && current.track.videoId().equals(videoId)
				&& client.getSoundManager().isPlaying(current.instance)) {
			return; // já está tocando essa faixa nessa jukebox
		}
		stopAt(client, payload.pos());

		var file = cache.read(videoId);
		if (file.isPresent()) {
			start(client, payload.pos(), payload.track(), file.get().frames(), payload.offsetMs());
		} else {
			WAITING_FOR_DATA.put(videoId, new PendingPlay(payload));
			DOWNLOADS.remove(videoId);
			ClientPlayNetworking.send(new RequestTrackPayload(videoId));
		}
	}

	public static void onChunk(MinecraftClient client, TrackChunkPayload chunk) {
		if (chunk.total() <= 0 || chunk.index() < 0 || chunk.index() >= chunk.total()) {
			return;
		}
		Download download = DOWNLOADS.computeIfAbsent(chunk.videoId(), id -> new Download(chunk.total()));
		if (download.chunks.length != chunk.total() || download.chunks[chunk.index()] != null) {
			return;
		}
		download.chunks[chunk.index()] = chunk.data();
		download.received++;
		if (download.received < download.chunks.length) {
			return;
		}
		DOWNLOADS.remove(chunk.videoId());

		int size = 0;
		for (byte[] part : download.chunks) {
			size += part.length;
		}
		byte[] whole = new byte[size];
		int offset = 0;
		for (byte[] part : download.chunks) {
			System.arraycopy(part, 0, whole, offset, part.length);
			offset += part.length;
		}

		try {
			SmbFileFormat.SmbFile parsed = SmbFileFormat.read(new ByteArrayInputStream(whole));
			cache.writeRawBytes(chunk.videoId(), whole);
			PendingPlay pending = WAITING_FOR_DATA.remove(chunk.videoId());
			if (pending != null) {
				long extraDelay = System.currentTimeMillis() - pending.receivedAtMs;
				start(client, pending.payload.pos(), pending.payload.track(), parsed.frames(),
						pending.payload.offsetMs() + extraDelay);
			}
		} catch (IOException | RuntimeException e) {
			SimpleMusicBox.LOGGER.warn("Received corrupted track data for {}", chunk.videoId(), e);
			WAITING_FOR_DATA.remove(chunk.videoId());
		}
	}

	public static void onStop(MinecraftClient client, BlockPos pos) {
		stopAt(client, pos);
	}

	private static void start(MinecraftClient client, BlockPos pos, TrackData track,
			List<byte[]> frames, long offsetMs) {
		if (offsetMs >= track.durationMs()) {
			return; // a faixa já teria acabado
		}
		PlayingTrack playing = new PlayingTrack(pos, track);
		PLAYING.put(pos, playing);
		startSound(client, playing, frames, offsetMs);
		SimpleMusicBox.LOGGER.info("Playing {} at {} (offset {} ms)",
				track.title(), pos.toShortString(), offsetMs);
	}

	private static void startSound(MinecraftClient client, PlayingTrack playing,
			List<byte[]> frames, long offsetMs) {
		boolean stereo = com.duck.simplemusicbox.ModConfig.get().stereo;
		Identifier soundId = SimpleMusicBox.id("stream/" + UUID.randomUUID());
		playing.streamLocation = SimpleMusicBox.id("sounds/" + soundId.getPath() + ".ogg");
		playing.instance = new CustomMusicSoundInstance(soundId, playing.pos, stereo);
		playing.stream = new OpusAudioStream(frames, offsetMs, stereo);
		playing.startOffsetMs = offsetMs;
		playing.startedLocalMs = System.currentTimeMillis();
		PENDING_STREAMS.put(playing.streamLocation, playing.stream);
		client.getSoundManager().play(playing.instance);
	}

	private static void stopAt(MinecraftClient client, BlockPos pos) {
		PlayingTrack track = PLAYING.remove(pos);
		if (track != null) {
			PENDING_STREAMS.remove(track.streamLocation);
			client.getSoundManager().stop(track.instance);
		}
	}

	/** Consumido pelo mixin em SoundLoader quando o SoundSystem pede o arquivo do nosso som. */
	public static AudioStream takeStream(Identifier location) {
		return PENDING_STREAMS.remove(location);
	}

	/** Estado local de reprodução para a GUI (barra de progresso ao vivo). */
	public record PlaybackInfo(TrackData track, long positionMs) {
	}

	public static java.util.Optional<PlaybackInfo> infoAt(BlockPos pos) {
		PlayingTrack playing = PLAYING.get(pos);
		return playing == null ? java.util.Optional.empty()
				: java.util.Optional.of(new PlaybackInfo(playing.track,
						Math.min(playing.positionNowMs(), playing.track.durationMs())));
	}

	/**
	 * A cada segundo: remove faixas que acabaram e retoma as que o SoundSystem
	 * matou no meio (ex.: volume master/jukebox zerado para o som de vez) assim
	 * que o volume volta a ficar audível.
	 */
	public static void tick(MinecraftClient client) {
		if (PLAYING.isEmpty() || client.world == null || client.world.getTime() % 20 != 0) {
			return;
		}
		long now = System.currentTimeMillis();
		List<BlockPos> finished = new ArrayList<>();
		for (Map.Entry<BlockPos, PlayingTrack> entry : PLAYING.entrySet()) {
			PlayingTrack playing = entry.getValue();
			if (now - playing.startedLocalMs < START_GRACE_MS) {
				continue;
			}
			if (!playing.interrupted(client)) {
				// Tocando normalmente, ou fim natural (stream fechado após consumir tudo).
				if (playing.stream.isClosed() && playing.stream.isFinished()) {
					finished.add(entry.getKey());
				}
				continue;
			}
			long position = playing.positionNowMs();
			if (position >= playing.track.durationMs() - END_MARGIN_MS) {
				finished.add(entry.getKey());
				continue;
			}
			// Interrompida no meio. Só vale a pena retomar se der para ouvir.
			if (client.options.getSoundVolume(SoundCategory.MASTER) <= 0
					|| client.options.getSoundVolume(SoundCategory.RECORDS) <= 0) {
				continue; // espera o volume voltar; a posição continua avançando
			}
			var file = cache.read(playing.track.videoId());
			if (file.isEmpty()) {
				finished.add(entry.getKey());
				continue;
			}
			PENDING_STREAMS.remove(playing.streamLocation);
			startSound(client, playing, file.get().frames(), position);
			SimpleMusicBox.LOGGER.info("Resumed {} at {} (offset {} ms)",
					playing.track.title(), playing.pos.toShortString(), position);
		}
		for (BlockPos pos : finished) {
			PlayingTrack track = PLAYING.remove(pos);
			PENDING_STREAMS.remove(track.streamLocation);
		}
	}

	public static void reset(MinecraftClient client) {
		for (PlayingTrack track : PLAYING.values()) {
			client.getSoundManager().stop(track.instance);
		}
		PLAYING.clear();
		PENDING_STREAMS.clear();
		WAITING_FOR_DATA.clear();
		DOWNLOADS.clear();
	}
}
