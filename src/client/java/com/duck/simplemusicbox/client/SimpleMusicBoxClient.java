package com.duck.simplemusicbox.client;

import com.duck.simplemusicbox.SimpleMusicBox;
import com.duck.simplemusicbox.client.gui.JukeboxScreen;
import com.duck.simplemusicbox.component.ModComponents;
import com.duck.simplemusicbox.item.ModItems;
import com.duck.simplemusicbox.net.JukeboxGuiOpenPayload;
import com.duck.simplemusicbox.net.PlayTrackPayload;
import com.duck.simplemusicbox.net.StopTrackPayload;
import com.duck.simplemusicbox.net.TrackChunkPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.item.ModelPredicateProviderRegistry;

public class SimpleMusicBoxClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlaybackManager.init(MinecraftClient.getInstance());

		// Disco Virgem (sem faixa) usa o modelo de CD vazio (models/item/music_disc_custom.json)
		ModelPredicateProviderRegistry.register(ModItems.MUSIC_DISC_CUSTOM, SimpleMusicBox.id("blank"),
				(stack, world, entity, seed) -> stack.contains(ModComponents.TRACK) ? 0 : 1);

		ClientPlayNetworking.registerGlobalReceiver(PlayTrackPayload.ID, (payload, context) ->
				ClientPlaybackManager.onPlay(context.client(), payload));
		ClientPlayNetworking.registerGlobalReceiver(StopTrackPayload.ID, (payload, context) ->
				ClientPlaybackManager.onStop(context.client(), payload.pos()));
		ClientPlayNetworking.registerGlobalReceiver(TrackChunkPayload.ID, (payload, context) ->
				ClientPlaybackManager.onChunk(context.client(), payload));
		ClientPlayNetworking.registerGlobalReceiver(JukeboxGuiOpenPayload.ID, (payload, context) -> {
			if (context.client().currentScreen instanceof JukeboxScreen screen
					&& screen.getPos().equals(payload.pos())) {
				screen.update(payload);
			} else {
				context.client().setScreen(new JukeboxScreen(payload));
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				ClientPlaybackManager.reset(client));
		ClientTickEvents.END_CLIENT_TICK.register(ClientPlaybackManager::tick);
	}
}
