package com.duck.simplemusicbox.audio;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * Classloader do motor de áudio. Regras:
 * - classes do pacote com.duck.simplemusicbox.engine: bytes vêm do jar do mod,
 *   mas são DEFINIDAS aqui, para linkarem contra as libs deste loader;
 * - libs (lavaplayer, youtube-source, jackson, httpclient, ...): child-first
 *   a partir dos jars de config/simple_musicbox/libs;
 * - resto (JDK, slf4j, classes do mod como AudioEngine/DownloadException):
 *   delega ao classloader do mod, para os tipos da fronteira serem os mesmos.
 */
public class EngineClassLoader extends URLClassLoader {
	private static final String ENGINE_PACKAGE = "com.duck.simplemusicbox.engine.";

	private final ClassLoader modLoader;

	public EngineClassLoader(List<Path> jars, ClassLoader modLoader) {
		super("SimpleMusicBox-Engine", jars.stream().map(EngineClassLoader::toUrl).toArray(URL[]::new),
				modLoader);
		this.modLoader = modLoader;
	}

	private static URL toUrl(Path path) {
		try {
			return path.toUri().toURL();
		} catch (IOException e) {
			throw new IllegalArgumentException("Bad engine lib path: " + path, e);
		}
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		synchronized (getClassLoadingLock(name)) {
			Class<?> loaded = findLoadedClass(name);
			if (loaded == null) {
				if (name.startsWith(ENGINE_PACKAGE)) {
					loaded = defineEngineClass(name);
				} else if (!name.startsWith("java.") && !name.startsWith("javax.")
						&& !name.startsWith("org.slf4j.")
						&& !name.startsWith("com.duck.simplemusicbox.")) {
					try {
						loaded = findClass(name);
					} catch (ClassNotFoundException ignored) {
						// não está nos jars do motor; cai para o loader do mod
					}
				}
				if (loaded == null) {
					loaded = modLoader.loadClass(name);
				}
			}
			if (resolve) {
				resolveClass(loaded);
			}
			return loaded;
		}
	}

	private Class<?> defineEngineClass(String name) throws ClassNotFoundException {
		String resource = name.replace('.', '/') + ".class";
		try (InputStream in = modLoader.getResourceAsStream(resource)) {
			if (in == null) {
				throw new ClassNotFoundException(name);
			}
			byte[] bytes = in.readAllBytes();
			return defineClass(name, bytes, 0, bytes.length);
		} catch (IOException e) {
			throw new ClassNotFoundException(name, e);
		}
	}
}
