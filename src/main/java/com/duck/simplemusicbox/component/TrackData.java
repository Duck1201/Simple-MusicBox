package com.duck.simplemusicbox.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

/**
 * Identifica uma faixa baixada: id do vídeo do YouTube, título e duração real.
 * spotifyId: id da faixa no Spotify quando ela foi pedida por um link de lá
 * ("" = veio do YouTube). O áudio sempre vem do YouTube (videoId).
 */
public record TrackData(String videoId, String title, long durationMs, String spotifyId) {
	public static final Codec<TrackData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("video_id").forGetter(TrackData::videoId),
			Codec.STRING.fieldOf("title").forGetter(TrackData::title),
			Codec.LONG.fieldOf("duration_ms").forGetter(TrackData::durationMs),
			// opcional: discos da 1.0 não têm o campo
			Codec.STRING.optionalFieldOf("spotify_id", "").forGetter(TrackData::spotifyId)
	).apply(instance, TrackData::new));

	public static final PacketCodec<ByteBuf, TrackData> PACKET_CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, TrackData::videoId,
			PacketCodecs.STRING, TrackData::title,
			PacketCodecs.VAR_LONG, TrackData::durationMs,
			PacketCodecs.STRING, TrackData::spotifyId,
			TrackData::new
	);

	public TrackData(String videoId, String title, long durationMs) {
		this(videoId, title, durationMs, "");
	}

	public boolean fromSpotify() {
		return !spotifyId.isEmpty();
	}

	public String formatDuration() {
		long totalSeconds = durationMs / 1000;
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		return String.format("%d:%02d", minutes, seconds);
	}
}
