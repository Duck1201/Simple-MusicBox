package com.duck.simplemusicbox.client.gui;

import com.duck.simplemusicbox.client.ClientPlaybackManager;
import com.duck.simplemusicbox.component.TrackData;
import com.duck.simplemusicbox.item.ModItems;
import com.duck.simplemusicbox.net.JukeboxGuiActionPayload;
import com.duck.simplemusicbox.net.JukeboxGuiOpenPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * GUI da jukebox: faixa atual com progresso, controles compactos, lista visual
 * das faixas do cache (ícone de disco, título + duração, destaque na que está
 * tocando) e download por URL.
 */
public class JukeboxScreen extends Screen {
	private static final int PANEL_WIDTH = 288;
	private static final int PANEL_HEIGHT = 226;
	private static final int ROW_HEIGHT = 24;
	private static final int VISIBLE_ROWS = 4;
	private static final int LIST_TOP_OFFSET = 94;
	private static final int RECORD_BUTTON_WIDTH = 44;
	private static final int RECORD_BUTTON_HEIGHT = 14;

	private static final int COLOR_PANEL = 0xF0101014;
	private static final int COLOR_BORDER = 0xFF3A3A44;
	private static final int COLOR_LIST_BG = 0xFF17171C;
	private static final int COLOR_ROW_EVEN = 0xFF1D1D24;
	private static final int COLOR_ROW_HOVER = 0x28FFFFFF;
	private static final int COLOR_ROW_PLAYING = 0x3355DCDC;
	private static final int COLOR_ACCENT = 0xFF55DCDC;
	private static final int COLOR_BAR_BG = 0xFF2A2A31;

	private static final ItemStack DISC_ICON = new ItemStack(ModItems.MUSIC_DISC_CUSTOM);

	private final BlockPos pos;
	private boolean loop;
	private boolean paused;
	private Optional<TrackData> current;
	private long basePositionMs;
	private long receivedAtMs;
	private List<TrackData> tracks;

	private final List<TrackData> filtered = new ArrayList<>();
	private int scroll;
	private ButtonWidget loopButton;
	private ButtonWidget pauseButton;
	private TextFieldWidget searchField;
	private TextFieldWidget urlField;
	private String searchText = "";
	private String urlText = "";

	private int left;
	private int top;

	/** Ângulo do disco girando na linha da faixa atual (congela no pause). */
	private float discAngle;
	private long lastSpinTimeMs = System.currentTimeMillis();

	public JukeboxScreen(JukeboxGuiOpenPayload payload) {
		super(Text.translatable("simple_musicbox.gui.title"));
		this.pos = payload.pos();
		apply(payload);
	}

	public BlockPos getPos() {
		return pos;
	}

	/** Atualização vinda do servidor (após uma ação ou download). */
	public void update(JukeboxGuiOpenPayload payload) {
		apply(payload);
		if (loopButton != null) {
			loopButton.setMessage(loopLabel());
		}
		if (pauseButton != null) {
			pauseButton.setMessage(pauseLabel());
		}
		refilter();
	}

	private void apply(JukeboxGuiOpenPayload payload) {
		this.loop = payload.loop();
		this.paused = payload.paused();
		this.current = payload.current();
		this.basePositionMs = payload.positionMs();
		this.receivedAtMs = System.currentTimeMillis();
		this.tracks = payload.tracks();
	}

