package com.duck.simplemusicbox.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpotifyResolverTest {
	private static final String ID = "6habFhsOp2NvshLv26DqMb";

	@Test
	void acceptsTrackLinksInEveryFormat() {
		assertEquals(Optional.of(ID), SpotifyResolver.trackId("https://open.spotify.com/track/" + ID));
		assertEquals(Optional.of(ID), SpotifyResolver.trackId("https://open.spotify.com/intl-pt/track/" + ID));
		assertEquals(Optional.of(ID), SpotifyResolver.trackId("https://open.spotify.com/intl-pt-br/track/" + ID));
		assertEquals(Optional.of(ID), SpotifyResolver.trackId("open.spotify.com/track/" + ID + "?si=abc123"));
		assertEquals(Optional.of(ID), SpotifyResolver.trackId("  spotify:track:" + ID + "  "));
	}

	@Test
	void recognizesNonTrackSpotifyLinks() {
		String album = "https://open.spotify.com/album/4LH4d3cOWNNsVw41Gqt2kv";
		String playlist = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M";
		assertTrue(SpotifyResolver.isSpotifyUrl(album));
		assertTrue(SpotifyResolver.isSpotifyUrl(playlist));
		assertTrue(SpotifyResolver.trackId(album).isEmpty());
		assertTrue(SpotifyResolver.trackId(playlist).isEmpty());
	}

	@Test
	void ignoresOtherSites() {
		assertFalse(SpotifyResolver.isSpotifyUrl("https://www.youtube.com/watch?v=kJQP7kiw5Fk"));
		assertFalse(SpotifyResolver.isSpotifyUrl("https://evil.example/open.spotify.com/track/" + ID));
		assertTrue(SpotifyResolver.trackId("https://evil.example/open.spotify.com/track/" + ID).isEmpty());
	}

	@Test
	void parsesTrackPage() throws IOException {
		SpotifyResolver.SpotifyTrack track = SpotifyResolver.parse(fixture("track.html")).orElseThrow();
		assertEquals("Despacito", track.title());
		assertEquals("Luis Fonsi, Daddy Yankee", track.artist());
		assertEquals(229_000, track.durationMs());
		assertEquals("ytsearch:Luis Fonsi, Daddy Yankee - Despacito", track.youtubeSearch());
	}

	@Test
	void rejectsAlbumPage() throws IOException {
		assertTrue(SpotifyResolver.parse(fixture("album.html")).isEmpty());
	}

	@Test
	void fallsBackToDescriptionAndUnescapes() {
		String html = "<meta property=\"og:type\" content=\"music.song\"/>"
				+ "<meta property=\"og:title\" content=\"Rock &amp; Roll\"/>"
				+ "<meta property=\"og:description\" content=\"Led Zeppelin · IV · Song · 1971\"/>";
		SpotifyResolver.SpotifyTrack track = SpotifyResolver.parse(html).orElseThrow();
		assertEquals("Rock & Roll", track.title());
		assertEquals("Led Zeppelin", track.artist());
		assertEquals(0, track.durationMs());
	}

	private static String fixture(String name) throws IOException {
		try (InputStream in = SpotifyResolverTest.class.getResourceAsStream("/spotify/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
