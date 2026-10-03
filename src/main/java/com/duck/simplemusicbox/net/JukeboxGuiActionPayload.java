package com.duck.simplemusicbox.net;

import com.duck.simplemusicbox.SimpleMusicBox;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

/**
 * C2S: uma ação feita na GUI da jukebox. {@code argument} carrega o videoId
 * (PLAY/MATERIALIZE) ou a URL (DOWNLOAD); vazio nas demais.
 */
public record JukeboxGuiActionPayload(BlockPos pos, Action action, String argument) implements CustomPayload {
	public enum Action {
		PLAY,
		STOP,
		SKIP,
		TOGGLE_PAUSE,
		EJECT,
		TOGGLE_LOOP,
		DOWNLOAD,
		/** Gasta um Disco Virgem do inventário e entrega o disco gravado da faixa. */
		RECORD
	}

	public static final CustomPayload.Id<JukeboxGuiActionPayload> ID =
			new CustomPayload.Id<>(SimpleMusicBox.id("gui_action"));

	public static final PacketCodec<RegistryByteBuf, JukeboxGuiActionPayload> CODEC =
			PacketCodec.of(JukeboxGuiActionPayload::write, JukeboxGuiActionPayload::read);

	private void write(RegistryByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeEnumConstant(action);
		buf.writeString(argument, 512);
	}

	private static JukeboxGuiActionPayload read(RegistryByteBuf buf) {
		return new JukeboxGuiActionPayload(
				buf.readBlockPos(), buf.readEnumConstant(Action.class), buf.readString(512));
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