	@Override
	protected void init() {
		left = (width - PANEL_WIDTH) / 2;
		top = (height - PANEL_HEIGHT) / 2;

		// Controles compactos numa fileira só
		int y = top + 42;
		pauseButton = addDrawableChild(ButtonWidget.builder(pauseLabel(),
						button -> sendAction(JukeboxGuiActionPayload.Action.TOGGLE_PAUSE, ""))
				.dimensions(left + 8, y, 56, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("simple_musicbox.gui.stop"),
						button -> sendAction(JukeboxGuiActionPayload.Action.STOP, ""))
				.dimensions(left + 66, y, 44, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("simple_musicbox.gui.skip"),
						button -> sendAction(JukeboxGuiActionPayload.Action.SKIP, ""))
				.dimensions(left + 112, y, 56, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("simple_musicbox.gui.eject"),
						button -> sendAction(JukeboxGuiActionPayload.Action.EJECT, ""))
				.dimensions(left + 170, y, 46, 20).build());
		loopButton = addDrawableChild(ButtonWidget.builder(loopLabel(),
						button -> sendAction(JukeboxGuiActionPayload.Action.TOGGLE_LOOP, ""))
				.dimensions(left + 218, y, 62, 20).build());

		// Busca com altura confortável
		searchField = addDrawableChild(new TextFieldWidget(textRenderer,
				left + 8, top + 68, PANEL_WIDTH - 16, 20,
				Text.translatable("simple_musicbox.gui.search")));
		searchField.setMaxLength(64);
		searchField.setPlaceholder(Text.translatable("simple_musicbox.gui.search")
				.formatted(Formatting.DARK_GRAY));
		searchField.setText(searchText);
		searchField.setChangedListener(text -> {
			searchText = text;
			refilter();
		});

		// URL + Baixar
		int urlY = top + PANEL_HEIGHT - 28;
		urlField = addDrawableChild(new TextFieldWidget(textRenderer,
				left + 8, urlY, PANEL_WIDTH - 84, 20,
				Text.translatable("simple_musicbox.gui.url")));
		urlField.setMaxLength(512);
		urlField.setPlaceholder(Text.translatable("simple_musicbox.gui.url")
				.formatted(Formatting.DARK_GRAY));
		urlField.setText(urlText);
		urlField.setChangedListener(text -> urlText = text);

		addDrawableChild(ButtonWidget.builder(Text.translatable("simple_musicbox.gui.download"),
						button -> {
							if (!urlText.isBlank()) {
								sendAction(JukeboxGuiActionPayload.Action.DOWNLOAD, urlText.trim());
								urlField.setText("");
							}
						})
				.dimensions(left + PANEL_WIDTH - 72, urlY, 64, 20).build());

		refilter();
	}

	private Text loopLabel() {
		return Text.translatable(loop ? "simple_musicbox.gui.loop_on" : "simple_musicbox.gui.loop_off");
	}

	private Text pauseLabel() {
		return Text.translatable(paused ? "simple_musicbox.gui.resume" : "simple_musicbox.gui.pause");
	}

	@Override
	public void tick() {
		// A faixa pode trocar sem ação do jogador (fila/loop): acompanha o
		// estado local de reprodução em vez de esperar um refresh do servidor.
		// Quando não há som local (ex.: pause), mantém o snapshot do servidor.
		Optional<ClientPlaybackManager.PlaybackInfo> info = ClientPlaybackManager.infoAt(pos);
		if (info.isEmpty()) {
			return;
		}
		String liveId = info.get().track().videoId();
		String shownId = current.map(TrackData::videoId).orElse(null);
		if (!liveId.equals(shownId)) {
			current = Optional.of(info.get().track());
			basePositionMs = info.get().positionMs();
			receivedAtMs = System.currentTimeMillis();
			paused = false;
			pauseButton.setMessage(pauseLabel());
		}
	}

	private void refilter() {
		filtered.clear();
		String query = searchText.toLowerCase(Locale.ROOT).trim();
		for (TrackData track : tracks) {
			if (query.isEmpty() || track.title().toLowerCase(Locale.ROOT).contains(query)) {
				filtered.add(track);
			}
		}
		scroll = Math.clamp(scroll, 0, Math.max(0, filtered.size() - VISIBLE_ROWS));
	}

	private void sendAction(JukeboxGuiActionPayload.Action action, String argument) {
		ClientPlayNetworking.send(new JukeboxGuiActionPayload(pos, action, argument));
	}

	private int listTop() {
		return top + LIST_TOP_OFFSET;
	}

	private long positionNowMs() {
		if (paused) {
			return basePositionMs; // congelada
		}
		return ClientPlaybackManager.infoAt(pos)
				.map(ClientPlaybackManager.PlaybackInfo::positionMs)
				.orElseGet(() -> current.isEmpty() ? 0
						: Math.min(basePositionMs + (System.currentTimeMillis() - receivedAtMs),
								current.get().durationMs()));
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		super.renderBackground(context, mouseX, mouseY, delta);

		context.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, COLOR_PANEL);
		context.drawBorder(left, top, PANEL_WIDTH, PANEL_HEIGHT, COLOR_BORDER);

