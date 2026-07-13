package com.duck.simplemusicbox.mixin;

import com.duck.simplemusicbox.component.ModComponents;
import com.duck.simplemusicbox.component.TrackData;
import com.duck.simplemusicbox.playback.JukeboxLoopAccess;
import com.duck.simplemusicbox.playback.JukeboxSessionManager;
import net.minecraft.block.entity.JukeboxBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Toda mudança no disco da jukebox (inserir pelo jogador, funil, ejetar,
 * quebrar o bloco) passa por setStack/setDisc; daqui sincronizamos as sessões
 * de reprodução customizadas. Também guarda a flag de loop da GUI no NBT.
 */
@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxBlockEntityMixin implements JukeboxLoopAccess {
	@Unique
	private static final String LOOP_NBT_KEY = "simple_musicbox:loop";

	@Unique
	private boolean simple_musicbox$loop;

	@Override
	public boolean simple_musicbox$isLoop() {
		return simple_musicbox$loop;
	}

	@Override
	public void simple_musicbox$setLoop(boolean loop) {
		this.simple_musicbox$loop = loop;
	}

	@Inject(method = "writeNbt", at = @At("TAIL"))
	private void simple_musicbox$writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries,
			CallbackInfo ci) {
		if (simple_musicbox$loop) {
			nbt.putBoolean(LOOP_NBT_KEY, true);
		}
	}

	@Inject(method = "readNbt", at = @At("TAIL"))
	private void simple_musicbox$readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries,
			CallbackInfo ci) {
		this.simple_musicbox$loop = nbt.getBoolean(LOOP_NBT_KEY);
	}

	@Inject(method = "setStack(Lnet/minecraft/item/ItemStack;)V", at = @At("TAIL"))
	private void simple_musicbox$afterSetStack(ItemStack stack, CallbackInfo ci) {
		simple_musicbox$syncSession(stack);
	}

	@Inject(method = "setDisc(Lnet/minecraft/item/ItemStack;)V", at = @At("TAIL"))
	private void simple_musicbox$afterSetDisc(ItemStack stack, CallbackInfo ci) {
		simple_musicbox$syncSession(stack);
	}

	@Unique
	private void simple_musicbox$syncSession(ItemStack stack) {
		JukeboxBlockEntity self = (JukeboxBlockEntity) (Object) this;
		if (!(self.getWorld() instanceof ServerWorld world)) {
			return;
		}
		BlockPos pos = self.getPos();
		TrackData track = stack.get(ModComponents.TRACK);
		if (track != null) {
			if (!JukeboxSessionManager.isPlayingTrack(world, pos, track)) {
				JukeboxSessionManager.start(world, pos, track);
			}
		} else if (stack.isEmpty()) {
			JukeboxSessionManager.stop(world, pos);
		}
	}
}
