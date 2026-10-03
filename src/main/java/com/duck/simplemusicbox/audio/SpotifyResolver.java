package com.duck.simplemusicbox.audio;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links do Spotify: o Spotify não permite baixar áudio, então lemos título,
 * artista e duração da página pública da faixa (meta tags, sem credenciais) e
 * buscamos a música no YouTube.
 */
public final class SpotifyResolver {
	private static final Pattern TRACK_URL = Pattern.compile(
			"^(?:https?://)?open\\.spotify\\.com/(?:intl-[a-z]{2}(?:-[a-z]{2})?/)?track/([A-Za-z0-9]{22})(?:[/?#].*)?$"
					+ "|^spotify:track:([A-Za-z0-9]{22})$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern ANY_SPOTIFY = Pattern.compile(
			"^(?:https?://)?open\\.spotify\\.com/.*|^spotify:.*", Pattern.CASE_INSENSITIVE);
	private static final Pattern META_TAG = Pattern.compile("<meta\\s[^>]*>", Pattern.CASE_INSENSITIVE);
	private static final Pattern ATTRIBUTE = Pattern.compile("(property|name|content)=\"([^\"]*)\"",
			Pattern.CASE_INSENSITIVE);

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/** Metadados da faixa; durationMs = 0 quando a página não informa. */
	public record SpotifyTrack(String title, String artist, long durationMs) {
		public String displayTitle() {
			return artist.isEmpty() ? title : artist + " - " + title;
		}

		public String youtubeSearch() {
			return "ytsearch:" + displayTitle();
		}
	}

	private SpotifyResolver() {
	}

	/** Qualquer link do Spotify (faixa, álbum, playlist...). */
	public static boolean isSpotifyUrl(String url) {
		return ANY_SPOTIFY.matcher(url.trim()).matches();
	}

	/** ID da faixa, se o link for de uma faixa. */
	public static Optional<String> trackId(String url) {
		Matcher matcher = TRACK_URL.matcher(url.trim());
		if (!matcher.matches()) {
			return Optional.empty();
		}
		return Optional.of(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
	}

	public static SpotifyTrack resolve(String url) throws DownloadException {
		String id = trackId(url).orElseThrow(
				() -> new DownloadException(DownloadException.Kind.SPOTIFY_ONLY_TRACKS, url));
		String html;
		try {
			HttpResponse<String> response = HTTP.send(
					HttpRequest.newBuilder(URI.create("https://open.spotify.com/track/" + id))
							.timeout(Duration.ofSeconds(20))
							.header("User-Agent", "Mozilla/5.0 (compatible; SimpleMusicBox)")
							.GET().build(),
					HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 404) {
				throw new DownloadException(DownloadException.Kind.NOT_FOUND, url);
			}
			if (response.statusCode() != 200) {
				throw new DownloadException(DownloadException.Kind.FAILED,
						"Spotify HTTP " + response.statusCode());
			}
			html = response.body();
		} catch (IOException e) {
			throw new DownloadException(DownloadException.Kind.FAILED, "Spotify: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DownloadException(DownloadException.Kind.FAILED, "interrupted", e);
		}
		return parse(html).orElseThrow(() -> new DownloadException(DownloadException.Kind.NOT_FOUND, url));
	}

	/** Extrai a faixa das meta tags da página; vazio se não for uma página de faixa. */
	public static Optional<SpotifyTrack> parse(String html) {
		Map<String, String> meta = new HashMap<>();
		Matcher tags = META_TAG.matcher(html);
		while (tags.find()) {
			String key = null;
			String content = null;
			Matcher attributes = ATTRIBUTE.matcher(tags.group());
			while (attributes.find()) {
				if (attributes.group(1).equalsIgnoreCase("content")) {
					content = unescape(attributes.group(2));
				} else {
					key = attributes.group(2).toLowerCase(java.util.Locale.ROOT);
				}
			}
			if (key != null && content != null) {
				meta.putIfAbsent(key, content);
			}
		}

		String title = meta.getOrDefault("og:title", "").trim();
		if (title.isEmpty() || !"music.song".equals(meta.get("og:type"))) {
			return Optional.empty();
		}
		// "Artista · Álbum · Song · Ano" — o primeiro campo é o artista
		String artist = meta.getOrDefault("music:musician_description",
				meta.getOrDefault("og:description", "").split(" · ")[0]).trim();
		long durationMs = 0;
		try {
			durationMs = Long.parseLong(meta.getOrDefault("music:duration", "0").trim()) * 1000;
		} catch (NumberFormatException ignored) {
			// sem duração: a busca aceita o primeiro resultado
		}
		return Optional.of(new SpotifyTrack(title, artist, durationMs));
	}

	private static String unescape(String text) {
		return text.replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
				.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
	}
}