		context.drawText(textRenderer, title, left + 8, top + 6, 0xFFFFFF, true);
		// No criativo gravar não gasta disco: mostra infinito em vez de contar
		boolean creative = client != null && client.player != null && client.player.getAbilities().creativeMode;
		Text blanks = Text.translatable("simple_musicbox.gui.blank_count",
				creative ? "∞" : String.valueOf(blankDiscCount()));
		context.drawText(textRenderer, blanks, left + PANEL_WIDTH - 8 - textRenderer.getWidth(blanks), top + 6,
				blankDiscCount() > 0 ? 0xC8C8D0 : 0x666666, false);

		// Faixa atual + barra de progresso
		Text nowPlaying = current.map(track -> (Text) Text.literal(track.title()).formatted(Formatting.AQUA))
				.orElse(Text.translatable("simple_musicbox.gui.nothing").formatted(Formatting.DARK_GRAY));
		context.drawText(textRenderer,
				textRenderer.trimToWidth(nowPlaying.getString(), PANEL_WIDTH - 16),
				left + 8, top + 17, current.isPresent() ? 0x55DCDC : 0x666666, false);

		int barX = left + 8;
		int barY = top + 30;
		int barWidth = PANEL_WIDTH - 76;
		context.fill(barX, barY, barX + barWidth, barY + 6, COLOR_BAR_BG);
		if (current.isPresent()) {
			TrackData track = current.get();
			long position = positionNowMs();
			int filledWidth = (int) (barWidth * Math.clamp(
					position / (double) Math.max(1, track.durationMs()), 0.0, 1.0));
			context.fill(barX, barY, barX + filledWidth, barY + 6, COLOR_ACCENT);
			String time = formatMs(position) + " / " + track.formatDuration();
			context.drawText(textRenderer, time, barX + barWidth + 5, barY - 1, 0xAAAAAA, false);
		}

		// Lista visual de faixas
		int listX = left + 8;
		int listY = listTop();
		int listWidth = PANEL_WIDTH - 16;
		int listHeight = VISIBLE_ROWS * ROW_HEIGHT;
		context.fill(listX, listY, listX + listWidth, listY + listHeight, COLOR_LIST_BG);

		long now = System.currentTimeMillis();
		if (!paused && current.isPresent()) {
			discAngle = (discAngle + (now - lastSpinTimeMs) * 0.12f) % 360f;
		}
		lastSpinTimeMs = now;

		for (int i = 0; i < VISIBLE_ROWS; i++) {
			int index = scroll + i;
			if (index >= filtered.size()) {
				break;
			}
			TrackData track = filtered.get(index);
			int rowY = listY + i * ROW_HEIGHT;
			boolean playing = current.isPresent()
					&& current.get().videoId().equals(track.videoId());
			boolean hovered = mouseX >= listX && mouseX < listX + listWidth
					&& mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

			if (index % 2 == 0) {
				context.fill(listX, rowY, listX + listWidth, rowY + ROW_HEIGHT, COLOR_ROW_EVEN);
			}
			if (playing) {
				context.fill(listX, rowY, listX + listWidth, rowY + ROW_HEIGHT, COLOR_ROW_PLAYING);
				context.fill(listX, rowY, listX + 2, rowY + ROW_HEIGHT, COLOR_ACCENT);
			}
			if (hovered) {
				context.fill(listX, rowY, listX + listWidth, rowY + ROW_HEIGHT, COLOR_ROW_HOVER);
			}

			if (playing) {
				// Disco girando: rotação em torno do centro do ícone (16x16)
				float centerX = listX + 5 + 8;
				float centerY = rowY + 4 + 8;
				context.getMatrices().push();
				context.getMatrices().translate(centerX, centerY, 0);
				context.getMatrices().multiply(
						net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(discAngle));
				context.getMatrices().translate(-centerX, -centerY, 0);
				context.drawItem(DISC_ICON, listX + 5, rowY + 4);
				context.getMatrices().pop();
			} else {
				context.drawItem(DISC_ICON, listX + 5, rowY + 4);
			}

			boolean showRecord = blankDiscCount() > 0;
			String label = textRenderer.trimToWidth(track.title(),
					listWidth - 34 - (showRecord ? RECORD_BUTTON_WIDTH + 4 : 0));
			context.drawText(textRenderer, label, listX + 26, rowY + 3,
					playing ? 0x55DCDC : 0xE8E8E8, false);
			String subtitle = playing
					? track.formatDuration() + "  •  " + Text.translatable(
							paused ? "simple_musicbox.gui.paused_tag" : "simple_musicbox.gui.playing_tag")
							.getString()
					: track.formatDuration();
			context.drawText(textRenderer, subtitle, listX + 26, rowY + 13,
					playing ? 0x3FA8A8 : 0x777777, false);
			if (showRecord) {
				drawRecordButton(context, rowY, mouseX, mouseY);
			}
		}

