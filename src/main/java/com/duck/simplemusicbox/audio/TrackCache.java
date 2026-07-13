package com.duck.simplemusicbox.audio;

import com.duck.simplemusicbox.ModConfig;
import com.duck.simplemusicbox.SimpleMusicBox;
import com.duck.simplemusicbox.component.TrackData;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Cache de faixas em disco no formato .smb, usado tanto no servidor
 * (config/simple_musicbox/cache) quanto no cliente (pasta do jogo).
 * Limitado por maxCacheSizeMb com poda LRU (mtime = último uso).
 */
public class TrackCache {
	private final Path directory;

	public TrackCache(Path directory) {
		this.directory = directory;
		try {
			Files.createDirectories(directory);
		} catch (IOException e) {
			SimpleMusicBox.LOGGER.error("Could not create track cache directory {}", directory, e);
		}
	}

	private Path pathFor(String videoId) {
		// IDs do YouTube só contêm [A-Za-z0-9_-]; qualquer outra coisa é recusada
		// para não permitir escapar do diretório de cache.
		if (!videoId.matches("[A-Za-z0-9_-]{1,64}")) {
			throw new IllegalArgumentException("Invalid video id: " + videoId);
		}
		return directory.resolve(videoId + ".smb");
	}

	public boolean has(String videoId) {
		return readHeader(videoId).isPresent();
	}

	public Optional<TrackData> readHeader(String videoId) {
		Path path = pathFor(videoId);
		if (!Files.isRegularFile(path)) {
			return Optional.empty();
		}
		// Valida o arquivo inteiro: um download interrompido não tem o marcador final.
		try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
			if (!SmbFileFormat.isComplete(in)) {
				SimpleMusicBox.LOGGER.warn("Discarding truncated cache file {}", path);
				Files.deleteIfExists(path);
				return Optional.empty();
			}
		} catch (IOException e) {
			return Optional.empty();
		}
		try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
			TrackData track = SmbFileFormat.readHeader(in);
			touch(path);
			return Optional.of(track);
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	public Optional<SmbFileFormat.SmbFile> read(String videoId) {
		Path path = pathFor(videoId);
		if (!Files.isRegularFile(path)) {
			return Optional.empty();
		}
		try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
			SmbFileFormat.SmbFile file = SmbFileFormat.read(in);
			touch(path);
			return Optional.of(file);
		} catch (IOException | RuntimeException e) {
			SimpleMusicBox.LOGGER.warn("Could not read cache file {}", path, e);
			return Optional.empty();
		}
	}

	public Optional<byte[]> readRawBytes(String videoId) {
		Path path = pathFor(videoId);
		if (!Files.isRegularFile(path)) {
			return Optional.empty();
		}
		try {
			byte[] bytes = Files.readAllBytes(path);
			touch(path);
			return Optional.of(bytes);
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	public void write(TrackData track, List<byte[]> frames) throws IOException {
		Path path = pathFor(track.videoId());
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
			SmbFileFormat.write(out, track, frames);
		}
		Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		prune();
	}

	public void writeRawBytes(String videoId, byte[] bytes) throws IOException {
		Path path = pathFor(videoId);
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		Files.write(tmp, bytes);
		Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		prune();
	}

	/**
	 * Todas as faixas do cache, ordenadas por título (a "fila" da jukebox).
	 * Não atualiza o LRU — listar não é tocar.
	 */
	public List<TrackData> listTracks() {
		List<TrackData> tracks = new ArrayList<>();
		try (var stream = Files.newDirectoryStream(directory, "*.smb")) {
			for (Path path : stream) {
				try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
					tracks.add(SmbFileFormat.readHeader(in));
				} catch (IOException ignored) {
					// arquivo corrompido/parcial não entra na fila
				}
			}
		} catch (IOException e) {
			SimpleMusicBox.LOGGER.warn("Could not list track cache", e);
		}
		tracks.sort(Comparator.comparing(track -> track.title().toLowerCase(java.util.Locale.ROOT)));
		return tracks;
	}

	/** Marca a faixa como usada recentemente (a poda LRU usa o mtime). */
	private static void touch(Path path) {
		try {
			Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
		} catch (IOException ignored) {
			// só afeta a ordem da poda
		}
	}

	/**
	 * Poda LRU: se o cache passar de maxCacheSizeMb, remove as faixas cujo
	 * último uso é mais antigo até caber no limite.
	 */
	private synchronized void prune() {
		long maxBytes = ModConfig.get().maxCacheSizeMb * 1024L * 1024L;
		if (maxBytes <= 0) {
			return;
		}

		record Entry(Path path, long size, long modified) {
		}
		List<Entry> entries = new ArrayList<>();
		try (var stream = Files.newDirectoryStream(directory, "*.smb")) {
			for (Path path : stream) {
				entries.add(new Entry(path, Files.size(path), Files.getLastModifiedTime(path).toMillis()));
			}
		} catch (IOException e) {
			SimpleMusicBox.LOGGER.warn("Could not scan cache for pruning", e);
			return;
		}

		long total = entries.stream().mapToLong(Entry::size).sum();
		if (total <= maxBytes) {
			return;
		}
		entries.sort(Comparator.comparingLong(Entry::modified));
		for (Entry entry : entries) {
			if (total <= maxBytes) {
				break;
			}
			try {
				Files.deleteIfExists(entry.path());
				total -= entry.size();
				SimpleMusicBox.LOGGER.info("Pruned cached track {} ({} KB)",
						entry.path().getFileName(), entry.size() / 1024);
			} catch (IOException e) {
				SimpleMusicBox.LOGGER.warn("Could not prune cache file {}", entry.path(), e);
			}
		}
	}
}
