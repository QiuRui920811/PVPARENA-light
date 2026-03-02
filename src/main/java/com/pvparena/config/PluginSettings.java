package com.pvparena.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PluginSettings {
    private String language;
    private boolean pkEnabled;
    private boolean requirePkToEnterWorld;
    private boolean protectionEnabled;
    private boolean protectionArenaOnly;
    private boolean protectionLockAdventure;
    private boolean blockBreak;
    private boolean blockPlace;
    private boolean blockInteract;
    private boolean blockEntityInteract;
    private boolean blockDrop;
    private boolean blockPickup;
    private List<String> protectedWorlds;
    private String protectedWorld;
    private boolean borderEnabled;
    private boolean borderLoseOnExit;
    private String borderLoseReasonKey;
    private String borderWarnKey;
    private boolean worldRulesEnabled;
    private long worldRulesTimeLock;
    private boolean worldRulesDaylightCycle;
    private boolean worldRulesMobSpawning;
    private boolean worldRulesWeatherCycle;
    private boolean worldRulesClearWeather;
    private long worldRulesTickInterval;
    private int guiMainSize;
    private int guiMainStartSlot;
    private int guiMainStep;
    private int guiMainCancelSlot;
    private Map<String, Integer> guiMainModeSlots;
    private List<GuiDecorationItem> guiMainDecorations;
    private int guiDuelSize;
    private int guiDuelStartSlot;
    private int guiDuelStep;
    private List<GuiDecorationItem> guiDuelDecorations;
    private GuiControlItem guiDuelSubmitControl;
    private GuiControlItem guiDuelModeControl;
    private GuiControlItem guiDuelTimeControl;
    private GuiControlItem guiDuelMapControl;
    private GuiControlItem guiDuelMapLockedFeedbackControl;
    private GuiControlItem guiDuelCancelControl;
    private int guiDuelMapLockedFeedbackTicks;
    private boolean guiDuelButtonSoundEnabled;
    private String guiDuelButtonSound;
    private float guiDuelButtonSoundVolume;
    private float guiDuelButtonSoundPitch;
    private int duelRequestTimeoutSeconds;
    private int queueTimeoutSeconds;
    private int queueBotFallbackSeconds;
    private int queueBotRetrySeconds;
    private boolean restoreDebug;
    private boolean spectatorEnabled;
    private boolean spectatorVanish;
    private boolean spectatorHideFromPlayers;
    private boolean spectatorCollidable;
    private boolean spectatorCanBeTargeted;
    private boolean spectatorIgnoreRealEvents;
    private org.bukkit.Material selectionTool;
    private String selectionToolDisplayName;
    private List<String> selectionToolLore;
    private boolean selectionToolGlow;
    private boolean selectionSneakLeftOpenAdminMenu;
    private String selectionSneakLeftCommand;
    private boolean lockMovementDuringCountdown;

    public PluginSettings(FileConfiguration config) {
        reload(config);
    }

    public void reload(FileConfiguration config) {
        this.language = config.getString("language", "en");
        this.pkEnabled = config.getBoolean("pk.enabled", true);
        this.requirePkToEnterWorld = config.getBoolean("protection.require-pk-to-enter", false);
        this.protectionEnabled = config.getBoolean("protection.enabled", true);
        this.protectionArenaOnly = config.getBoolean("protection.arena-only", true);
        this.protectionLockAdventure = config.getBoolean("protection.lock-adventure", true);
        this.blockBreak = config.getBoolean("protection.block-break", true);
        this.blockPlace = config.getBoolean("protection.block-place", true);
        this.blockInteract = config.getBoolean("protection.block-interact", true);
        this.blockEntityInteract = config.getBoolean("protection.block-entity-interact", true);
        this.blockDrop = config.getBoolean("protection.block-drop", true);
        this.blockPickup = config.getBoolean("protection.block-pickup", true);
        LinkedHashSet<String> worlds = new LinkedHashSet<>();
        for (String world : config.getStringList("protection.worlds")) {
            if (world == null) {
                continue;
            }
            String normalized = world.trim();
            if (!normalized.isEmpty()) {
                worlds.add(normalized);
            }
        }
        if (worlds.isEmpty()) {
            String single = config.getString("protection.world", "pv");
            if (single != null && !single.trim().isEmpty()) {
                worlds.add(single.trim());
            }
        }
        this.protectedWorlds = new ArrayList<>(worlds);
        this.protectedWorld = this.protectedWorlds.isEmpty() ? "" : this.protectedWorlds.get(0);
        this.borderEnabled = config.getBoolean("border.enabled", true);
        this.borderLoseOnExit = config.getBoolean("border.lose-on-exit", true);
        this.borderLoseReasonKey = config.getString("border.lose-reason-key", "border_lost");
        this.borderWarnKey = config.getString("border.warn-key", "border_warn");
        this.worldRulesEnabled = config.getBoolean("world-rules.enabled", true);
        this.worldRulesTimeLock = config.getLong("world-rules.time-lock", 6000L);
        this.worldRulesDaylightCycle = config.getBoolean("world-rules.daylight-cycle", false);
        this.worldRulesMobSpawning = config.getBoolean("world-rules.mob-spawning", false);
        this.worldRulesWeatherCycle = config.getBoolean("world-rules.weather-cycle", false);
        this.worldRulesClearWeather = config.getBoolean("world-rules.clear-weather", true);
        this.worldRulesTickInterval = Math.max(20L, config.getLong("world-rules.tick-interval", 200L));
        this.guiMainSize = clampInventorySize(config.getInt("gui.main.size", 27));
        this.guiMainStartSlot = clampSlot(config.getInt("gui.main.start-slot", 10), guiMainSize);
        this.guiMainStep = Math.max(1, config.getInt("gui.main.step", 2));
        this.guiMainCancelSlot = clampSlot(config.getInt("gui.main.cancel-slot", 22), guiMainSize);
        this.guiMainModeSlots = new HashMap<>();
        var modeSlotsSection = config.getConfigurationSection("gui.main.mode-slots");
        if (modeSlotsSection != null) {
            for (String key : modeSlotsSection.getKeys(false)) {
                int slot = clampSlot(modeSlotsSection.getInt(key), guiMainSize);
                guiMainModeSlots.put(key.toLowerCase(), slot);
            }
        }
        this.guiMainDecorations = parseDecorations(config, "gui.main.decorations", guiMainSize);
        this.guiDuelSize = clampInventorySize(config.getInt("gui.duel.size", 27));
        this.guiDuelStartSlot = clampSlot(config.getInt("gui.duel.start-slot", 10), guiDuelSize);
        this.guiDuelStep = Math.max(1, config.getInt("gui.duel.step", 2));
        this.guiDuelDecorations = parseDecorations(config, "gui.duel.decorations", guiDuelSize);
        this.guiDuelSubmitControl = parseGuiControl(config, "gui.duel.controls.submit",
            10, "GREEN_STAINED_GLASS_PANE", "&a&lSend Invite",
            List.of("&7Click to send duel invite"), true, guiDuelSize);
        this.guiDuelModeControl = parseGuiControl(config, "gui.duel.controls.mode",
            12, "DIAMOND_SWORD", "&b&lMode: &f{mode}",
            List.of("&7Click to cycle mode", "&7Left next / Right previous"), false, guiDuelSize);
        this.guiDuelTimeControl = parseGuiControl(config, "gui.duel.controls.time",
            13, "CLOCK", "&e&lTime: &f{minutes} min",
            List.of("&7Time up = draw", "&7Click to cycle duration"), false, guiDuelSize);
        this.guiDuelMapControl = parseGuiControl(config, "gui.duel.controls.map",
            14, "GRASS_BLOCK", "&6&lMap: &f{map}",
            List.of("&7Click to cycle map", "&7Left next / Right previous"), false, guiDuelSize);
        this.guiDuelMapLockedFeedbackControl = parseGuiControl(config, "gui.duel.controls.map-locked-feedback",
            14, "BARRIER", "&c&lMap Locked",
            List.of("&7This mode uses a bound map", "&7Cannot change map"), false, guiDuelSize);
        this.guiDuelCancelControl = parseGuiControl(config, "gui.duel.controls.cancel",
            16, "RED_STAINED_GLASS_PANE", "&c&lClose",
            List.of("&7Close this menu"), false, guiDuelSize);
        this.guiDuelMapLockedFeedbackTicks = clampInt(config.getInt("gui.duel.controls.map-locked-feedback.revert-ticks", 30), 5, 200);
        this.guiDuelButtonSoundEnabled = config.getBoolean("gui.duel.button-sound.enabled", true);
        this.guiDuelButtonSound = normalizeSoundKey(config.getString("gui.duel.button-sound.sound", "ui.button.click"));
        this.guiDuelButtonSoundVolume = clampFloat(config.getDouble("gui.duel.button-sound.volume", 1.0D), 0.0f, 2.0f);
        this.guiDuelButtonSoundPitch = clampFloat(config.getDouble("gui.duel.button-sound.pitch", 1.0D), 0.5f, 2.0f);

        this.duelRequestTimeoutSeconds = clampInt(config.getInt("duel.request-timeout-seconds", 60), 5, 3600);

        this.queueTimeoutSeconds = clampInt(config.getInt("queue.timeout-seconds", 60), 5, 3600);
        this.queueBotFallbackSeconds = clampInt(config.getInt("queue.bot-fallback-seconds", 15), 1, 3600);
        this.queueBotRetrySeconds = clampInt(config.getInt("queue.bot-retry-seconds", 5), 1, 3600);
        this.restoreDebug = config.getBoolean("debug.restore", false);
        this.spectatorEnabled = config.getBoolean("spectator.enabled", true);
        this.spectatorVanish = config.getBoolean("spectator.vanish", true);
        this.spectatorHideFromPlayers = config.getBoolean("spectator.hide-from-players", true);
        this.spectatorCollidable = config.getBoolean("spectator.collidable", false);
        this.spectatorCanBeTargeted = config.getBoolean("spectator.can-be-targeted", false);
        this.spectatorIgnoreRealEvents = config.getBoolean("spectator.ignore-real-events", true);

        String toolKey = config.getString("setup.selection-tool", "WOODEN_AXE");
        org.bukkit.Material parsed = org.bukkit.Material.matchMaterial(toolKey == null ? "" : toolKey.trim());
        if (parsed == null || parsed.isAir()) {
            parsed = org.bukkit.Material.WOODEN_AXE;
        }
        this.selectionTool = parsed;
        this.selectionToolDisplayName = config.getString("setup.selection-tool-name", "&bPvPArena Selection Tool");
        this.selectionToolLore = config.getStringList("setup.selection-tool-lore");
        this.selectionToolGlow = config.getBoolean("setup.selection-tool-glow", true);
        this.selectionSneakLeftOpenAdminMenu = config.getBoolean("setup.sneak-left-open-admin-menu", false);
        this.selectionSneakLeftCommand = config.getString("setup.sneak-left-admin-command", "pvparena help");
        this.lockMovementDuringCountdown = config.getBoolean("match.lock-movement-during-countdown", true);
    }

    public String getLanguage() {
        return language;
    }

    public boolean isPkEnabled() {
        return pkEnabled;
    }

    public boolean isRequirePkToEnterWorld() {
        return requirePkToEnterWorld;
    }

    public boolean isProtectionEnabled() {
        return protectionEnabled;
    }

    public boolean isProtectionArenaOnly() {
        return protectionArenaOnly;
    }

    public boolean isProtectionForceSpawn() {
        return false;
    }

    public boolean isProtectionLockAdventure() {
        return protectionLockAdventure;
    }

    public boolean isBlockBreak() {
        return blockBreak;
    }

    public boolean isBlockPlace() {
        return blockPlace;
    }

    public boolean isBlockInteract() {
        return blockInteract;
    }

    public boolean isBlockEntityInteract() {
        return blockEntityInteract;
    }

    public boolean isBlockDrop() {
        return blockDrop;
    }

    public boolean isBlockPickup() {
        return blockPickup;
    }

    public String getProtectedWorld() {
        return protectedWorld;
    }

    public List<String> getProtectedWorlds() {
        return new ArrayList<>(protectedWorlds);
    }

    public boolean isProtectedWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        for (String configured : protectedWorlds) {
            if (configured != null && configured.equalsIgnoreCase(worldName)) {
                return true;
            }
        }
        return false;
    }

    public boolean isBorderEnabled() {
        return borderEnabled;
    }

    public boolean isBorderLoseOnExit() {
        return borderLoseOnExit;
    }

    public String getBorderLoseReasonKey() {
        return borderLoseReasonKey;
    }

    public String getBorderWarnKey() {
        return borderWarnKey;
    }

    public boolean isWorldRulesEnabled() {
        return worldRulesEnabled;
    }

    public long getWorldRulesTimeLock() {
        return worldRulesTimeLock;
    }

    public boolean isWorldRulesDaylightCycle() {
        return worldRulesDaylightCycle;
    }

    public boolean isWorldRulesMobSpawning() {
        return worldRulesMobSpawning;
    }

    public boolean isWorldRulesWeatherCycle() {
        return worldRulesWeatherCycle;
    }

    public boolean isWorldRulesClearWeather() {
        return worldRulesClearWeather;
    }

    public long getWorldRulesTickInterval() {
        return worldRulesTickInterval;
    }

    public int getGuiMainSize() {
        return guiMainSize;
    }

    public int getGuiMainStartSlot() {
        return guiMainStartSlot;
    }

    public int getGuiMainStep() {
        return guiMainStep;
    }

    public int getGuiMainCancelSlot() {
        return guiMainCancelSlot;
    }

    public Integer getGuiMainModeSlot(String modeId) {
        if (modeId == null) {
            return null;
        }
        return guiMainModeSlots.get(modeId.toLowerCase());
    }

    public List<GuiDecorationItem> getGuiMainDecorations() {
        return guiMainDecorations;
    }

    public int getGuiDuelSize() {
        return guiDuelSize;
    }

    public int getGuiDuelStartSlot() {
        return guiDuelStartSlot;
    }

    public int getGuiDuelStep() {
        return guiDuelStep;
    }

    public List<GuiDecorationItem> getGuiDuelDecorations() {
        return guiDuelDecorations;
    }

    public GuiControlItem getGuiDuelSubmitControl() {
        return guiDuelSubmitControl;
    }

    public GuiControlItem getGuiDuelModeControl() {
        return guiDuelModeControl;
    }

    public GuiControlItem getGuiDuelTimeControl() {
        return guiDuelTimeControl;
    }

    public GuiControlItem getGuiDuelMapControl() {
        return guiDuelMapControl;
    }

    public GuiControlItem getGuiDuelMapLockedFeedbackControl() {
        return guiDuelMapLockedFeedbackControl;
    }

    public GuiControlItem getGuiDuelCancelControl() {
        return guiDuelCancelControl;
    }

    public int getGuiDuelMapLockedFeedbackTicks() {
        return guiDuelMapLockedFeedbackTicks;
    }

    public boolean isGuiDuelButtonSoundEnabled() {
        return guiDuelButtonSoundEnabled;
    }

    public String getGuiDuelButtonSound() {
        return guiDuelButtonSound;
    }

    public float getGuiDuelButtonSoundVolume() {
        return guiDuelButtonSoundVolume;
    }

    public float getGuiDuelButtonSoundPitch() {
        return guiDuelButtonSoundPitch;
    }

    public int getDuelRequestTimeoutSeconds() {
        return duelRequestTimeoutSeconds;
    }

    public int getQueueTimeoutSeconds() {
        return queueTimeoutSeconds;
    }

    public int getQueueBotFallbackSeconds() {
        return queueBotFallbackSeconds;
    }

    public int getQueueBotRetrySeconds() {
        return queueBotRetrySeconds;
    }

    public boolean isRestoreDebug() {
        return restoreDebug;
    }

    public boolean isSpectatorEnabled() {
        return spectatorEnabled;
    }

    public boolean isSpectatorVanish() {
        return spectatorVanish;
    }

    public boolean isSpectatorHideFromPlayers() {
        return spectatorHideFromPlayers;
    }

    public boolean isSpectatorCollidable() {
        return spectatorCollidable;
    }

    public boolean isSpectatorCanBeTargeted() {
        return spectatorCanBeTargeted;
    }

    public boolean isSpectatorIgnoreRealEvents() {
        return spectatorIgnoreRealEvents;
    }

    public org.bukkit.Material getSelectionTool() {
        return selectionTool;
    }

    public String getSelectionToolDisplayName() {
        return selectionToolDisplayName;
    }

    public List<String> getSelectionToolLore() {
        return selectionToolLore;
    }

    public boolean isSelectionToolGlow() {
        return selectionToolGlow;
    }

    public boolean isSelectionSneakLeftOpenAdminMenu() {
        return selectionSneakLeftOpenAdminMenu;
    }

    public String getSelectionSneakLeftCommand() {
        return selectionSneakLeftCommand;
    }

    public boolean isLockMovementDuringCountdown() {
        return lockMovementDuringCountdown;
    }

    private int clampInventorySize(int size) {
        int[] allowed = {9, 18, 27, 36, 45, 54};
        for (int a : allowed) {
            if (size <= a) return a;
        }
        return 54;
    }

    private List<GuiDecorationItem> parseDecorations(FileConfiguration config, String path, int invSize) {
        List<GuiDecorationItem> result = new ArrayList<>();
        List<Map<?, ?>> rawList = config.getMapList(path);
        if (rawList == null) {
            return result;
        }
        for (Map<?, ?> raw : rawList) {
            if (raw == null) {
                continue;
            }
            Object slotObj = raw.get("slot");
            if (slotObj == null) {
                continue;
            }
            int slot;
            try {
                slot = clampSlot(Integer.parseInt(String.valueOf(slotObj)), invSize);
            } catch (Exception ignored) {
                continue;
            }

            Object materialObj = raw.containsKey("material") ? raw.get("material") : "GRAY_STAINED_GLASS_PANE";
            String materialName = String.valueOf(materialObj);
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(materialName.toUpperCase(Locale.ROOT));
            if (material == null || material.isAir()) {
                material = org.bukkit.Material.GRAY_STAINED_GLASS_PANE;
            }

            String name = raw.get("name") == null ? "" : String.valueOf(raw.get("name"));
            List<String> lore = new ArrayList<>();
            Object loreObj = raw.get("lore");
            if (loreObj instanceof List<?> loreList) {
                for (Object line : loreList) {
                    lore.add(String.valueOf(line));
                }
            }
            Object glowObj = raw.containsKey("glow") ? raw.get("glow") : false;
            boolean glow = Boolean.parseBoolean(String.valueOf(glowObj));
            result.add(new GuiDecorationItem(slot, material, name, lore, glow));
        }
        return result;
    }

    private GuiControlItem parseGuiControl(FileConfiguration config, String path,
                                           int defaultSlot,
                                           String defaultMaterial,
                                           String defaultName,
                                           List<String> defaultLore,
                                           boolean defaultGlow,
                                           int invSize) {
        int slot = clampSlot(config.getInt(path + ".slot", defaultSlot), invSize);
        String materialName = config.getString(path + ".material", defaultMaterial);
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(
                (materialName == null ? defaultMaterial : materialName).toUpperCase(Locale.ROOT));
        if (material == null || material.isAir()) {
            material = org.bukkit.Material.matchMaterial(defaultMaterial);
            if (material == null || material.isAir()) {
                material = org.bukkit.Material.BARRIER;
            }
        }
        String name = config.getString(path + ".name", defaultName);
        List<String> lore = config.getStringList(path + ".lore");
        if (lore == null || lore.isEmpty()) {
            lore = new ArrayList<>(defaultLore == null ? List.of() : defaultLore);
        }
        boolean glow = config.getBoolean(path + ".glow", defaultGlow);
        return new GuiControlItem(slot, material, name == null ? "" : name, lore, glow);
    }

    private int clampSlot(int slot, int invSize) {
        return Math.max(0, Math.min(invSize - 1, slot));
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clampFloat(double value, float min, float max) {
        return (float) Math.max(min, Math.min(max, value));
    }

    private String normalizeSoundKey(String sound) {
        if (sound == null) {
            return null;
        }
        String value = sound.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "none".equals(value) || "off".equals(value) || "disabled".equals(value)) {
            return null;
        }
        if (!value.contains(":")) {
            value = "minecraft:" + value;
        }
        return value;
    }

    public static class GuiDecorationItem {
        private final int slot;
        private final org.bukkit.Material material;
        private final String name;
        private final List<String> lore;
        private final boolean glow;

        public GuiDecorationItem(int slot, org.bukkit.Material material, String name, List<String> lore, boolean glow) {
            this.slot = slot;
            this.material = material;
            this.name = name;
            this.lore = lore == null ? List.of() : lore;
            this.glow = glow;
        }

        public int getSlot() {
            return slot;
        }

        public org.bukkit.Material getMaterial() {
            return material;
        }

        public String getName() {
            return name;
        }

        public List<String> getLore() {
            return lore;
        }

        public boolean isGlow() {
            return glow;
        }
    }

    public static class GuiControlItem {
        private final int slot;
        private final org.bukkit.Material material;
        private final String name;
        private final List<String> lore;
        private final boolean glow;

        public GuiControlItem(int slot, org.bukkit.Material material, String name, List<String> lore, boolean glow) {
            this.slot = slot;
            this.material = material;
            this.name = name;
            this.lore = lore == null ? List.of() : lore;
            this.glow = glow;
        }

        public int getSlot() {
            return slot;
        }

        public org.bukkit.Material getMaterial() {
            return material;
        }

        public String getName() {
            return name;
        }

        public List<String> getLore() {
            return lore;
        }

        public boolean isGlow() {
            return glow;
        }
    }
}
