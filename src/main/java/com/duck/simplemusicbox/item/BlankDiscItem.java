package com.duck.simplemusicbox.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** Disco Virgem: não toca; é gasto pelo botão Gravar da GUI da jukebox. */
public class BlankDiscItem extends Item {
	public BlankDiscItem(Settings settings) {
		super(settings);
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		tooltip.add(Text.translatable("item.simple_musicbox.blank_disc.tooltip").formatted(Formatting.GRAY));
	}
}
