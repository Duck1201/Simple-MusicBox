package com.duck.simplemusicbox.net;

import com.duck.simplemusicbox.SimpleMusicBox;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

/** S2C: para a música da jukebox em {@code pos}. */
public record StopTrackPayload(BlockPos pos) implements CustomPayload {
	public static final CustomPayload.Id<StopTrackPayload> ID =
			new CustomPayload.Id<>(SimpleMusicBox.id("stop_track"));

	public static final PacketCodec<RegistryByteBuf, StopTrackPayload> CODEC = PacketCodec.tuple(
			BlockPos.PACKET_CODEC, StopTrackPayload::pos,
			StopTrackPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
