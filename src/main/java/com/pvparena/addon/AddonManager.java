package com.pvparena.addon;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class AddonManager {
    private final JavaPlugin plugin;
    private final File addonsFolder;
    private final Map<String, Plugin> loadedAddonPlugins = new LinkedHashMap<>();
    private volatile LeaderboardAddon leaderboardAddon;
    private volatile TeamAddon teamAddon;

    public AddonManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.addonsFolder = new File(plugin.getDataFolder(), "addons");
    }

    public void initialize() {
        ensureDirectory(addonsFolder, "addons");
        cleanupLegacySubfolders();
    }

    public void shutdown() {
        unloadDiscoveredPlugins();
        leaderboardAddon = null;
        teamAddon = null;
    }

    public void loadDiscoveredPlugins() {
        ensureDirectory(addonsFolder, "addons");
        File[] jars = addonsFolder.listFiles(file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            plugin.getLogger().info("[Addons] No addon jars found in " + addonsFolder.getAbsolutePath());
            return;
        }
        plugin.getLogger().info("[Addons] Scanning " + jars.length + " addon jar(s) from " + addonsFolder.getAbsolutePath());

        List<File> sorted = new ArrayList<>();
        for (File jar : jars) {
            sorted.add(jar);
        }
        sorted.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        PluginManager pluginManager = Bukkit.getPluginManager();
        int loadedCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        for (File jar : sorted) {
            String declaredName = readDeclaredPluginName(jar);
            if (declaredName != null && !declaredName.isBlank()) {
                Plugin existing = pluginManager.getPlugin(declaredName);
                if (existing != null) {
                    String key = existing.getName().toLowerCase(Locale.ROOT);
                    Plugin tracked = loadedAddonPlugins.get(key);
                    if (tracked == existing) {
                        if (!existing.isEnabled()) {
                            pluginManager.enablePlugin(existing);
                        }
                        loadedAddonPlugins.put(key, existing);
                        plugin.getLogger().info("Addon already loaded, skipping duplicate jar: " + existing.getName() + " (" + jar.getName() + ")");
                        skippedCount++;
                        continue;
                    }

                    plugin.getLogger().warning("[Addons] Found pre-registered addon plugin not managed by current core instance: " + existing.getName() + ". Trying to unload stale instance before loading from jar.");
                    if (!forceUnloadByName(existing.getName())) {
                        plugin.getLogger().warning("[Addons] Could not unload stale addon plugin '" + existing.getName() + "'. Skipping jar: " + jar.getName());
                        failedCount++;
                        continue;
                    }
                }
            }

            try {
                Plugin loaded = pluginManager.loadPlugin(jar);
                if (loaded == null) {
                    plugin.getLogger().warning("Skipped addon jar (plugin loader returned null): " + jar.getName());
                    skippedCount++;
                    continue;
                }
                if (loaded == plugin) {
                    plugin.getLogger().warning("Skipped self plugin in addons folder: " + jar.getName());
                    skippedCount++;
                    continue;
                }
                pluginManager.enablePlugin(loaded);
                loadedAddonPlugins.put(loaded.getName().toLowerCase(Locale.ROOT), loaded);
                plugin.getLogger().info("Loaded addon plugin: " + loaded.getName() + " from addons/" + jar.getName());
                loadedCount++;
            } catch (InvalidPluginException | InvalidDescriptionException ex) {
                plugin.getLogger().warning("Failed to load addon jar '" + jar.getName() + "': " + ex.getMessage());
                failedCount++;
            } catch (Throwable ex) {
                plugin.getLogger().warning("Unexpected error while loading addon jar '" + jar.getName() + "': " + ex.getMessage());
                failedCount++;
            }
        }
        plugin.getLogger().info("[Addons] Scan complete. loaded=" + loadedCount + ", skipped=" + skippedCount + ", failed=" + failedCount);
    }

    public void reloadDiscoveredPlugins() {
        loadDiscoveredPlugins();
        triggerRankAddonReload();
    }

    public void unloadDiscoveredPlugins() {
        if (loadedAddonPlugins.isEmpty()) {
            return;
        }
        PluginManager pluginManager = Bukkit.getPluginManager();
        List<Plugin> toDisable = new ArrayList<>(loadedAddonPlugins.values());
        for (int i = toDisable.size() - 1; i >= 0; i--) {
            Plugin addonPlugin = toDisable.get(i);
            try {
                if (addonPlugin != null) {
                    if (addonPlugin.isEnabled()) {
                        pluginManager.disablePlugin(addonPlugin);
                    }
                    forceUnloadByName(addonPlugin.getName());
                }
            } catch (Throwable ex) {
                plugin.getLogger().warning("Failed to disable addon plugin '" + (addonPlugin == null ? "unknown" : addonPlugin.getName()) + "': " + ex.getMessage());
            }
        }
        loadedAddonPlugins.clear();
    }

    public File getAddonsFolder() {
        return addonsFolder;
    }

    public void registerLeaderboardAddon(LeaderboardAddon addon) {
        leaderboardAddon = Objects.requireNonNull(addon, "addon");
        plugin.getLogger().info("Registered leaderboard addon provider: " + addon.getProviderId());
    }

    public void unregisterLeaderboardAddon() {
        leaderboardAddon = null;
    }

    public Optional<LeaderboardAddon> getLeaderboardAddon() {
        return Optional.ofNullable(leaderboardAddon);
    }

    public void registerTeamAddon(TeamAddon addon) {
        teamAddon = Objects.requireNonNull(addon, "addon");
        plugin.getLogger().info("Registered team addon provider: " + addon.getProviderId());
    }

    public void unregisterTeamAddon() {
        teamAddon = null;
    }

    public Optional<TeamAddon> getTeamAddon() {
        return Optional.ofNullable(teamAddon);
    }

    private void ensureDirectory(File dir, String name) {
        if (dir.exists()) {
            return;
        }
        if (dir.mkdirs()) {
            plugin.getLogger().info("Created " + name + " directory.");
        } else {
            plugin.getLogger().warning("Failed to create " + name + " directory: " + dir.getAbsolutePath());
        }
    }

    private void cleanupLegacySubfolders() {
        deleteLegacyFolder("leaderboard");
        deleteLegacyFolder("team");
    }

    private void deleteLegacyFolder(String name) {
        File legacy = new File(addonsFolder, name);
        if (!legacy.exists() || !legacy.isDirectory()) {
            return;
        }
        try {
            List<Path> paths = Files.walk(legacy.toPath())
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
            plugin.getLogger().info("Removed legacy addons/" + name + " directory.");
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to remove legacy addons/" + name + " directory: " + ex.getMessage());
        }
    }

    private String readDeclaredPluginName(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry pluginYml = jar.getJarEntry("plugin.yml");
            if (pluginYml == null) {
                return null;
            }
            try (InputStream in = jar.getInputStream(pluginYml)) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in));
                return yaml.getString("name");
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private void triggerRankAddonReload() {
        Plugin rank = Bukkit.getPluginManager().getPlugin("PvPArenaRank");
        if (rank == null || !rank.isEnabled()) {
            return;
        }
        if (Bukkit.getPluginCommand("rank") == null) {
            plugin.getLogger().warning("[Addons] PvPArenaRank is enabled but /rank command is unavailable; skip auto reload.");
            return;
        }

        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            ConsoleCommandSender console = Bukkit.getConsoleSender();
            try {
                boolean ok = Bukkit.dispatchCommand(console, "rank reload");
                if (ok) {
                    plugin.getLogger().info("[Addons] Triggered PvPArenaRank reload via /rank reload.");
                } else {
                    plugin.getLogger().warning("[Addons] Failed to trigger PvPArenaRank reload via /rank reload.");
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("[Addons] Rank auto-reload failed: " + ex.getMessage());
            }
        });
    }

    private boolean forceUnloadByName(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            return false;
        }
        PluginManager pluginManager = Bukkit.getPluginManager();
        Plugin existing = pluginManager.getPlugin(pluginName);
        if (existing == null) {
            return true;
        }
        try {
            if (existing.isEnabled()) {
                pluginManager.disablePlugin(existing);
            }
        } catch (Throwable ex) {
            plugin.getLogger().warning("[Addons] Failed disabling plugin before unload '" + pluginName + "': " + ex.getMessage());
        }

        Plugin afterDisable = pluginManager.getPlugin(pluginName);
        if (afterDisable == null) {
            return true;
        }

        if (Bukkit.getPluginCommand("plugman") == null) {
            return false;
        }

        try {
            ConsoleCommandSender console = Bukkit.getConsoleSender();
            Bukkit.dispatchCommand(console, "plugman unload " + pluginName);
        } catch (Throwable ex) {
            plugin.getLogger().warning("[Addons] Failed to execute PlugMan unload for '" + pluginName + "': " + ex.getMessage());
            return false;
        }

        return pluginManager.getPlugin(pluginName) == null;
    }
}
