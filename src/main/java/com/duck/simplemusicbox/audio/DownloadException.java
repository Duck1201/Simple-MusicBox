package com.duck.simplemusicbox.audio;

public class DownloadException extends RuntimeException {
	public enum Kind {
		INVALID_URL,
		NOT_FOUND,
		TOO_LONG,
		LIVE,
		/** O YouTube mudou algo e o extractor atual não dá conta; atualizar resolve. */
		YOUTUBE_CHANGED,
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
