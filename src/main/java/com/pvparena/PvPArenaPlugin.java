package com.pvparena;

import com.pvparena.command.ArenaCommand;
import com.pvparena.command.ArenaTabCompleter;
import com.pvparena.command.DuelCommand;
import com.pvparena.command.DuelTabCompleter;
import com.pvparena.command.PvPArenaCommand;
import com.pvparena.command.PvPArenaTabCompleter;
import com.pvparena.command.PkCommand;
import com.pvparena.command.PvpCommand;
import com.pvparena.command.PvpTabCompleter;
import com.pvparena.addon.AddonManager;
import com.pvparena.config.ArenasConfig;
import com.pvparena.config.KitsConfig;
import com.pvparena.config.MessagesConfig;
import com.pvparena.config.ModesConfig;
import com.pvparena.config.PluginSettings;
import com.pvparena.gui.DuelMenu;
import com.pvparena.gui.KitEditorMenu;
import com.pvparena.gui.MainMenu;
import com.pvparena.gui.ResultMenu;
import com.pvparena.gui.SpectatorAdminMenu;
import com.pvparena.gui.SpectatorBrowseMenu;
import com.pvparena.hook.HuskSyncHook;
import com.pvparena.hook.placeholder.PvPArenaPlaceholderExpansion;
import com.pvparena.listener.DuelMenuListener;
import com.pvparena.listener.GuiListener;
import com.pvparena.listener.KitEditorListener;
import com.pvparena.listener.MatchListener;
import com.pvparena.listener.WorldLoadListener;
import com.pvparena.listener.PlayerListener;
import com.pvparena.listener.PvpFlowIsolationListener;
import com.pvparena.listener.ResultMenuListener;
import com.pvparena.listener.SelectionListener;
import com.pvparena.listener.SpectatorAdminMenuListener;
import com.pvparena.listener.SpectatorBrowseMenuListener;
import com.pvparena.listener.SpectatorListener;
import com.pvparena.manager.ArenaManager;
import com.pvparena.manager.BigDoorManager;
import com.pvparena.manager.DuelManager;
import com.pvparena.manager.KitManager;
import com.pvparena.manager.MessageManager;
import com.pvparena.manager.MatchCrashRecoveryManager;
import com.pvparena.manager.MatchManager;
import com.pvparena.manager.ModeManager;
import com.pvparena.manager.PendingRestoreManager;
import com.pvparena.manager.PkManager;
import com.pvparena.manager.QueueManager;
import com.pvparena.manager.SelectionManager;
import com.pvparena.manager.SpectatorManager;
import com.pvparena.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class PvPArenaPlugin extends JavaPlugin {
    private ModesConfig modesConfig;
    private ArenasConfig arenasConfig;
    private KitsConfig kitsConfig;
    private MessagesConfig messagesConfig;
    private PluginSettings settings;

    private MessageManager messageManager;
    private KitManager kitManager;
    private ModeManager modeManager;
    private ArenaManager arenaManager;
    private QueueManager queueManager;
    private MatchManager matchManager;
    private MainMenu mainMenu;
    private ResultMenu resultMenu;
    private DuelMenu duelMenu;
    private KitEditorMenu kitEditorMenu;
    private SpectatorAdminMenu spectatorAdminMenu;
    private SpectatorBrowseMenu spectatorBrowseMenu;
    private SelectionManager selectionManager;
    private BigDoorManager bigDoorManager;
    private PendingRestoreManager pendingRestoreManager;
    private MatchCrashRecoveryManager matchCrashRecoveryManager;
    private DuelManager duelManager;
    private PkManager pkManager;
    private boolean protocolLibPresent;
    private ScheduledTask pvWorldRulesTask;
    private ScheduledTask menuRefreshTask;
    private SpectatorManager spectatorManager;
    private AddonManager addonManager;
    private HuskSyncHook huskSyncHook;
    private Object huskSyncApiHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mergeConfigDefaults();
        this.addonManager = new AddonManager(this);
        this.addonManager.initialize();
        this.settings = new PluginSettings(getConfig());
        this.modesConfig = new ModesConfig(this, "modes.yml");
        this.arenasConfig = new ArenasConfig(this, "arenas.yml");
        this.kitsConfig = new KitsConfig(this, "kits.yml");
        this.messagesConfig = new MessagesConfig(this, selectMessagesFile());

        this.messageManager = new MessageManager(messagesConfig);
        MessageUtil.init(messageManager);
        this.kitManager = new KitManager(kitsConfig);
        this.modeManager = new ModeManager(modesConfig, kitManager);
        this.arenaManager = new ArenaManager(arenasConfig);
        this.bigDoorManager = new BigDoorManager(this);
        this.pendingRestoreManager = new PendingRestoreManager(this);
        this.matchCrashRecoveryManager = new MatchCrashRecoveryManager(this);
        this.queueManager = new QueueManager(this, arenaManager, modeManager, settings);
        this.pkManager = new PkManager();
        this.protocolLibPresent = false;
        this.matchManager = new MatchManager(this, arenaManager, queueManager, modeManager, pendingRestoreManager, pkManager, matchCrashRecoveryManager);
        this.spectatorManager = new SpectatorManager(this, matchManager, settings);
        this.matchManager.setSpectatorManager(spectatorManager);
        this.mainMenu = new MainMenu(this, messageManager, modeManager, queueManager, settings);
        this.resultMenu = new ResultMenu(messageManager, matchManager);
        this.duelMenu = new DuelMenu(messageManager, modeManager, arenaManager, settings);
        this.kitEditorMenu = new KitEditorMenu(this, kitManager);
        this.spectatorAdminMenu = new SpectatorAdminMenu(this);
        this.spectatorBrowseMenu = new SpectatorBrowseMenu(this, matchManager);
        this.spectatorManager.setBrowserOpener(player -> spectatorBrowseMenu.open(player, 0));
        this.selectionManager = new SelectionManager();
        this.duelManager = new DuelManager(this, arenaManager, matchManager, modeManager, queueManager, settings);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new PvPArenaPlaceholderExpansion(this, matchManager).register();
                getLogger().info("PlaceholderAPI expansion registered: %pvparena_*");
            } catch (Throwable ex) {
                getLogger().warning("Failed to register PlaceholderAPI expansion: " + ex.getMessage());
            }
        }

        if (getServer().getPluginManager().getPlugin("HuskSync") != null) {
            boolean apiHooked = tryHookHuskSyncApi(matchManager);
            if (!apiHooked) {
                try {
                    this.huskSyncHook = new HuskSyncHook(this, matchManager);
                } catch (Throwable ex) {
                    getLogger().warning("Failed to hook HuskSync events; continuing without hook: " + ex.getMessage());
                    this.huskSyncHook = null;
                }
            }
        }

        getServer().getPluginManager().registerEvents(new GuiListener(this, mainMenu, queueManager, modeManager), this);
        getServer().getPluginManager().registerEvents(new KitEditorListener(kitManager, modeManager), this);
        getServer().getPluginManager().registerEvents(new MatchListener(matchManager, pkManager, settings), this);
        getServer().getPluginManager().registerEvents(new PvpFlowIsolationListener(), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this, queueManager, matchManager, pkManager, settings), this);
        getServer().getPluginManager().registerEvents(new ResultMenuListener(resultMenu), this);
        getServer().getPluginManager().registerEvents(new DuelMenuListener(duelMenu, duelManager), this);
        getServer().getPluginManager().registerEvents(new SelectionListener(this, selectionManager, settings), this);
        getServer().getPluginManager().registerEvents(new SpectatorAdminMenuListener(this, spectatorAdminMenu), this);
        getServer().getPluginManager().registerEvents(new SpectatorBrowseMenuListener(spectatorBrowseMenu, spectatorManager), this);
        getServer().getPluginManager().registerEvents(new SpectatorListener(spectatorManager), this);
        getServer().getPluginManager().registerEvents(new WorldLoadListener(this), this);

        matchManager.bootstrapCrashRecovery();
        scheduleRollbackBaselineWarmup(5L);
        scheduleArenaChunkWarmup(10L);

        getCommand("pvp").setExecutor(new PvpCommand(mainMenu, matchManager, spectatorManager, spectatorBrowseMenu));
        getCommand("pvp").setTabCompleter(new PvpTabCompleter(matchManager));
        getCommand("duel").setExecutor(new DuelCommand(mainMenu, resultMenu, duelManager, matchManager, duelMenu));
        getCommand("duel").setTabCompleter(new DuelTabCompleter());
        getCommand("pk").setExecutor(new PkCommand(pkManager, settings));
        getCommand("arena").setExecutor(new ArenaCommand(arenaManager, selectionManager));
        getCommand("arena").setTabCompleter(new ArenaTabCompleter(arenaManager));
        getCommand("pvparena").setExecutor(new PvPArenaCommand(this, kitManager, modeManager, arenaManager, selectionManager, kitEditorMenu, spectatorAdminMenu, settings));
        getCommand("pvparena").setTabCompleter(new PvPArenaTabCompleter(this));

        setupPvWorld();

        // Worlds managed by external plugins (e.g. Multiverse) may load after this plugin.
        // If arena locations couldn't deserialize due to unloaded worlds, reload arenas once shortly after startup.
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            if (arenaManager != null) {
                arenaManager.load();
            }
            setupPvWorld();
            scheduleRollbackBaselineWarmup(20L);
            scheduleArenaChunkWarmup(30L);
        }, 40L);

        menuRefreshTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            if (mainMenu != null) {
                mainMenu.refreshOpenMenus();
            }
        }, 20L, 20L);

        if (addonManager != null) {
            Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> addonManager.loadDiscoveredPlugins(), 1L);
        }

        logStartupInfo();
    }

    private void scheduleRollbackBaselineWarmup(long delayTicks) {
        if (matchManager == null || arenaManager == null) {
            return;
        }
        long delay = Math.max(1L, delayTicks);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            try {
                matchManager.getRollbackService().warmupArenaBaselinesAsync(arenaManager.getArenas());
            } catch (Throwable ex) {
                getLogger().warning("Failed to schedule rollback baseline warmup: " + ex.getMessage());
            }
        }, delay);
    }

    private void scheduleArenaChunkWarmup(long delayTicks) {
        if (matchManager == null || arenaManager == null) {
            return;
        }
        if (!getConfig().getBoolean("match.preheat-arena-chunks-on-first-start", true)) {
            return;
        }
        long delay = Math.max(1L, delayTicks);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            try {
                matchManager.warmupArenaChunksAsync(arenaManager.getArenas());
            } catch (Throwable ex) {
                getLogger().warning("Failed to schedule arena chunk warmup: " + ex.getMessage());
            }
        }, delay);
    }

    private boolean tryHookHuskSyncApi(MatchManager matchManager) {
        try {
            Class<?> hookClass = Class.forName("com.pvparena.hook.husksync.HuskSyncApiHook");
            Object hook = hookClass.getConstructor(JavaPlugin.class, MatchManager.class)
                    .newInstance(this, matchManager);
            this.huskSyncApiHook = hook;
            getLogger().info("HuskSync API hook enabled.");
            return true;
        } catch (Throwable t) {
            getLogger().log(java.util.logging.Level.FINE, "HuskSync API hook not available; using fallback hook", t);
            return false;
        }
    }

    @Override
    public void onDisable() {
        if (pvWorldRulesTask != null) {
            pvWorldRulesTask.cancel();
            pvWorldRulesTask = null;
        }
        if (menuRefreshTask != null) {
            menuRefreshTask.cancel();
            menuRefreshTask = null;
        }
        if (pendingRestoreManager != null) {
            pendingRestoreManager.save();
            pendingRestoreManager.close();
        }
        if (matchCrashRecoveryManager != null) {
            matchCrashRecoveryManager.close();
        }
        if (spectatorManager != null) {
            spectatorManager.restoreAllOnline();
        }
        if (addonManager != null) {
            addonManager.shutdown();
        }
    }

    public void setupPvWorld() {
        if (pvWorldRulesTask != null) {
            pvWorldRulesTask.cancel();
            pvWorldRulesTask = null;
        }
        java.util.List<String> worldNames;
        if (settings != null) {
            worldNames = settings.getProtectedWorlds();
        } else {
            worldNames = getConfig().getStringList("protection.worlds");
            if (worldNames == null || worldNames.isEmpty()) {
                String single = getConfig().getString("protection.world", "");
                worldNames = new java.util.ArrayList<>();
                if (single != null && !single.trim().isEmpty()) {
                    worldNames.add(single.trim());
                }
            }
        }
        if (worldNames == null || worldNames.isEmpty()) {
            getLogger().info("Pv world rules disabled (no world configured).");
            return;
        }
        final java.util.List<String> targetWorldNames = new java.util.ArrayList<>(worldNames);
        if (!settings.isWorldRulesEnabled()) {
            getLogger().info("World rules disabled via config.");
            return;
        }
        for (String worldName : targetWorldNames) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                getLogger().warning("World '" + worldName + "' not found. Skipping pv world rules.");
                continue;
            }
            Bukkit.getGlobalRegionScheduler().run(this, task -> applyWorldRules(world));
        }
        pvWorldRulesTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            for (String worldName : targetWorldNames) {
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    continue;
                }
                applyWorldRules(world);
            }
        }, 1L, settings.getWorldRulesTickInterval());
    }

    private void applyWorldRules(World world) {
        if (world == null) {
            return;
        }
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, settings.isWorldRulesDaylightCycle());
        world.setGameRule(GameRule.DO_MOB_SPAWNING, settings.isWorldRulesMobSpawning());
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, settings.isWorldRulesWeatherCycle());
        if (settings.isWorldRulesClearWeather()) {
            world.setStorm(false);
            world.setThundering(false);
        }
        if (settings.getWorldRulesTimeLock() >= 0) {
            world.setTime(settings.getWorldRulesTimeLock());
        }
    }

    private void logStartupInfo() {
        int modeCount = modeManager != null ? modeManager.getModes().size() : 0;
        int arenaCount = arenaManager != null ? arenaManager.getArenas().size() : 0;
        String version = getDescription().getVersion();
        String bar = "======================================================================";
        getLogger().info(bar);
        getLogger().info(">>> PvPArena-Light v" + version + " | by _RuCat <<<");
        getLogger().info(bar);
        getLogger().info("PvPArena-Light " + version + " loaded | Modes: " + modeCount + " | Arenas: " + arenaCount);
        getLogger().info("Support / Discord: ru3690");
        getLogger().info("Thank you for choosing PvPArena ✨");
        getLogger().info(bar);
    }

    public void reloadAll() {
        modesConfig.reload();
        arenasConfig.reload();
        kitsConfig.reload();
        messagesConfig.reload();
        reloadConfig();
        mergeConfigDefaults();
        settings.reload(getConfig());
        this.messagesConfig = new MessagesConfig(this, selectMessagesFile());
        this.messageManager = new MessageManager(messagesConfig);
        MessageUtil.init(messageManager);
        kitManager.load();
        modeManager.load();
        arenaManager.load();
        if (bigDoorManager != null) {
            bigDoorManager.clearAllSnapshots();
        }
        if (addonManager != null) {
            addonManager.reloadDiscoveredPlugins();
        }
    }

    public ModeManager getModeManager() {
        return modeManager;
    }

    private void mergeConfigDefaults() {
        try (InputStream stream = getResource("config.yml")) {
            if (stream == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
            getConfig().setDefaults(defaults);
            getConfig().options().copyDefaults(true);
            saveConfig();
        } catch (Exception ex) {
            getLogger().warning("Failed to merge config defaults: " + ex.getMessage());
        }
    }

    private String selectMessagesFile() {
        String lang = settings != null ? settings.getLanguage() : getConfig().getString("language", "en");
        if (lang == null || lang.isEmpty()) {
            lang = "en";
        }
        String lower = lang.trim().toLowerCase(java.util.Locale.ROOT);
        String compact = lower.replace("-", "").replace("_", "");

        // prefer lang/<code>.yml, with alias fallback (e.g. zh_tw -> zhtw)
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add("lang/" + lower + ".yml");
        candidates.add("lang/" + compact + ".yml");

        if (compact.equals("zhtw") || compact.equals("zhhant") || compact.equals("zhtraditional")) {
            candidates.add("lang/zhtw.yml");
            candidates.add("lang/zh_tw.yml");
            candidates.add("lang/zh-tw.yml");
        } else if (compact.equals("zhcn") || compact.equals("zhhans") || compact.equals("zhsimplified")) {
            candidates.add("lang/zh.yml");
            candidates.add("lang/zh_cn.yml");
            candidates.add("lang/zh-cn.yml");
        } else if (compact.equals("enus") || compact.equals("engb")) {
            candidates.add("lang/en.yml");
        }

        for (String candidate : candidates) {
            java.io.InputStream res = getResource(candidate);
            if (res != null) {
                try { res.close(); } catch (Exception ignored) {}
                return candidate;
            }
        }

        return compact.startsWith("en") ? "messages_en.yml" : "messages.yml";
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public QueueManager getQueueManager() {
        return queueManager;
    }

    public MatchManager getMatchManager() {
        return matchManager;
    }

    public KitManager getKitManager() {
        return kitManager;
    }

    public BigDoorManager getBigDoorManager() {
        return bigDoorManager;
    }

    public AddonManager getAddonManager() {
        return addonManager;
    }
}
