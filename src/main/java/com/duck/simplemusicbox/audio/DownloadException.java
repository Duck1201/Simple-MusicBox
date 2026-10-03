package com.duck.simplemusicbox.audio;

public class DownloadException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public enum Kind {
		INVALID_URL,
		NOT_FOUND,
		TOO_LONG,
		LIVE,
		/** O YouTube mudou algo e o extractor atual não dá conta; atualizar resolve. */
		YOUTUBE_CHANGED,
		/** Link do Spotify sem nenhum resultado no YouTube com duração compatível. */
		NO_MATCH,
		/** Link do Spotify que não é de uma faixa (álbum, playlist, artista...). */
		SPOTIFY_ONLY_TRACKS,
		FAILED
	}

	private final Kind kind;

	public DownloadException(Kind kind, String message) {
		super(message);
		this.kind = kind;
	}

	public DownloadException(Kind kind, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}
}
