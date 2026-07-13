package com.duck.simplemusicbox.item;

import com.duck.simplemusicbox.SimpleMusicBox;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.block.jukebox.JukeboxSong;
import net.minecraft.util.Rarity;

public class ModItems {
	public static final RegistryKey<JukeboxSong> CUSTOM_SONG_KEY =
			RegistryKey.of(RegistryKeys.JUKEBOX_SONG, SimpleMusicBox.id("custom"));

	public static final Item MUSIC_DISC_CUSTOM = Registry.register(
			Registries.ITEM,
			SimpleMusicBox.id("music_disc_custom"),
			new CustomDiscItem(new Item.Settings()
					.maxCount(1)
					.rarity(Rarity.RARE)
					.jukeboxPlayable(CUSTOM_SONG_KEY))
	);

	public static void register() {
	}
}
