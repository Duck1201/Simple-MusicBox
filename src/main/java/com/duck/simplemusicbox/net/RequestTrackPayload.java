package com.duck.simplemusicbox.net;

import com.duck.simplemusicbox.SimpleMusicBox;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/** C2S: o cliente não tem a faixa no cache e pede os bytes ao servidor. */
public record RequestTrackPayload(String videoId) implements CustomPayload {
	public static final CustomPayload.Id<RequestTrackPayload> ID =
			new CustomPayload.Id<>(SimpleMusicBox.id("request_track"));

	public static final PacketCodec<RegistryByteBuf, RequestTrackPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, RequestTrackPayload::videoId,
			RequestTrackPayload::new
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
