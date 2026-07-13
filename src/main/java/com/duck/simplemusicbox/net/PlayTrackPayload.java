package com.duck.simplemusicbox.net;

import com.duck.simplemusicbox.SimpleMusicBox;
import com.duck.simplemusicbox.component.TrackData;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

/** S2C: toca uma faixa na jukebox em {@code pos}, começando em {@code offsetMs}. */
public record PlayTrackPayload(BlockPos pos, TrackData track, long offsetMs) implements CustomPayload {
	public static final CustomPayload.Id<PlayTrackPayload> ID =
			new CustomPayload.Id<>(SimpleMusicBox.id("play_track"));

	public static final PacketCodec<RegistryByteBuf, PlayTrackPayload> CODEC = PacketCodec.tuple(
			BlockPos.PACKET_CODEC, PlayTrackPayload::pos,
			TrackData.PACKET_CODEC, PlayTrackPayload::track,
			PacketCodecs.VAR_LONG, PlayTrackPayload::offsetMs,
			PlayTrackPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
