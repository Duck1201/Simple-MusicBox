package com.duck.simplemusicbox.component;

import com.duck.simplemusicbox.SimpleMusicBox;
import net.minecraft.component.ComponentType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public class ModComponents {
	public static final ComponentType<TrackData> TRACK = Registry.register(
			Registries.DATA_COMPONENT_TYPE,
			SimpleMusicBox.id("track"),
			ComponentType.<TrackData>builder()
					.codec(TrackData.CODEC)
					.packetCodec(TrackData.PACKET_CODEC.cast())
					.build()
	);

	public static void register() {
		// Estático: o registro acontece na inicialização da classe.
	}
}