		// Scrollbar
		if (filtered.size() > VISIBLE_ROWS) {
			int thumbHeight = Math.max(10, listHeight * VISIBLE_ROWS / filtered.size());
			int thumbY = listY + (listHeight - thumbHeight) * scroll
					/ Math.max(1, filtered.size() - VISIBLE_ROWS);
			context.fill(listX + listWidth - 2, listY, listX + listWidth, listY + listHeight, COLOR_BAR_BG);
			context.fill(listX + listWidth - 2, thumbY, listX + listWidth, thumbY + thumbHeight, 0xFF888899);
		}
	}

	/**
	 * Botão "Gravar" à direita da linha: gasta um Disco Virgem e entrega o disco
	 * da faixa. Só aparece quando o jogador tem Disco Virgem (ou está no criativo).
	 */
	private void drawRecordButton(DrawContext context, int rowY, int mouseX, int mouseY) {
		int x = recordButtonX();
		int y = rowY + (ROW_HEIGHT - RECORD_BUTTON_HEIGHT) / 2;
		boolean hovered = mouseX >= x && mouseX < x + RECORD_BUTTON_WIDTH
				&& mouseY >= y && mouseY < y + RECORD_BUTTON_HEIGHT;
		context.fill(x, y, x + RECORD_BUTTON_WIDTH, y + RECORD_BUTTON_HEIGHT, hovered ? 0xFF4A4A58 : 0xFF33333D);
		context.drawBorder(x, y, RECORD_BUTTON_WIDTH, RECORD_BUTTON_HEIGHT, 0xFF6A6A78);
		Text label = Text.translatable("simple_musicbox.gui.record");
		context.drawText(textRenderer, label, x + (RECORD_BUTTON_WIDTH - textRenderer.getWidth(label)) / 2,
				y + 3, 0xFFFFFF, false);
	}

	private int recordButtonX() {
		return left + 8 + PANEL_WIDTH - 16 - RECORD_BUTTON_WIDTH - 6;
	}

	/** Discos Virgens no inventário (no criativo gravar não gasta, então sempre há). */
	private int blankDiscCount() {
		if (client == null || client.player == null) {
			return 0;
		}
		return client.player.getAbilities().creativeMode ? 99
				: client.player.getInventory().count(ModItems.BLANK_DISC);
	}

	private static String formatMs(long ms) {
		long totalSeconds = ms / 1000;
		return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		int listX = left + 8;
		int listY = listTop();
		int listWidth = PANEL_WIDTH - 16;
		if (button == 0 && mouseX >= listX && mouseX < listX + listWidth
				&& mouseY >= listY && mouseY < listY + VISIBLE_ROWS * ROW_HEIGHT) {
			int index = scroll + (int) ((mouseY - listY) / ROW_HEIGHT);
			if (index >= 0 && index < filtered.size()) {
				int rowY = listY + (index - scroll) * ROW_HEIGHT;
				int buttonY = rowY + (ROW_HEIGHT - RECORD_BUTTON_HEIGHT) / 2;
				boolean onRecord = blankDiscCount() > 0
						&& mouseX >= recordButtonX() && mouseX < recordButtonX() + RECORD_BUTTON_WIDTH
						&& mouseY >= buttonY && mouseY < buttonY + RECORD_BUTTON_HEIGHT;
				if (onRecord) {
					sendAction(JukeboxGuiActionPayload.Action.RECORD, filtered.get(index).videoId());
				} else {
					sendAction(JukeboxGuiActionPayload.Action.PLAY, filtered.get(index).videoId());
				}
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int listY = listTop();
		if (mouseY >= listY && mouseY < listY + VISIBLE_ROWS * ROW_HEIGHT) {
			scroll = Math.clamp(scroll - (int) Math.signum(verticalAmount),
					0, Math.max(0, filtered.size() - VISIBLE_ROWS));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
