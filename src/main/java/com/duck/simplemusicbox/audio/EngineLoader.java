package com.duck.simplemusicbox.audio;

import com.duck.simplemusicbox.ModConfig;
import com.duck.simplemusicbox.SimpleMusicBox;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Mantém config/simple_musicbox/libs: extrai os jars-semente embutidos no mod
 * (engine-libs/), verifica no repositório Maven do Lavalink se existe
 * youtube-source mais novo (a parte que quebra quando o YouTube muda), baixa
 * as atualizações e monta o EngineClassLoader — com fallback para a versão
 * embutida se a nova falhar ao carregar.
 */
public class EngineLoader {
	private static final String LAVALINK_REPO = "https://maven.lavalink.dev/releases/";
	private static final String CENTRAL_REPO = "https://repo1.maven.org/maven2/";
	private static final String YT_GROUP_PATH = "dev/lavalink/youtube/";
	private static final Pattern JAR_NAME = Pattern.compile("^(.*?)-([0-9][A-Za-z0-9._\\-]*)\\.jar$");
	/** Artefatos do youtube-source que devem casar de versão entre si. */
	private static final Set<String> YT_ARTIFACTS = Set.of("v2", "common");

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private static final Object LOCK = new Object();
	private static boolean prepared;
	private static AudioEngine engine;
	private static String loadedVersion;
	private static volatile String opsNotice;

	private record JarFile(Path path, String base, String version) {
	}

	/** Mensagem para avisar admins no jogo, ou null se está tudo bem. */
	public static String opsNotice() {
		return opsNotice;
	}

	public static String loadedVersion() {
		return loadedVersion;
	}

	/** Prepara as libs (seed + auto-update). Idempotente; nunca lança. */
	public static void prepareLibs() {
		synchronized (LOCK) {
			if (prepared) {
				return;
			}
			prepared = true;
			try {
				seedFromModJar();
			} catch (IOException e) {
				SimpleMusicBox.LOGGER.error("Could not extract engine libs from mod jar", e);
			}
			if (ModConfig.get().autoUpdateYoutubeSource) {
				try {
					checkForUpdate();
				} catch (Exception e) {
					SimpleMusicBox.LOGGER.warn("youtube-source update check failed ({}); usando versão local",
							String.valueOf(e.getMessage()));
				}
			}
		}
	}

	/** Constrói (uma vez) o motor de áudio. Lança DownloadException se impossível. */
	public static AudioEngine getEngine() {
		synchronized (LOCK) {
			if (engine != null) {
				return engine;
			}
			prepareLibs();

			Map<String, List<JarFile>> byBase = scanLibs();
			List<JarFile> candidates = byBase.getOrDefault("v2", List.of()).stream()
					.sorted(Comparator.comparing((JarFile jar) -> versionKey(jar.version())).reversed())
					.toList();
			if (candidates.isEmpty()) {
				opsNotice = "Simple MusicBox: bibliotecas de áudio ausentes em config/simple_musicbox/libs";
				throw new DownloadException(DownloadException.Kind.FAILED,
						"engine libs missing (config/simple_musicbox/libs)");
			}

			Throwable lastError = null;
			for (JarFile candidate : candidates) {
				try {
					List<Path> jars = selectJars(byBase, candidate.version());
					EngineClassLoader loader = new EngineClassLoader(jars, EngineLoader.class.getClassLoader());
					Class<?> impl = Class.forName(
							"com.duck.simplemusicbox.engine.LavaPlayerAudioEngine", true, loader);
					engine = (AudioEngine) impl.getDeclaredConstructor().newInstance();
					loadedVersion = candidate.version();
					SimpleMusicBox.LOGGER.info("Audio engine loaded (youtube-source {})", loadedVersion);
					if (!candidate.equals(candidates.getFirst())) {
						opsNotice = "Simple MusicBox: youtube-source " + candidates.getFirst().version()
								+ " falhou ao carregar; usando " + loadedVersion;
					}
					return engine;
				} catch (Throwable e) {
					lastError = e;
					SimpleMusicBox.LOGGER.error("youtube-source {} failed to load, trying older",
							candidate.version(), e);
				}
			}
			opsNotice = "Simple MusicBox: o motor de áudio não carregou; veja o log do servidor";
			throw new DownloadException(DownloadException.Kind.FAILED, "audio engine failed to load", lastError);
		}
	}

