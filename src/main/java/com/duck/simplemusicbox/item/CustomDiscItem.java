package com.duck.simplemusicbox.item;

import com.duck.simplemusicbox.component.ModComponents;
import com.duck.simplemusicbox.component.TrackData;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public class CustomDiscItem extends Item {
	public CustomDiscItem(Settings settings) {
		super(settings);
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		TrackData track = stack.get(ModComponents.TRACK);
		if (track != null) {
			tooltip.add(Text.literal(track.title()).formatted(Formatting.GRAY));
			tooltip.add(Text.literal(track.formatDuration() + (track.fromSpotify()
							? " • Spotify: " + track.spotifyId()
							: " • YouTube: " + track.videoId()))
					.formatted(Formatting.DARK_GRAY));
		} else {
			tooltip.add(Text.translatable("item.simple_musicbox.music_disc_custom.empty")
					.formatted(Formatting.DARK_GRAY));
		}
	}
}
