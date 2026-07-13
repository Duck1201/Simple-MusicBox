package com.duck.simplemusicbox.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

/**
 * Identifica uma faixa baixada: id do vídeo do YouTube, título e duração real.
 */
public record TrackData(String videoId, String title, long durationMs) {
	public static final Codec<TrackData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("video_id").forGetter(TrackData::videoId),
			Codec.STRING.fieldOf("title").forGetter(TrackData::title),
			Codec.LONG.fieldOf("duration_ms").forGetter(TrackData::durationMs)
	).apply(instance, TrackData::new));

	public static final PacketCodec<ByteBuf, TrackData> PACKET_CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, TrackData::videoId,
			PacketCodecs.STRING, TrackData::title,
			PacketCodecs.VAR_LONG, TrackData::durationMs,
			TrackData::new
	);

	public String formatDuration() {
		long totalSeconds = durationMs / 1000;
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		return String.format("%d:%02d", minutes, seconds);
	}
}
