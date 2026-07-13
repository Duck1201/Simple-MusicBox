package com.duck.simplemusicbox.client;

import com.duck.simplemusicbox.SimpleMusicBox;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import net.minecraft.client.sound.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * AudioStream do Minecraft alimentado por frames Opus (20 ms, 48 kHz estéreo)
 * decodificados com Concentus (Opus puro-Java — o cliente não depende do
 * LavaPlayer nem de bibliotecas nativas).
 *
 * Em modo estéreo a saída mantém os 2 canais (o volume por distância é
 * aplicado manualmente pelo CustomMusicSoundInstance, já que o OpenAL não
 * espacializa fontes estéreo). Em modo mono faz downmix L+R e a atenuação
 * posicional fica por conta do OpenAL, como um disco vanilla.
 */
public class OpusAudioStream implements AudioStream {
	private static final int SAMPLE_RATE = 48000;
	private static final int CHANNELS = 2;
	private static final int SAMPLES_PER_FRAME = 960; // 20 ms a 48 kHz

	private final List<byte[]> frames;
	private final boolean stereo;
	private final int outputBytesPerFrame;
	private final AudioFormat format;
	private final OpusDecoder decoder;
	private final short[] pcm = new short[SAMPLES_PER_FRAME * CHANNELS];
	private volatile int index;
	private volatile boolean closed;

	public OpusAudioStream(List<byte[]> frames, long offsetMs, boolean stereo) {
		this.frames = frames;
		this.stereo = stereo;
		this.outputBytesPerFrame = SAMPLES_PER_FRAME * (stereo ? CHANNELS : 1) * 2;
		this.format = new AudioFormat(SAMPLE_RATE, 16, stereo ? CHANNELS : 1, true, false);
		this.index = (int) Math.clamp(offsetMs / 20, 0, frames.size());
		try {
			this.decoder = new OpusDecoder(SAMPLE_RATE, CHANNELS);
		} catch (OpusException e) {
			throw new IllegalStateException("Could not create Opus decoder", e);
		}
	}

	@Override
	public AudioFormat getFormat() {
		return format;
	}

	@Override
	public ByteBuffer read(int size) throws IOException {
		int frameCount = Math.max(1, size / outputBytesPerFrame);
		ByteBuffer result = ByteBuffer.allocateDirect(frameCount * outputBytesPerFrame)
				.order(ByteOrder.LITTLE_ENDIAN);
		synchronized (this) {
			while (!closed && index < frames.size() && result.remaining() >= outputBytesPerFrame) {
				byte[] frame = frames.get(index++);
				int samples;
				try {
					samples = decoder.decode(frame, 0, frame.length, pcm, 0, SAMPLES_PER_FRAME, false);
				} catch (OpusException e) {
					SimpleMusicBox.LOGGER.warn("Skipping corrupted opus frame", e);
					continue;
				}
				if (stereo) {
					for (int i = 0; i < samples * CHANNELS; i++) {
						result.putShort(pcm[i]);
					}
				} else {
					for (int i = 0; i < samples; i++) {
						int left = pcm[i * 2];
						int right = pcm[i * 2 + 1];
						result.putShort((short) ((left + right) / 2));
					}
				}
			}
		}
		result.flip();
		return result;
	}

	@Override
	public synchronized void close() {
		closed = true;
	}

	/**
	 * O SoundSystem fecha o stream assim que o som para (fim natural, slider de
	 * volume zerado, /stopsound...). É o sinal mais rápido de interrupção.
	 */
	public boolean isClosed() {
		return closed;
	}

	/** true se todos os frames foram entregues (fim natural da faixa). */
	public boolean isFinished() {
		return index >= frames.size();
	}
}
