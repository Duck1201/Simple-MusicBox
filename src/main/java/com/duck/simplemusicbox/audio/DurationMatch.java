package com.duck.simplemusicbox.audio;

import java.util.List;

/**
 * Escolhe, entre os resultados de uma busca, o de duração mais próxima da
 * esperada — evita pegar clipe estendido, ao vivo ou versão de 10 horas
 * quando a música vem de um link do Spotify.
 */
public final class DurationMatch {
	/** Quantos resultados da busca são considerados. */
	public static final int CANDIDATES = 5;
	private static final long MIN_TOLERANCE_MS = 10_000;

	private DurationMatch() {
	}

	/**
	 * @param durationsMs     durações dos resultados, na ordem da busca
	 * @param expectedMs      duração esperada; 0 ou negativo = aceita o primeiro
	 * @return índice escolhido, ou -1 se nenhum dos primeiros {@link #CANDIDATES}
	 *         está dentro da tolerância (max(10 s, 10%))
	 */
	public static int bestIndex(List<Long> durationsMs, long expectedMs) {
		if (durationsMs.isEmpty()) {
			return -1;
		}
		if (expectedMs <= 0) {
			return 0;
		}
		long tolerance = Math.max(MIN_TOLERANCE_MS, expectedMs / 10);
		int best = -1;
		long bestDiff = Long.MAX_VALUE;
		for (int i = 0; i < Math.min(CANDIDATES, durationsMs.size()); i++) {
			long diff = Math.abs(durationsMs.get(i) - expectedMs);
			// estritamente menor: em empate fica o mais bem ranqueado pela busca
			if (diff <= tolerance && diff < bestDiff) {
				best = i;
				bestDiff = diff;
			}
		}
		return best;
	}
}
