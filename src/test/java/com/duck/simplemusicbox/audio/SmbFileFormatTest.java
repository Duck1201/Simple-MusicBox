package com.duck.simplemusicbox.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.duck.simplemusicbox.component.TrackData;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmbFileFormatTest {
	@Test
	void roundTripKeepsSpotifyId() throws IOException {
		TrackData track = new TrackData("72UO0v5ESUo", "Luis Fonsi - Despacito", 228_000, "6habFhsOp2NvshLv26DqMb");
		List<byte[]> frames = List.of(new byte[] {1, 2, 3}, new byte[] {4});
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		SmbFileFormat.write(out, track, frames);

		SmbFileFormat.SmbFile file = SmbFileFormat.read(new ByteArrayInputStream(out.toByteArray()));
		assertEquals(track, file.track());
		assertTrue(file.track().fromSpotify());
		assertEquals(2, file.frames().size());
		assertArrayEquals(frames.get(0), file.frames().get(0));
	}

	@Test
	void readsVersion1FilesFromTheFirstRelease() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream data = new DataOutputStream(bytes);
		data.writeInt(0x534D4231); // "SMB1"
		data.writeUTF("dQw4w9WgXcQ");
		data.writeUTF("Never Gonna Give You Up");
		data.writeLong(213_000);
		data.writeShort(2);
		data.write(new byte[] {9, 9});
		data.writeShort(0);

		TrackData track = SmbFileFormat.readHeader(new ByteArrayInputStream(bytes.toByteArray()));
		assertEquals(new TrackData("dQw4w9WgXcQ", "Never Gonna Give You Up", 213_000), track);
		assertFalse(track.fromSpotify());
		assertTrue(SmbFileFormat.isComplete(new ByteArrayInputStream(bytes.toByteArray())));
	}

	@Test
	void truncatedFileIsIncomplete() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		SmbFileFormat.write(out, new TrackData("abc", "t", 1000), List.of(new byte[] {1, 2, 3}));
		byte[] whole = out.toByteArray();
		byte[] cut = Arrays.copyOf(whole, whole.length - 2); // sem o marcador de fim
		assertFalse(SmbFileFormat.isComplete(new ByteArrayInputStream(cut)));
	}
}
