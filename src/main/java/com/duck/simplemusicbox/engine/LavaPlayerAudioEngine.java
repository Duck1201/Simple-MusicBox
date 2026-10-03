package com.duck.simplemusicbox.engine;

import com.duck.simplemusicbox.ModConfig;
import com.duck.simplemusicbox.SimpleMusicBox;
import com.duck.simplemusicbox.audio.AudioEngine;
import com.duck.simplemusicbox.audio.DownloadException;
import com.duck.simplemusicbox.audio.DurationMatch;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent;
import com.sedmelluq.discord.lavaplayer.player.event.TrackExceptionEvent;
import com.sedmelluq.discord.lavaplayer.player.event.TrackStuckEvent;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.skeleton.Client;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Implementação do motor de áudio sobre LavaPlayer + youtube-source.
 *
 * IMPORTANTE: esta classe (e todo o pacote .engine) é carregada pelo
 * EngineClassLoader junto com os jars de config/simple_musicbox/libs — nunca
 * referencie-a diretamente fora daqui; use a interface AudioEngine.
 */
public class LavaPlayerAudioEngine implements AudioEngine {
	private static final long FRAME_MS = 20;
	private static final int SAMPLE_RATE = 48000;
	private static final int CHANNELS = 2;
	private static final int SAMPLES_PER_FRAME = 960; // 20 ms a 48 kHz
	/** Identificador do youtube-source (README) -> classe em dev.lavalink.youtube.clients. */
	private static final Map<String, String> CLIENT_CLASSES = Map.of(
			"WEB", "Web", "MWEB", "MWeb", "WEB_EMBEDDED", "WebEmbedded", "WEBEMBEDDED", "WebEmbedded",
			"ANDROID", "Android", "ANDROID_MUSIC", "AndroidMusic", "ANDROID_VR", "AndroidVr",
			"IOS", "Ios", "TV", "Tv", "TVHTML5_SIMPLY", "TvHtml5Simply");

	public LavaPlayerAudioEngine() {
		// Valida cedo que o youtube-source carregado é compatível.
		new YoutubeAudioSourceManager().shutdown();
	}

	@Override
	public Result download(String url, long expectedDurationMs, long maxDurationMs, int opusBitrate,
			Predicate<String> alreadyCached) throws DownloadException {
		AudioPlayerManager manager = new DefaultAudioPlayerManager();
		try {
			boolean reencode = opusBitrate > 0;
			AudioDataFormat format = reencode
					? new Pcm16AudioDataFormat(CHANNELS, SAMPLE_RATE, SAMPLES_PER_FRAME, false)
					: StandardAudioDataFormats.DISCORD_OPUS;
			manager.getConfiguration().setOutputFormat(format);
			Client[] clients = configuredClients();
			manager.registerSourceManager(clients.length == 0
					? new YoutubeAudioSourceManager()
					: new YoutubeAudioSourceManager(true, clients));

			AudioTrack track = resolve(manager, url, expectedDurationMs);
			AudioTrackInfo info = track.getInfo();
			if (info.isStream) {
				throw new DownloadException(DownloadException.Kind.LIVE, info.title);
			}
			if (track.getDuration() > maxDurationMs) {
				throw new DownloadException(DownloadException.Kind.TOO_LONG, info.title);
			}
			if (alreadyCached.test(info.identifier)) {
				return new Result(info.identifier, info.title, track.getDuration(), null);
			}

			List<byte[]> frames = decode(manager, track, maxDurationMs,
					reencode ? new OpusReencoder(opusBitrate) : null);
			return new Result(info.identifier, info.title, frames.size() * FRAME_MS, frames);
		} finally {
			manager.shutdown();
		}
	}

