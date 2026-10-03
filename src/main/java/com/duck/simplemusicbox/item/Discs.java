package com.duck.simplemusicbox.item;

import com.duck.simplemusicbox.component.ModComponents;
import com.duck.simplemusicbox.component.TrackData;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Unit;

/**
 * Discos do mod:
 * - Disco Virgem (item próprio, não toca): o botão Gravar da GUI gasta um e
 *   entrega um disco físico;
 * - físico: disco com faixa, item normal que o jogador guarda e carrega;
 * - virtual: com faixa e a marca VIRTUAL, existe só dentro da jukebox.
 */
public final class Discs {
	private Discs() {
	}

	public static boolean isCustom(ItemStack stack) {
		return stack.isOf(ModItems.MUSIC_DISC_CUSTOM);
	}

	public static boolean isBlank(ItemStack stack) {
		return stack.isOf(ModItems.BLANK_DISC);
	}

	/** Gasta um Disco Virgem do inventário (no criativo não gasta); false se não houver. */
	public static boolean takeBlank(PlayerEntity player) {
		if (player.getAbilities().creativeMode) {
			return true;
		}
		PlayerInventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (isBlank(stack)) {
				stack.decrement(1);
				inventory.markDirty();
				return true;
			}
		}
		return false;
	}

	public static boolean hasBlank(PlayerEntity player) {
		return player.getAbilities().creativeMode || player.getInventory().count(ModItems.BLANK_DISC) > 0;
	}

	public static boolean isVirtual(ItemStack stack) {
		return stack.contains(ModComponents.VIRTUAL);
	}

	/** Disco que o jogador pode guardar: qualquer coisa não vazia e não virtual (inclui vanilla). */
	public static boolean isPhysical(ItemStack stack) {
		return !stack.isEmpty() && !isVirtual(stack);
	}

	/** Disco físico com a faixa (resultado de gravar um Disco Virgem). */
	public static ItemStack recorded(TrackData track) {
		ItemStack stack = new ItemStack(ModItems.MUSIC_DISC_CUSTOM);
		stack.set(ModComponents.TRACK, track);
		stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(track.title())
				.formatted(Formatting.AQUA)
				.styled(style -> style.withItalic(false)));
		return stack;
	}

	/** Disco só para a jukebox tocar (GUI, fila); some ao sair dela. */
	public static ItemStack virtual(TrackData track) {
		ItemStack stack = recorded(track);
		stack.set(ModComponents.VIRTUAL, Unit.INSTANCE);
		return stack;
	}
}
