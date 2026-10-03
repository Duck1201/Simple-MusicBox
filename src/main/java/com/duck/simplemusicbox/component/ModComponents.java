package com.duck.simplemusicbox.component;

import com.duck.simplemusicbox.SimpleMusicBox;
import net.minecraft.component.ComponentType;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Unit;

public class ModComponents {
	public static final ComponentType<TrackData> TRACK = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			SimpleMusicBox.id("track"),
			ComponentType.<TrackData>builder()
					.codec(TrackData.CODEC)
					.packetCodec(TrackData.PACKET_CODEC.cast())
					.build()
	);

	/**
	 * Disco "virtual": criado pela GUI ou pela fila só para a jukebox tocar.
	 * Nunca sai da jukebox como item (ver JukeboxBlockEntityMixin) — discos
	 * físicos só nascem gravando um Disco Virgem.
	 */
	public static final ComponentType<Unit> VIRTUAL = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			SimpleMusicBox.id("virtual"),
			ComponentType.<Unit>builder()
					.codec(Unit.CODEC)
					.packetCodec(PacketCodec.unit(Unit.INSTANCE))
					.build()
	);

	public static void register() {
		// Estático: o registro acontece na inicialização da classe.
	}
}
