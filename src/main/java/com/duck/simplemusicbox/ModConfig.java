package com.duck.simplemusicbox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ModConfig instance;

	/** Duração máxima aceita para uma faixa, em segundos. */
	public int maxDurationSeconds = 900;
	/**
	 * (Servidor) Nível de permissão para usar /music: 0 = todos, 2 = só
	 * operadores. Com 2, jogadores baixam músicas só pela GUI da jukebox.
	 * O console sempre pode.
	 */
	public int musicCommandPermission = 0;
	/**
	 * (Servidor) Baixar pela GUI exige um Disco Virgem na jukebox, que é
	 * gravado com a música baixada — cada música nova custa um disco.
	 */
	public boolean requireBlankDiscToDownload = false;
	/** Raio (em blocos) em que os jogadores recebem o áudio da jukebox. */
	public double audibleRadius = 64.0;
	/** Tamanho de cada chunk de áudio enviado pela rede, em bytes. */
	public int networkChunkSize = 60_000;
	/** Quantos chunks enviar por tick para cada jogador. */
	public int chunksPerTick = 8;
	/**
	 * (Cliente) true = música em estéreo com volume caindo pela distância, mas
	 * sem direção esquerda/direita; false = mono posicional como um disco
	 * vanilla (o OpenAL só espacializa fontes mono).
	 */
	public boolean stereo = true;
	/**
	 * Bitrate do re-encode Opus em bits/s (padrão 96 kbps ≈ 0,7 MB/min).
	 * 0 ou negativo desliga o re-encode e usa a saída padrão do LavaPlayer
	 * (~140 kbps). Aplicado apenas a downloads novos.
	 */
	public int opusBitrate = 96_000;
	/**
	 * Tamanho máximo do cache de faixas em MB (servidor e cliente, cada um no
	 * seu diretório). Ao exceder, as faixas tocadas há mais tempo são
	 * removidas. 0 ou negativo = ilimitado.
	 */
	public int maxCacheSizeMb = 512;
	/**
	 * (Servidor) Verifica no início se saiu versão nova do youtube-source (a
	 * parte que quebra quando o YouTube muda) e a baixa automaticamente do
	 * repositório Maven oficial do Lavalink para config/simple_musicbox/libs.
	 */
	public boolean autoUpdateYoutubeSource = true;
	/**
	 * (Servidor) Clients do YouTube tentados em ordem (identificadores do
	 * youtube-source: ANDROID, IOS, ANDROID_VR, WEB, MWEB, TVHTML5_SIMPLY,
	 * WEB_EMBEDDED, ANDROID_MUSIC, TV). Quando o YouTube bloqueia um client,
	 * dá para reordenar aqui sem recompilar. Vazio = padrão do youtube-source.
	 */
	public List<String> youtubeClients = List.of(
			"ANDROID", "IOS", "ANDROID_VR", "TVHTML5_SIMPLY", "WEB", "MWEB", "WEB_EMBEDDED");

	public static ModConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static ModConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("simple_musicbox/config.json");
		try {
			if (Files.exists(path)) {
				return GSON.fromJson(Files.readString(path), ModConfig.class);
			}
			ModConfig config = new ModConfig();
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(config));
			return config;
		} catch (IOException | RuntimeException e) {
			SimpleMusicBox.LOGGER.warn("Could not load config, using defaults", e);
			return new ModConfig();
		}
	}
}