	/** Clients de youtubeClients (config), na ordem; ignora nomes desconhecidos. */
	private static Client[] configuredClients() {
		List<String> ids = ModConfig.get().youtubeClients;
		List<Client> clients = new ArrayList<>();
		for (String id : ids == null ? List.<String>of() : ids) {
			String className = CLIENT_CLASSES.get(id.toUpperCase(Locale.ROOT));
			try {
				clients.add((Client) Class.forName("dev.lavalink.youtube.clients."
						+ (className != null ? className : id)).getDeclaredConstructor().newInstance());
			} catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
				SimpleMusicBox.LOGGER.warn("Unknown YouTube client '{}' in config, skipping", id);
			}
		}
		return clients.toArray(Client[]::new);
	}

	private AudioTrack resolve(AudioPlayerManager manager, String url, long expectedDurationMs)
			throws DownloadException {
		CompletableFuture<AudioTrack> resolved = new CompletableFuture<>();
		manager.loadItem(url, new AudioLoadResultHandler() {
			@Override
			public void trackLoaded(AudioTrack track) {
				resolved.complete(track);
			}

			@Override
			public void playlistLoaded(AudioPlaylist playlist) {
				if (playlist.isSearchResult()) {
					List<AudioTrack> results = playlist.getTracks();
					int best = DurationMatch.bestIndex(
							results.stream().map(AudioTrack::getDuration).toList(), expectedDurationMs);
					if (best < 0) {
						resolved.completeExceptionally(
								new DownloadException(DownloadException.Kind.NO_MATCH, url));
					} else {
						resolved.complete(results.get(best));
					}
					return;
				}
				AudioTrack track = playlist.getSelectedTrack() != null
						? playlist.getSelectedTrack()
						: playlist.getTracks().isEmpty() ? null : playlist.getTracks().getFirst();
				if (track == null) {
					resolved.completeExceptionally(
							new DownloadException(DownloadException.Kind.NOT_FOUND, url));
				} else {
					resolved.complete(track);
				}
			}

			@Override
			public void noMatches() {
				resolved.completeExceptionally(new DownloadException(DownloadException.Kind.NOT_FOUND, url));
			}

			@Override
			public void loadFailed(FriendlyException exception) {
				resolved.completeExceptionally(new DownloadException(
						DownloadException.Kind.FAILED, exception.getMessage(), exception));
			}
		});
		try {
			return resolved.get(60, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw e.getCause() instanceof DownloadException downloadException ? downloadException
					: new DownloadException(DownloadException.Kind.FAILED,
							String.valueOf(e.getCause().getMessage()), e.getCause());
		} catch (InterruptedException | TimeoutException e) {
			throw new DownloadException(DownloadException.Kind.FAILED, "resolve timed out", e);
		}
	}

	private List<byte[]> decode(AudioPlayerManager manager, AudioTrack track, long maxDurationMs,
			OpusReencoder reencoder) throws DownloadException {
		AudioPlayer player = manager.createPlayer();
		try {
			AtomicBoolean ended = new AtomicBoolean(false);
			AtomicReference<Throwable> playbackError = new AtomicReference<>();
			player.addListener(event -> {
				if (event instanceof TrackExceptionEvent exceptionEvent) {
					playbackError.set(exceptionEvent.exception);
					ended.set(true);
				} else if (event instanceof TrackStuckEvent) {
					playbackError.set(new DownloadException(DownloadException.Kind.FAILED, "track stuck"));
					ended.set(true);
				} else if (event instanceof TrackEndEvent) {
					ended.set(true);
				}
			});
			player.playTrack(track);

			List<byte[]> frames = new ArrayList<>();
			long maxFrames = maxDurationMs / FRAME_MS + 50;
			long lastFrameAt = System.currentTimeMillis();
			while (true) {
				AudioFrame frame = null;
				try {
					frame = player.provide(500, TimeUnit.MILLISECONDS);
				} catch (TimeoutException ignored) {
					// sem frame nesta janela; verificado abaixo
				} catch (InterruptedException e) {
					throw new DownloadException(DownloadException.Kind.FAILED, "interrupted", e);
				}
				if (frame != null) {
					frames.add(reencoder != null ? reencoder.reencode(frame.getData()) : frame.getData());
					lastFrameAt = System.currentTimeMillis();
					if (frames.size() > maxFrames) {
						throw new DownloadException(DownloadException.Kind.TOO_LONG, track.getInfo().title);
					}
					continue;
				}
				if (ended.get()) {
					AudioFrame remaining;
					while ((remaining = player.provide()) != null) {
						frames.add(reencoder != null
								? reencoder.reencode(remaining.getData()) : remaining.getData());
					}
					break;
				}
				if (System.currentTimeMillis() - lastFrameAt > 30_000) {
					throw new DownloadException(DownloadException.Kind.FAILED, "download timed out");
				}
			}

			Throwable error = playbackError.get();
			if (error != null) {
				throw error instanceof DownloadException downloadException ? downloadException
						: new DownloadException(DownloadException.Kind.FAILED,
								String.valueOf(error.getMessage()), error);
			}
			if (frames.isEmpty()) {
				throw new DownloadException(DownloadException.Kind.FAILED, "no audio produced");
			}
			return frames;
		} finally {
			player.destroy();
		}
	}

	/**
	 * Recebe frames PCM s16 LE (960 amostras estéreo) e devolve pacotes Opus no
	 * bitrate configurado, via Concentus (Opus puro-Java).
	 */
	private static class OpusReencoder {
		private final OpusEncoder encoder;
		private final short[] pcm = new short[SAMPLES_PER_FRAME * CHANNELS];
		private final byte[] packet = new byte[4096];

		OpusReencoder(int bitrate) {
			try {
				this.encoder = new OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_AUDIO);
			} catch (OpusException e) {
				throw new DownloadException(DownloadException.Kind.FAILED, "opus encoder init failed", e);
			}
			encoder.setBitrate(bitrate);
			encoder.setComplexity(10);
			encoder.setUseVBR(true);
			encoder.setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC);
		}

		byte[] reencode(byte[] frameData) {
			// PCM little-endian intercalado; um frame parcial no fim vira silêncio.
			for (int i = 0; i < pcm.length; i++) {
				int lo = i * 2 + 1 < frameData.length ? frameData[i * 2] & 0xFF : 0;
				int hi = i * 2 + 1 < frameData.length ? frameData[i * 2 + 1] : 0;
				pcm[i] = (short) ((hi << 8) | lo);
			}
			try {
				int length = encoder.encode(pcm, 0, SAMPLES_PER_FRAME, packet, 0, packet.length);
				byte[] result = new byte[length];
				System.arraycopy(packet, 0, result, 0, length);
				return result;
			} catch (OpusException e) {
				throw new DownloadException(DownloadException.Kind.FAILED, "opus encode failed", e);
			}
		}
	}
}
