package com.duck.simplemusicbox.playback;

/**
 * Implementada pelo JukeboxBlockEntity via mixin: flag "repetir a faixa atual"
 * configurável na GUI e persistida no NBT do bloco.
 */
public interface JukeboxLoopAccess {
	boolean simple_musicbox$isLoop();

	void simple_musicbox$setLoop(boolean loop);
}
