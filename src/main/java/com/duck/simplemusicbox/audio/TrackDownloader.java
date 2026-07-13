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

	private final TrackCache cache;
	private final ExecutorService executor;
	private final Map<String, CompletableFuture<TrackData>> inFlight = new ConcurrentHashMap<>();

	public TrackDownloader(TrackCache cache) {
		this.cache = cache;
		this.executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "SimpleMusicBox-Downloader");
			thread.setDaemon(true);
			return thread;
		});
	}

	public CompletableFuture<TrackData> download(String url) {
		if (!YOUTUBE_URL.matcher(url.trim()).matches()) {
			return CompletableFuture.failedFuture(
					new DownloadException(DownloadException.Kind.INVALID_URL, url));
		}
		return inFlight.computeIfAbsent(url.trim(), key -> {
			CompletableFuture<TrackData> future = new CompletableFuture<>();
			executor.submit(() -> run(key, future));
			future.whenComplete((result, error) -> inFlight.remove(key));
			return future;
		});
	}

	private void run(String url, CompletableFuture<TrackData> future) {
		try {
			ModConfig config = ModConfig.get();
			AudioEngine engine = EngineLoader.getEngine();
			AudioEngine.Result result = engine.download(url,
					config.maxDurationSeconds * 1000L,
					config.opusBitrate <= 0 ? 0 : Math.clamp(config.opusBitrate, 24_000, 320_000),
					cache::has);

			if (result.frames() == null) {
				// Já estava no cache; usa os metadados de lá (duração real).
				future.complete(cache.readHeader(result.videoId()).orElseThrow(
						() -> new DownloadException(DownloadException.Kind.FAILED, "cache desapareceu")));
				return;
			}
			TrackData data = new TrackData(result.videoId(), result.title(), result.durationMs());
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
