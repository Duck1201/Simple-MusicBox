package com.duck.simplemusicbox;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;

public class ModSounds {
	/**
	 * Som silencioso usado pelo jukebox_song dummy: a jukebox vanilla "toca"
	 * isso enquanto o áudio real vem do OpusAudioStream.
	 */
	public static final SoundEvent SILENCE = Registry.register(
			Registries.SOUND_EVENT,
			SimpleMusicBox.id("silence"),
			SoundEvent.of(SimpleMusicBox.id("silence"))
	);

	public static void register() {
	}
}
