package com.pvparena.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * Downloads and loads sqlite-jdbc at runtime to keep the plugin jar slim.
 */
public final class SQLiteDriverLoader {
    private static final String VERSION = "3.45.3.0";
    private static final String DRIVER_CLASS = "org.sqlite.JDBC";
    private static final String JAR_NAME = "sqlite-jdbc-" + VERSION + ".jar";
    private static final String DOWNLOAD_URL = "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/"
        + VERSION + "/" + JAR_NAME;

    private final JavaPlugin plugin;
    private final Logger logger;

    public SQLiteDriverLoader(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void ensureDriver() {
        try {
            Class.forName(DRIVER_CLASS);
            return; // already present
        } catch (ClassNotFoundException ignored) {
            // proceed to load
        }

        Path libsDir = plugin.getDataFolder().toPath().getParent().resolve("lib");
        Path jarPath = libsDir.resolve(JAR_NAME);
        try {
            Files.createDirectories(libsDir);
            if (!Files.exists(jarPath)) {
                download(jarPath);
            }
            addToClasspath(jarPath.toUri().toURL());
            Class.forName(DRIVER_CLASS); // verify load
            logger.info("Loaded sqlite-jdbc from " + jarPath.getFileName());
        } catch (Exception ex) {
            logger.severe("Failed to load sqlite driver: " + ex.getMessage());
        }
    }

    private void download(Path target) throws IOException {
        logger.info("Downloading sqlite-jdbc " + VERSION + " ...");
        HttpURLConnection conn = (HttpURLConnection) new URL(DOWNLOAD_URL).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "PvPArena/1.0");
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void addToClasspath(URL url) throws Exception {
        ClassLoader cl = plugin.getClass().getClassLoader();
        if (cl instanceof URLClassLoader) {
            Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addURL.setAccessible(true);
            addURL.invoke(cl, url);
        } else {
            // Fallback to system loader
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            if (sys instanceof URLClassLoader) {
                Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                addURL.setAccessible(true);
                addURL.invoke(sys, url);
            } else {
                throw new IllegalStateException("Unsupported classloader: " + cl);
            }
        }
    }
}
