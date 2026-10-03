package com.duck.simplemusicbox;

import com.duck.simplemusicbox.audio.TrackCache;
import com.duck.simplemusicbox.audio.TrackDownloader;
import com.duck.simplemusicbox.command.MusicCommand;
import com.duck.simplemusicbox.component.ModComponents;
import com.duck.simplemusicbox.item.ModItems;
import com.duck.simplemusicbox.net.PlayTrackPayload;
import com.duck.simplemusicbox.net.RequestTrackPayload;
import com.duck.simplemusicbox.net.StopTrackPayload;
import com.duck.simplemusicbox.net.TrackChunkPayload;
import com.duck.simplemusicbox.playback.JukeboxSessionManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SimpleMusicBox implements ModInitializer {
	public static final String MOD_ID = "simple_musicbox";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static volatile TrackCache trackCache;
	private static TrackDownloader downloader;

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	/** Biblioteca de faixas do servidor atual (muda a cada mundo no single-player). */
	public static TrackCache trackCache() {
		return trackCache;
	}

	/** Criado sob demanda: o AudioPlayerManager do LavaPlayer só é necessário onde /music é usado. */
	public static synchronized TrackDownloader downloader() {
		if (downloader == null) {
			downloader = new TrackDownloader(SimpleMusicBox::trackCache);
		}
		return downloader;
	}

	@Override
	public void onInitialize() {
		setupNativesDir();

		ModComponents.register();
		ModSounds.register();
		ModItems.register();

		PayloadTypeRegistry.playS2C().register(PlayTrackPayload.ID, PlayTrackPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(StopTrackPayload.ID, StopTrackPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(TrackChunkPayload.ID, TrackChunkPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(com.duck.simplemusicbox.net.JukeboxGuiOpenPayload.ID,
				com.duck.simplemusicbox.net.JukeboxGuiOpenPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RequestTrackPayload.ID, RequestTrackPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(com.duck.simplemusicbox.net.JukeboxGuiActionPayload.ID,
				com.duck.simplemusicbox.net.JukeboxGuiActionPayload.CODEC);

		trackCache = new TrackCache(dedicatedLibraryDir());
		// Servidor dedicado: biblioteca em config/. Single-player/LAN (servidor
		// integrado): uma biblioteca por mundo, dentro do save.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			Path dir = server.isDedicated()
					? dedicatedLibraryDir()
					: server.getSavePath(WorldSavePath.ROOT).resolve("simple_musicbox/cache").normalize();
			trackCache = new TrackCache(dir);
			LOGGER.info("Track library: {}", dir);
		});

		MusicCommand.register();
		JukeboxSessionManager.init();
		com.duck.simplemusicbox.playback.JukeboxGuiServer.init();
		// Quando a faixa real termina, desliga o estado "tocando" da jukebox vanilla
		// (partículas, dança dos allays, saída de comparador de "tocando").
		JukeboxSessionManager.setVanillaStopCallback((world, pos) -> {
			if (world.getBlockEntity(pos) instanceof net.minecraft.block.entity.JukeboxBlockEntity jukebox) {
				jukebox.getManager().stopPlaying(world, world.getBlockState(pos));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(RequestTrackPayload.ID, (payload, context) ->
				JukeboxSessionManager.handleRequest(context.player(), payload.videoId()));

		// Prepara/atualiza o motor de áudio em background assim que o servidor
		// sobe, para o primeiro /music já usar a versão mais nova.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server ->
				new Thread(com.duck.simplemusicbox.audio.EngineLoader::prepareLibs,
						"SimpleMusicBox-EngineUpdate").start());

		// Avisa admins no login se o motor de áudio tem algum problema.
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			String notice = com.duck.simplemusicbox.audio.EngineLoader.opsNotice();
			if (notice != null && server.getPlayerManager().isOperator(handler.getPlayer().getGameProfile())) {
				handler.getPlayer().sendMessage(
						net.minecraft.text.Text.literal(notice).formatted(net.minecraft.util.Formatting.YELLOW));
			}
		});

		LOGGER.info("Simple MusicBox initialized");
	}

	private static Path dedicatedLibraryDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("simple_musicbox/cache");
	}

	/**
	 * O LavaPlayer extrai bibliotecas nativas (encoder/decoder Opus) em
	 * java.io.tmpdir por padrão; em sistemas com /tmp noexec isso falha.
	 * Redireciona a extração para a pasta do jogo.
	 */
	private static void setupNativesDir() {
		if (System.getProperty("lava.native.extractPath") != null) {
			return;
		}
		Path nativesDir = FabricLoader.getInstance().getGameDir().resolve("simple_musicbox/natives");
		try {
			Files.createDirectories(nativesDir);
			System.setProperty("lava.native.extractPath", nativesDir.toAbsolutePath().toString());
		} catch (IOException e) {
			LOGGER.warn("Could not create natives directory, using default tmpdir", e);
		}
	}
}
