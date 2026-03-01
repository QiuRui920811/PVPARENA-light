package com.pvparena.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class YamlFile {
    protected final JavaPlugin plugin;
    private final String resourcePath;
    private final File file;
    private FileConfiguration config;

    public YamlFile(JavaPlugin plugin, String fileName) {
        this.plugin = plugin;
        this.resourcePath = fileName;
        this.file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            try {
                plugin.saveResource(resourcePath, false);
            } catch (IllegalArgumentException ex) {
                try {
                    file.getParentFile().mkdirs();
                    file.createNewFile();
                } catch (IOException ioe) {
                    plugin.getLogger().severe("Failed to create " + fileName + ": " + ioe.getMessage());
                }
            }
        }
        reload();
    }

    public void reload() {
        if (!file.exists()) {
            try {
                plugin.saveResource(resourcePath, false);
            } catch (IllegalArgumentException ex) {
                try {
                    file.getParentFile().mkdirs();
                    file.createNewFile();
                } catch (IOException ioe) {
                    plugin.getLogger().severe("Failed to create " + file.getName() + ": " + ioe.getMessage());
                }
            }
        }
        this.config = YamlConfiguration.loadConfiguration(file);
        if (shouldApplyDefaults()) {
            applyDefaultsFromResource();
        }
    }

    protected void applyDefaultsFromResource() {
        try (InputStream stream = plugin.getResource(resourcePath)) {
            if (stream == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            save();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to apply defaults for " + resourcePath + ": " + ex.getMessage());
        }
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save " + file.getName() + ": " + e.getMessage());
        }
    }

    public File getFile() {
        return file;
    }

    protected JavaPlugin getPlugin() {
        return plugin;
    }

    protected boolean shouldApplyDefaults() {
        return true;
    }
}
