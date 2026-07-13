package com.duck.simplemusicbox.audio;

import com.duck.simplemusicbox.component.TrackData;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Formato .smb: cabeçalho (magic, videoId, título, duração) seguido de frames
 * Opus (20 ms, 48 kHz estéreo) prefixados por tamanho (u16). Um tamanho 0
 * marca o fim — arquivos sem esse marcador são considerados truncados.
 */
public final class SmbFileFormat {
	private static final int MAGIC = 0x534D4231; // "SMB1"

	public record SmbFile(TrackData track, List<byte[]> frames) {
	}

	private SmbFileFormat() {
	}

	public static void write(OutputStream out, TrackData track, List<byte[]> frames) throws IOException {
		DataOutputStream data = new DataOutputStream(out);
		data.writeInt(MAGIC);
		data.writeUTF(track.videoId());
		data.writeUTF(track.title());
		data.writeLong(track.durationMs());
		for (byte[] frame : frames) {
			data.writeShort(frame.length);
			data.write(frame);
		}
		data.writeShort(0);
		data.flush();
	}

	public static SmbFile read(InputStream in) throws IOException {
		DataInputStream data = new DataInputStream(in);
		if (data.readInt() != MAGIC) {
			throw new IOException("Not a Simple MusicBox track file");
		}
		String videoId = data.readUTF();
		String title = data.readUTF();
		long durationMs = data.readLong();
		List<byte[]> frames = new ArrayList<>();
		while (true) {
			int length = data.readUnsignedShort();
			if (length == 0) {
				return new SmbFile(new TrackData(videoId, title, durationMs), frames);
			}
			byte[] frame = new byte[length];
			data.readFully(frame);
			frames.add(frame);
		}
	}

	/** Lê apenas o cabeçalho, sem carregar os frames. */
	public static TrackData readHeader(InputStream in) throws IOException {
		DataInputStream data = new DataInputStream(in);
		if (data.readInt() != MAGIC) {
			throw new IOException("Not a Simple MusicBox track file");
		}
		String videoId = data.readUTF();
		String title = data.readUTF();
		long durationMs = data.readLong();
		return new TrackData(videoId, title, durationMs);
	}

	/** Valida que o arquivo termina com o marcador de fim. */
	public static boolean isComplete(InputStream in) {
		try {
			read(in);
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}
}
