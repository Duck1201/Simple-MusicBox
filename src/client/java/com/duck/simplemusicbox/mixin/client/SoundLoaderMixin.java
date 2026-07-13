package com.duck.simplemusicbox.mixin.client;

import com.duck.simplemusicbox.SimpleMusicBox;
import com.duck.simplemusicbox.client.ClientPlaybackManager;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Entrega nosso OpusAudioStream quando o SoundSystem pede o "arquivo" de um
 * som simple_musicbox:sounds/stream/<uuid>.ogg, que não existe em resource
 * pack nenhum.
 */
@Mixin(SoundLoader.class)
public class SoundLoaderMixin {
	@Inject(method = "loadStreamed", at = @At("HEAD"), cancellable = true)
	private void simple_musicbox$loadStreamed(Identifier id, boolean repeatInstantly,
			CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
		if (id.getNamespace().equals(SimpleMusicBox.MOD_ID) && id.getPath().startsWith("sounds/stream/")) {
			AudioStream stream = ClientPlaybackManager.takeStream(id);
			if (stream != null) {
				cir.setReturnValue(CompletableFuture.completedFuture(stream));
			}
		}
	}
}
