package com.duck.simplemusicbox.audio;

import com.duck.simplemusicbox.ModConfig;
import com.duck.simplemusicbox.SimpleMusicBox;
import com.duck.simplemusicbox.component.TrackData;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Camada fina sobre o {@link AudioEngine}: valida a URL, deduplica downloads
 * em andamento, consulta/grava o cache e traduz falhas típicas de mudança do
 * YouTube num erro acionável.
 */
public class TrackDownloader {
	private static final Pattern YOUTUBE_URL = Pattern.compile(
			"^(https?://)?(www\\.|m\\.|music\\.)?(youtube\\.com|youtu\\.be)/.+", Pattern.CASE_INSENSITIVE);
	/** Assinaturas comuns de "o YouTube mudou e o extractor quebrou". */
	private static final String[] YOUTUBE_CHANGED_HINTS = {
			"sign in to confirm", "cipher", "decipher", "unable to extract",
			"signature", "n function", "please sign in", "not a bot",
	};

	/** Mesma URL na mesma biblioteca = mesmo download; outra biblioteca (outro mundo) baixa à parte. */
	private record DownloadKey(TrackCache cache, String url) {
	}

	private final Supplier<TrackCache> currentCache;
	private final ExecutorService executor;
	private final Map<DownloadKey, CompletableFuture<TrackData>> inFlight = new ConcurrentHashMap<>();

	public TrackDownloader(Supplier<TrackCache> currentCache) {
		this.currentCache = currentCache;
		this.executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "SimpleMusicBox-Downloader");
			thread.setDaemon(true);
			return thread;
		});
	}

	public CompletableFuture<TrackData> download(String url) {
		if (SpotifyResolver.isSpotifyUrl(url)) {
			if (SpotifyResolver.trackId(url).isEmpty()) {
				return CompletableFuture.failedFuture(
						new DownloadException(DownloadException.Kind.SPOTIFY_ONLY_TRACKS, url));
			}
		} else if (!YOUTUBE_URL.matcher(url.trim()).matches()) {
			return CompletableFuture.failedFuture(
					new DownloadException(DownloadException.Kind.INVALID_URL, url));
		}
		// A biblioteca é fixada no pedido: se o jogador trocar de mundo durante o
		// download, a faixa vai para o mundo onde foi pedida.
		return inFlight.computeIfAbsent(new DownloadKey(currentCache.get(), url.trim()), key -> {
			CompletableFuture<TrackData> future = new CompletableFuture<>();
			executor.submit(() -> run(key.url(), key.cache(), future));
			future.whenComplete((result, error) -> inFlight.remove(key));
			return future;
		});
	}

	private void run(String url, TrackCache cache, CompletableFuture<TrackData> future) {
		try {
			ModConfig config = ModConfig.get();
			// Spotify: metadados da página pública -> busca no YouTube pela duração
			String source = url;
			long expectedDurationMs = 0;
			String titleOverride = null;
			String spotifyId = "";
			if (SpotifyResolver.isSpotifyUrl(url)) {
				spotifyId = SpotifyResolver.trackId(url).orElse("");
				SpotifyResolver.SpotifyTrack spotify = SpotifyResolver.resolve(url);
				source = spotify.youtubeSearch();
				expectedDurationMs = spotify.durationMs();
				titleOverride = spotify.displayTitle();
				SimpleMusicBox.LOGGER.info("Spotify {} -> searching YouTube for \"{}\" ({} s)",
						url, spotify.displayTitle(), expectedDurationMs / 1000);
			}
			AudioEngine engine = EngineLoader.getEngine();
			AudioEngine.Result result = engine.download(source, expectedDurationMs,
					config.maxDurationSeconds * 1000L,
					config.opusBitrate <= 0 ? 0 : Math.clamp(config.opusBitrate, 24_000, 320_000),
					cache::has);
			if (titleOverride != null) {
				SimpleMusicBox.LOGGER.info("Spotify match: YouTube {} \"{}\" ({} s)",
						result.videoId(), result.title(), result.durationMs() / 1000);
			}

			if (result.frames() == null) {
				// Já estava no cache; usa os metadados de lá (duração real).
				future.complete(cache.readHeader(result.videoId()).orElseThrow(
						() -> new DownloadException(DownloadException.Kind.FAILED, "cache desapareceu")));
				return;
			}
			TrackData data = new TrackData(result.videoId(),
					titleOverride != null ? titleOverride : result.title(), result.durationMs(), spotifyId);
			cache.write(data, result.frames());
			SimpleMusicBox.LOGGER.info("Downloaded track {} ({}, {} frames)",
					data.videoId(), data.title(), result.frames().size());
			future.complete(data);
		} catch (Throwable e) {
			future.completeExceptionally(classify(e));
		}
	}

	/** Converte erros crus em DownloadException, marcando quebras do YouTube. */
	private static DownloadException classify(Throwable error) {
		if (error instanceof DownloadException downloadException) {
			if (downloadException.kind() == DownloadException.Kind.FAILED
					&& looksLikeYoutubeChange(downloadException)) {
				return new DownloadException(DownloadException.Kind.YOUTUBE_CHANGED,
						downloadException.getMessage(), downloadException);
			}
			return downloadException;
		}
		return new DownloadException(DownloadException.Kind.FAILED, String.valueOf(error.getMessage()), error);
	}

	private static boolean looksLikeYoutubeChange(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
			for (String hint : YOUTUBE_CHANGED_HINTS) {
				if (message.contains(hint)) {
					return true;
				}
			}
		}
		return false;
	}
}
