package com.duck.simplemusicbox.net;

import com.duck.simplemusicbox.SimpleMusicBox;
import com.duck.simplemusicbox.component.TrackData;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * S2C: abre (ou atualiza) a GUI da jukebox no cliente, com o estado atual e a
 * lista de faixas do cache do servidor.
 */
public record JukeboxGuiOpenPayload(BlockPos pos, boolean loop, boolean paused,
		Optional<TrackData> current, long positionMs, List<TrackData> tracks) implements CustomPayload {
	public static final CustomPayload.Id<JukeboxGuiOpenPayload> ID =
			new CustomPayload.Id<>(SimpleMusicBox.id("gui_open"));

	public static final PacketCodec<RegistryByteBuf, JukeboxGuiOpenPayload> CODEC =
			PacketCodec.of(JukeboxGuiOpenPayload::write, JukeboxGuiOpenPayload::read);

	private void write(RegistryByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeBoolean(loop);
		buf.writeBoolean(paused);
		buf.writeBoolean(current.isPresent());
		current.ifPresent(track -> TrackData.PACKET_CODEC.encode(buf, track));
		buf.writeVarLong(positionMs);
		buf.writeVarInt(tracks.size());
		for (TrackData track : tracks) {
			TrackData.PACKET_CODEC.encode(buf, track);
		}
	}

	private static JukeboxGuiOpenPayload read(RegistryByteBuf buf) {
		BlockPos pos = buf.readBlockPos();
		boolean loop = buf.readBoolean();
		boolean paused = buf.readBoolean();
		Optional<TrackData> current = buf.readBoolean()
				? Optional.of(TrackData.PACKET_CODEC.decode(buf))
				: Optional.empty();
		long positionMs = buf.readVarLong();
		int count = buf.readVarInt();
		List<TrackData> tracks = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			tracks.add(TrackData.PACKET_CODEC.decode(buf));
		}
		return new JukeboxGuiOpenPayload(pos, loop, paused, current, positionMs, tracks);
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
