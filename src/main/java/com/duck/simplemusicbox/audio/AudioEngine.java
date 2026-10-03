package com.duck.simplemusicbox.audio;

import java.util.List;
import java.util.function.Predicate;

/**
 * Fronteira entre o mod e o motor de áudio (LavaPlayer + youtube-source),
 * que é carregado num classloader próprio a partir de
 * config/simple_musicbox/libs — e por isso pode ser atualizado sem recompilar
 * o mod. Apenas tipos do JDK e deste pacote cruzam a fronteira.
 */
public interface AudioEngine {
	/**
	 * Resolve a URL (ou busca, ex. "ytsearch:..."), decodifica e re-encoda a faixa.
	 *
	 * @param expectedDurationMs numa busca, escolhe o resultado de duração mais
	 *                      próxima (ver {@link DurationMatch}); 0 = o primeiro
	 * @param alreadyCached recebe o videoId resolvido; se retornar true, a
	 *                      decodificação é pulada e {@link Result#frames()} vem null.
	 */
	Result download(String url, long expectedDurationMs, long maxDurationMs, int opusBitrate,
			Predicate<String> alreadyCached) throws DownloadException;

	/** Frames Opus de 20 ms (48 kHz estéreo); null quando a faixa já estava em cache. */
	record Result(String videoId, String title, long durationMs, List<byte[]> frames) {
	}
}
