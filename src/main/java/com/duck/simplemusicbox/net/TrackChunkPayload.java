package com.duck.simplemusicbox.net;

import com.duck.simplemusicbox.SimpleMusicBox;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/** S2C: um pedaço do arquivo .smb da faixa {@code videoId}. */
public record TrackChunkPayload(String videoId, int index, int total, byte[] data) implements CustomPayload {
	public static final CustomPayload.Id<TrackChunkPayload> ID =
			new CustomPayload.Id<>(SimpleMusicBox.id("track_chunk"));

	public static final PacketCodec<RegistryByteBuf, TrackChunkPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, TrackChunkPayload::videoId,
			PacketCodecs.VAR_INT, TrackChunkPayload::index,
			PacketCodecs.VAR_INT, TrackChunkPayload::total,
			PacketCodecs.BYTE_ARRAY, TrackChunkPayload::data,
			TrackChunkPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
