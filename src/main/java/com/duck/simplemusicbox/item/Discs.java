package com.duck.simplemusicbox.item;

import com.duck.simplemusicbox.component.ModComponents;
import com.duck.simplemusicbox.component.TrackData;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Unit;

/**
 * Os três estados do disco personalizado:
 * - virgem: sem faixa (craftado); gravá-lo na jukebox gera um disco físico;
 * - físico: com faixa, item normal que o jogador guarda e carrega;
 * - virtual: com faixa e a marca VIRTUAL, existe só dentro da jukebox.
 */
public final class Discs {
	private Discs() {
	}

	public static boolean isCustom(ItemStack stack) {
		return stack.isOf(ModItems.MUSIC_DISC_CUSTOM);
	}

	public static boolean isBlank(ItemStack stack) {
		return isCustom(stack) && !stack.contains(ModComponents.TRACK);
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
