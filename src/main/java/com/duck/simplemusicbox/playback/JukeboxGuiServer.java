package com.duck.simplemusicbox.playback;

import com.duck.simplemusicbox.ModConfig;
import com.duck.simplemusicbox.SimpleMusicBox;
import com.duck.simplemusicbox.command.MusicCommand;
import com.duck.simplemusicbox.item.Discs;
import com.duck.simplemusicbox.net.JukeboxGuiActionPayload;
import com.duck.simplemusicbox.net.JukeboxGuiOpenPayload;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.JukeboxBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * Lado servidor da GUI da jukebox: abre com shift+clique-direito e executa as
 * ações enviadas pelo cliente (tocar da fila, materializar disco, parar,
 * ejetar, loop, baixar URL). Toda ação valida distância.
 */
public class JukeboxGuiServer {
	/** GUI aberta por comando pode estar um pouco mais longe que um clique. */
	private static final double MAX_USE_DISTANCE = 20.0;
	private static final int COMMAND_SEARCH_RADIUS = 16;

	public static void init() {
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (!player.isSneaking() || !world.getBlockState(hit.getBlockPos()).isOf(Blocks.JUKEBOX)) {
				return ActionResult.PASS;
			}
			if (player instanceof ServerPlayerEntity serverPlayer && world instanceof ServerWorld serverWorld) {
				sendOpen(serverPlayer, serverWorld, hit.getBlockPos());
			}
			return ActionResult.SUCCESS;
		});

		ServerPlayNetworking.registerGlobalReceiver(JukeboxGuiActionPayload.ID, (payload, context) ->
				handleAction(context.player(), payload));

		// /player abre a GUI da jukebox mais próxima (raio de 16 blocos)
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) ->
						dispatcher.register(net.minecraft.server.command.CommandManager.literal("player")
								.executes(context -> {
									ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
									ServerWorld world = player.getServerWorld();
									Optional<BlockPos> nearest = BlockPos.findClosest(
											player.getBlockPos(), COMMAND_SEARCH_RADIUS, COMMAND_SEARCH_RADIUS,
											pos -> world.getBlockState(pos).isOf(Blocks.JUKEBOX));
									if (nearest.isEmpty()) {
										context.getSource().sendError(Text.translatable(
												"simple_musicbox.command.error.no_jukebox",
												COMMAND_SEARCH_RADIUS));
										return 0;
									}
									sendOpen(player, world, nearest.get());
									return 1;
								})));
	}

	public static void sendOpen(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
		boolean loop = world.getBlockEntity(pos) instanceof JukeboxLoopAccess access
				&& access.simple_musicbox$isLoop();
		Optional<JukeboxSessionManager.SessionInfo> session = JukeboxSessionManager.sessionAt(world, pos);
		ServerPlayNetworking.send(player, new JukeboxGuiOpenPayload(
				pos,
				loop,
				session.map(JukeboxSessionManager.SessionInfo::paused).orElse(false),
				session.map(JukeboxSessionManager.SessionInfo::track),
				session.map(JukeboxSessionManager.SessionInfo::positionMs).orElse(0L),
				SimpleMusicBox.trackCache().listTracks()));
	}

	/**
	 * Discos físicos (vanilla, gravados, virgens) são do jogador: antes de a GUI
	 * trocar o conteúdo da jukebox, devolve o disco a ele. Virtuais só somem.
	 */
	private static void returnPhysicalDisc(ServerPlayerEntity player, JukeboxBlockEntity jukebox) {
		if (Discs.isPhysical(jukebox.getStack())) {
			player.getInventory().offerOrDrop(jukebox.emptyStack());
		}
	}

	private static void giveRecorded(ServerPlayerEntity player, com.duck.simplemusicbox.component.TrackData track) {
		player.getInventory().offerOrDrop(Discs.recorded(track));
		player.sendMessage(Text.translatable("simple_musicbox.gui.recorded",
				Text.literal(track.title()).formatted(Formatting.AQUA)), false);
	}

	private static void handleAction(ServerPlayerEntity player, JukeboxGuiActionPayload payload) {
		ServerWorld world = player.getServerWorld();
		BlockPos pos = payload.pos();
		if (player.getPos().distanceTo(Vec3d.ofCenter(pos)) > MAX_USE_DISTANCE
				|| !world.getBlockState(pos).isOf(Blocks.JUKEBOX)) {
			return;
		}
		JukeboxBlockEntity jukebox = world.getBlockEntity(pos) instanceof JukeboxBlockEntity entity
				? entity : null;

		switch (payload.action()) {
			case PLAY -> {
				if (jukebox != null) {
					SimpleMusicBox.trackCache().readHeader(payload.argument()).ifPresent(track -> {
						returnPhysicalDisc(player, jukebox);
						jukebox.setStack(Discs.virtual(track));
					});
					sendOpen(player, world, pos);
				}
			}
			case STOP -> {
				JukeboxSessionManager.stop(world, pos);
				if (jukebox != null) {
					jukebox.getManager().stopPlaying(world, world.getBlockState(pos));
				}
				sendOpen(player, world, pos);
			}
			case SKIP -> {
				if (jukebox != null) {
					returnPhysicalDisc(player, jukebox);
				}
				JukeboxSessionManager.skip(world, pos);
				sendOpen(player, world, pos);
			}
			case TOGGLE_PAUSE -> {
				JukeboxSessionManager.togglePause(world, pos);
				sendOpen(player, world, pos);
			}
			case EJECT -> {
				// Pela GUI o disco vai direto para o inventário (útil com /player,
				// longe do bloco); o clique vanilla continua ejetando no mundo.
				// Disco virtual sai vazio (mixin): só para a música.
				if (jukebox != null) {
					net.minecraft.item.ItemStack disc = jukebox.emptyStack();
					if (!disc.isEmpty()) {
						player.getInventory().offerOrDrop(disc);
					}
				}
				sendOpen(player, world, pos);
			}
			case TOGGLE_LOOP -> {
				if (jukebox instanceof JukeboxLoopAccess access) {
					boolean loop = !access.simple_musicbox$isLoop();
					access.simple_musicbox$setLoop(loop);
					jukebox.markDirty();
					player.sendMessage(Text.translatable(loop
							? "simple_musicbox.loop.enabled" : "simple_musicbox.loop.disabled"), true);
					sendOpen(player, world, pos);
				}
			}
			case RECORD -> SimpleMusicBox.trackCache().readHeader(payload.argument()).ifPresent(track -> {
				if (Discs.takeBlank(player)) {
					giveRecorded(player, track);
				} else {
					player.sendMessage(Text.translatable("simple_musicbox.gui.need_blank_disc")
							.formatted(Formatting.RED), false);
				}
			});
			case DOWNLOAD -> {
				// Modo sobrevivência: cada música nova custa um Disco Virgem, e já sai gravada
				boolean requireBlank = ModConfig.get().requireBlankDiscToDownload;
				if (requireBlank && !Discs.hasBlank(player)) {
					player.sendMessage(Text.translatable("simple_musicbox.gui.need_blank_disc")
							.formatted(Formatting.RED), false);
					return;
				}
				player.sendMessage(Text.translatable("simple_musicbox.command.downloading")
						.formatted(Formatting.GRAY), false);
				SimpleMusicBox.downloader().download(payload.argument()).whenComplete((track, error) ->
						world.getServer().execute(() -> {
							if (error != null) {
								player.sendMessage(MusicCommand.describeError(
										error instanceof java.util.concurrent.CompletionException
												&& error.getCause() != null ? error.getCause() : error)
										.copy().formatted(Formatting.RED), false);
								return;
							}
							if (requireBlank && Discs.takeBlank(player)) {
								giveRecorded(player, track);
							} else {
								player.sendMessage(Text.translatable("simple_musicbox.command.added",
										Text.literal(track.title()).formatted(Formatting.AQUA)), false);
							}
							// Atualiza a lista se o jogador ainda estiver por perto
							if (player.getPos().distanceTo(Vec3d.ofCenter(pos)) <= MAX_USE_DISTANCE
									&& world.getBlockState(pos).isOf(Blocks.JUKEBOX)) {
								sendOpen(player, world, pos);
							}
						}));
			}
		}
	}
}