	private static Path libsDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("simple_musicbox/libs");
	}

	private static void seedFromModJar() throws IOException {
		Path dir = libsDir();
		Files.createDirectories(dir);
		ModContainer mod = FabricLoader.getInstance().getModContainer(SimpleMusicBox.MOD_ID).orElseThrow();
		Optional<Path> seeds = mod.findPath("engine-libs");
		if (seeds.isEmpty()) {
			SimpleMusicBox.LOGGER.warn("Mod jar has no embedded engine-libs");
			return;
		}
		try (var stream = Files.list(seeds.get())) {
			// Os seeds usam extensão .jarx para o empacotamento não os explodir.
			for (Path seed : stream.filter(p -> p.getFileName().toString().endsWith(".jarx")).toList()) {
				String name = seed.getFileName().toString();
				Path target = dir.resolve(name.substring(0, name.length() - 1)); // .jarx -> .jar
				if (!Files.exists(target)) {
					Files.copy(seed, target, StandardCopyOption.REPLACE_EXISTING);
					SimpleMusicBox.LOGGER.info("Seeded engine lib {}", target.getFileName());
				}
			}
		}
	}

	private static Map<String, List<JarFile>> scanLibs() {
		Map<String, List<JarFile>> byBase = new HashMap<>();
		try (var stream = Files.list(libsDir())) {
			for (Path path : stream.toList()) {
				Matcher matcher = JAR_NAME.matcher(path.getFileName().toString());
				if (matcher.matches()) {
					byBase.computeIfAbsent(matcher.group(1), base -> new ArrayList<>())
							.add(new JarFile(path, matcher.group(1), matcher.group(2)));
				}
			}
		} catch (IOException e) {
			SimpleMusicBox.LOGGER.error("Could not scan engine libs", e);
		}
		return byBase;
	}

	/** Uma versão de cada lib: a mais nova, exceto os artefatos do youtube-source, que casam com o candidato. */
	private static List<Path> selectJars(Map<String, List<JarFile>> byBase, String youtubeVersion) {
		List<Path> jars = new ArrayList<>();
		for (Map.Entry<String, List<JarFile>> entry : byBase.entrySet()) {
			List<JarFile> versions = entry.getValue();
			JarFile chosen;
			if (YT_ARTIFACTS.contains(entry.getKey())) {
				chosen = versions.stream()
						.filter(jar -> jar.version().equals(youtubeVersion))
						.findFirst()
						.orElseGet(() -> newest(versions));
			} else {
				chosen = newest(versions);
			}
			jars.add(chosen.path());
		}
		return jars;
	}

	private static JarFile newest(List<JarFile> versions) {
		return versions.stream()
				.max(Comparator.comparing(jar -> versionKey(jar.version())))
				.orElseThrow();
	}

	/** Chave comparável de versão: segmentos numéricos com padding. */
	private static String versionKey(String version) {
		StringBuilder key = new StringBuilder();
		for (String part : version.split("[^0-9]+")) {
			if (!part.isEmpty()) {
				key.append(String.format("%08d", Long.parseLong(
						part.length() > 8 ? part.substring(0, 8) : part)));
			}
		}
		return key.toString();
	}

	private static void checkForUpdate() throws Exception {
		String metadata = fetchText(LAVALINK_REPO + YT_GROUP_PATH + "v2/maven-metadata.xml");
		Matcher release = Pattern.compile("<release>([^<]+)</release>").matcher(metadata);
		if (!release.find()) {
			return;
		}
		String latest = release.group(1);
		String current = scanLibs().getOrDefault("v2", List.of()).stream()
				.map(JarFile::version)
				.max(Comparator.comparing(EngineLoader::versionKey))
				.orElse("0");
		if (versionKey(latest).compareTo(versionKey(current)) <= 0) {
			SimpleMusicBox.LOGGER.info("youtube-source {} é a versão mais recente", current);
			return;
		}
		SimpleMusicBox.LOGGER.info("Baixando youtube-source {} (local: {})...", latest, current);
		Set<String> visited = new HashSet<>();
		downloadArtifact("dev.lavalink.youtube", "v2", latest, visited, 0);
		SimpleMusicBox.LOGGER.info("youtube-source {} baixado; será usado no próximo carregamento do motor", latest);
	}

	private static void downloadArtifact(String group, String artifact, String version,
			Set<String> visited, int depth) throws Exception {
		if (depth > 3 || !visited.add(group + ":" + artifact)) {
			return;
		}
		Map<String, List<JarFile>> local = scanLibs();
		boolean isYt = YT_ARTIFACTS.contains(artifact);
		boolean alreadyHaveBase = local.containsKey(artifact);
		boolean alreadyHaveVersion = local.getOrDefault(artifact, List.of()).stream()
				.anyMatch(jar -> jar.version().equals(version));
		// Libs comuns: qualquer versão presente basta; artefatos do
		// youtube-source precisam da versão exata do candidato.
		if (alreadyHaveVersion || (alreadyHaveBase && !isYt)) {
			return;
		}

		String repo = group.startsWith("dev.lavalink") || group.startsWith("dev.arbjerg")
				? LAVALINK_REPO : CENTRAL_REPO;
		String basePath = repo + group.replace('.', '/') + "/" + artifact + "/" + version + "/"
				+ artifact + "-" + version;

		byte[] jar = fetchBytes(basePath + ".jar");
		String expectedSha1 = fetchText(basePath + ".jar.sha1").trim().split("\\s+")[0];
		String actualSha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(jar));
		if (!expectedSha1.equalsIgnoreCase(actualSha1)) {
			throw new IOException("SHA-1 mismatch for " + artifact + "-" + version + ".jar");
		}
		Path target = libsDir().resolve(artifact + "-" + version + ".jar");
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(tmp, jar);
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		SimpleMusicBox.LOGGER.info("Downloaded engine lib {}-{}.jar", artifact, version);

		// Dependências declaradas no POM (só as de runtime, não opcionais)
		for (String[] dep : parsePomDependencies(fetchText(basePath + ".pom"))) {
			String depGroup = dep[0];
			String depArtifact = dep[1];
			String depVersion = dep[2];
			if (depGroup.equals("org.slf4j") || depVersion == null || depVersion.contains("${")) {
				continue;
			}
			downloadArtifact(depGroup, depArtifact, depVersion, visited, depth + 1);
		}
	}

	/** Retorna [groupId, artifactId, version] das dependências relevantes do POM. */
	private static List<String[]> parsePomDependencies(String pomXml) throws Exception {
		List<String[]> result = new ArrayList<>();
		Document doc = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder()
				.parse(new java.io.ByteArrayInputStream(pomXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		NodeList deps = doc.getElementsByTagName("dependency");
		for (int i = 0; i < deps.getLength(); i++) {
			Element dep = (Element) deps.item(i);
			// ignora <dependencyManagement>
			if (!dep.getParentNode().getParentNode().getNodeName().equals("project")) {
				continue;
			}
			String scope = childText(dep, "scope");
			String optional = childText(dep, "optional");
			if ((scope == null || scope.equals("compile") || scope.equals("runtime"))
					&& !"true".equals(optional)) {
				result.add(new String[]{
						childText(dep, "groupId"), childText(dep, "artifactId"), childText(dep, "version")});
			}
		}
		return result;
	}

	private static String childText(Element parent, String tag) {
		NodeList children = parent.getElementsByTagName(tag);
		return children.getLength() == 0 ? null : children.item(0).getTextContent().trim();
	}

	private static String fetchText(String url) throws IOException, InterruptedException {
		return new String(fetchBytes(url), java.nio.charset.StandardCharsets.UTF_8);
	}

	private static byte[] fetchBytes(String url) throws IOException, InterruptedException {
		HttpResponse<byte[]> response = HTTP.send(
				HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " for " + url);
		}
		return response.body();
	}
}
