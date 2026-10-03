package com.duck.simplemusicbox.command;

import com.duck.simplemusicbox.SimpleMusicBox;
import com.duck.simplemusicbox.audio.DownloadException;
import com.duck.simplemusicbox.component.TrackData;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.concurrent.CompletionException;

public class MusicCommand {
	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("music")
						.then(CommandManager.argument("url", StringArgumentType.greedyString())
								.executes(context -> run(context.getSource(),
										StringArgumentType.getString(context, "url"))))));
	}

	// Não exige jogador: o console também pode pré-carregar faixas no cache.
	private static int run(ServerCommandSource source, String url) {
		MinecraftServer server = source.getServer();

		source.sendFeedback(() -> Text.translatable("simple_musicbox.command.downloading")
				.formatted(Formatting.GRAY), false);

		java.util.concurrent.CompletableFuture<TrackData> download;
		try {
			download = SimpleMusicBox.downloader().download(url);
		} catch (Throwable e) {
			// Nunca derrubar o servidor por causa do comando (ex.: falha ao
			// inicializar o LavaPlayer neste ambiente).
			SimpleMusicBox.LOGGER.error("Could not start download", e);
			source.sendError(describeError(e));
			return 0;
		}

		download.whenComplete((track, error) -> server.execute(() -> {
			if (error != null) {
				source.sendError(describeError(unwrap(error)));
				return;
			}
			// Sem disco de brinde: discos físicos só nascem gravando um Disco Virgem.
			// A faixa fica disponível na GUI (shift+clique na jukebox ou /player).
			source.sendFeedback(() -> Text.translatable("simple_musicbox.command.added",
					Text.literal(track.title()).formatted(Formatting.AQUA)), false);
		}));
		return 1;
	}

	private static Throwable unwrap(Throwable error) {
		return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
	}

	public static Text describeError(Throwable error) {
		if (error instanceof DownloadException downloadError) {
			return switch (downloadError.kind()) {
				case INVALID_URL -> Text.translatable("simple_musicbox.command.error.invalid_url");
				case NOT_FOUND -> Text.translatable("simple_musicbox.command.error.not_found");
				case TOO_LONG -> Text.translatable("simple_musicbox.command.error.too_long",
						com.duck.simplemusicbox.ModConfig.get().maxDurationSeconds / 60);
				case LIVE -> Text.translatable("simple_musicbox.command.error.live");
				case YOUTUBE_CHANGED -> Text.translatable("simple_musicbox.command.error.youtube_changed");
				case NO_MATCH -> Text.translatable("simple_musicbox.command.error.no_match");
				case SPOTIFY_ONLY_TRACKS -> Text.translatable("simple_musicbox.command.error.spotify_only_tracks");
				case FAILED -> Text.translatable("simple_musicbox.command.error.failed",
						String.valueOf(downloadError.getMessage()));
			};
		}
		SimpleMusicBox.LOGGER.error("Unexpected error downloading track", error);
		return Text.translatable("simple_musicbox.command.error.failed", String.valueOf(error.getMessage()));
	}
}
