package com.duck.simplemusicbox.client;

import com.duck.simplemusicbox.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.floatprovider.ConstantFloatProvider;

/**
 * Som de jukebox (categoria RECORDS) cujo áudio vem de um {@link OpusAudioStream}.
 *
 * Modo mono: som posicional com atenuação LINEAR do OpenAL, volume 4.0 e
 * alcance de ~64 blocos, idêntico a um disco vanilla.
 *
 * Modo estéreo: o OpenAL não espacializa fontes estéreo, então o som toca
 * "no ouvido" (relativo ao jogador) e este SoundInstance recalcula o volume a
 * cada tick pela distância até a jukebox, na mesma curva linear do vanilla.
 */
public class CustomMusicSoundInstance implements TickableSoundInstance {
	private final Identifier id;
	private final Sound sound;
	private final BlockPos pos;
	private final boolean stereo;
	private float stereoVolume;

	public CustomMusicSoundInstance(Identifier id, BlockPos pos, boolean stereo) {
		this.id = id;
		this.pos = pos;
		this.stereo = stereo;
		this.sound = new Sound(id,
				ConstantFloatProvider.create(1.0f),
				ConstantFloatProvider.create(1.0f),
				1, Sound.RegistrationType.FILE, true, false, 16);
		this.stereoVolume = computeStereoVolume();
	}

	private float computeStereoVolume() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return 0.0f;
		}
		double distance = client.player.getPos().distanceTo(Vec3d.ofCenter(pos));
		double radius = ModConfig.get().audibleRadius;
		return (float) Math.max(0.0, 1.0 - distance / radius);
	}

	@Override
	public void tick() {
		if (stereo) {
			stereoVolume = computeStereoVolume();
		}
	}

	@Override
	public boolean isDone() {
		// O ciclo de vida é controlado pelo ClientPlaybackManager (stop explícito)
		// e pelo fim natural do stream.
		return false;
	}

	@Override
	public Identifier getId() {
		return id;
	}

	@Override
	public WeightedSoundSet getSoundSet(SoundManager soundManager) {
		WeightedSoundSet set = new WeightedSoundSet(id, null);
		set.add(sound);
		return set;
	}

	@Override
	public Sound getSound() {
		return sound;
	}

	@Override
	public SoundCategory getCategory() {
		return SoundCategory.RECORDS;
	}

	@Override
	public boolean isRepeatable() {
		return false;
	}

	@Override
	public boolean isRelative() {
		return stereo;
	}

	@Override
	public int getRepeatDelay() {
		return 0;
	}

	@Override
	public float getVolume() {
		return stereo ? stereoVolume : 4.0f;
	}

	@Override
	public float getPitch() {
		return 1.0f;
	}

	@Override
	public double getX() {
		return stereo ? 0.0 : pos.getX() + 0.5;
	}

	@Override
	public double getY() {
		return stereo ? 0.0 : pos.getY() + 0.5;
	}

	@Override
	public double getZ() {
		return stereo ? 0.0 : pos.getZ() + 0.5;
	}

	@Override
	public AttenuationType getAttenuationType() {
		return stereo ? AttenuationType.NONE : AttenuationType.LINEAR;
	}
}
