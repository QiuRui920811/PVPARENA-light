package com.pvparena.manager;

import com.pvparena.config.ModesConfig;
import com.pvparena.model.Mode;
import com.pvparena.model.ModeKit;
import com.pvparena.model.ModeSettings;
import com.pvparena.util.ItemUtil;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ModeManager {
    private final ModesConfig modesConfig;
    private final KitManager kitManager;
    // Folia runs on multiple region threads; swap map atomically on reload.
    private volatile Map<String, Mode> modes = new ConcurrentHashMap<>();

    public ModeManager(ModesConfig modesConfig, KitManager kitManager) {
        this.modesConfig = modesConfig;
        this.kitManager = kitManager;
        load();
    }

    public void load() {
        Map<String, Mode> loaded = new HashMap<>();
        ConfigurationSection section = modesConfig.getConfig().getConfigurationSection("modes");
        if (section == null) {
            modes = new ConcurrentHashMap<>();
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection modeSection = section.getConfigurationSection(id);
            if (modeSection == null) {
                continue;
            }
            String displayName = modeSection.getString("displayName", id);
            List<String> lore = modeSection.getStringList("lore");
            Material icon = Material.matchMaterial(modeSection.getString("icon", "DIAMOND_SWORD"));
            if (icon == null) {
                icon = Material.DIAMOND_SWORD;
            }

            ConfigurationSection settingsSection = modeSection.getConfigurationSection("settings");
            double maxHealth = settingsSection != null ? settingsSection.getDouble("maxHealth", 20.0) : 20.0;
            int hunger = settingsSection != null ? settingsSection.getInt("hunger", 20) : 20;
            float saturation = settingsSection != null ? (float) settingsSection.getDouble("saturation", 5.0) : 5.0f;
            int noDamageTicks = settingsSection != null ? settingsSection.getInt("noDamageTicks", 10) : 10;
            // 1.8 PVP 已停用，統一使用原版冷卻行為。
            boolean legacyPvp = false;
            boolean botEnabled = settingsSection != null && settingsSection.getBoolean("bot", false);
            int roundsToWin = settingsSection != null ? settingsSection.getInt("rounds-to-win", 1) : 1;
            int nextRoundDelaySeconds = settingsSection != null ? settingsSection.getInt("next-round-delay-seconds", 0) : 0;
            int winnerLeaveDelaySeconds = settingsSection != null ? settingsSection.getInt("winner-leave-delay-seconds", 0) : 0;
            boolean buildEnabled = settingsSection == null || settingsSection.getBoolean("build.enabled", true);
            boolean breakPlacedBlocksOnly = settingsSection != null && settingsSection.getBoolean("build.break-placed-only", false);
            boolean dropOnFinalRoundOnly = settingsSection == null || settingsSection.getBoolean("inventory.drop-on-final-round-only", true);
            boolean spectatorEnabled = settingsSection == null || settingsSection.getBoolean("spectator.enabled", true);
            boolean eliminatedCanSpectate = settingsSection == null || settingsSection.getBoolean("spectator.eliminated-can-spectate", true);
            boolean publicSpectatorEnabled = settingsSection == null || settingsSection.getBoolean("spectator.public-enabled", true);
            roundsToWin = Math.max(1, Math.min(15, roundsToWin));
            nextRoundDelaySeconds = Math.max(0, Math.min(60, nextRoundDelaySeconds));
                winnerLeaveDelaySeconds = Math.max(0, Math.min(300, winnerLeaveDelaySeconds));
            ModeSettings settings = new ModeSettings(maxHealth, hunger, saturation, noDamageTicks, legacyPvp, botEnabled,
                        roundsToWin, nextRoundDelaySeconds, winnerLeaveDelaySeconds, buildEnabled, breakPlacedBlocksOnly,
                        dropOnFinalRoundOnly,
                    spectatorEnabled, eliminatedCanSpectate, publicSpectatorEnabled);
            Set<String> preferredArenaIds = parsePreferredArenaIds(modeSection);
            Integer mainMenuSlot = parseOptionalSlot(modeSection, "gui.main-slot");
            Integer duelMenuSlot = parseOptionalSlot(modeSection, "gui.duel-slot");

            ConfigurationSection kitSection = modeSection.getConfigurationSection("kit");
            String kitRef = modeSection.getString("kitRef", "").trim();
            boolean usePlayerInventory = kitRef.isEmpty() && kitSection == null;
            boolean restoreBackupAfterMatch = settingsSection != null
                    ? settingsSection.getBoolean("inventory.restore-backup", !usePlayerInventory)
                    : !usePlayerInventory;

            if (!kitRef.isEmpty()) {
                ModeKit kit = kitManager.getKit(kitRef);
                if (kit == null) {
                    kit = new ModeKit(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
                }
                loaded.put(id.toLowerCase(), new Mode(id.toLowerCase(), displayName, lore, icon, kit, settings,
                        preferredArenaIds, mainMenuSlot, duelMenuSlot, usePlayerInventory, restoreBackupAfterMatch));
                continue;
            }

            List<ItemStack> items = new ArrayList<>();
            List<ItemStack> armor = new ArrayList<>();
            List<PotionEffect> effects = new ArrayList<>();
            if (kitSection != null) {
                List<Map<?, ?>> itemMaps = (List<Map<?, ?>>) kitSection.getList("items");
                items = ItemUtil.parseItemList(itemMaps);
                List<Map<?, ?>> armorMaps = (List<Map<?, ?>>) kitSection.getList("armor");
                armor = ItemUtil.parseItemList(armorMaps);

                List<Map<?, ?>> potionMaps = (List<Map<?, ?>>) kitSection.getList("potionEffects");
                if (potionMaps != null) {
                    for (Map<?, ?> potionMap : potionMaps) {
                        PotionEffect effect = parseEffect(potionMap);
                        if (effect != null) {
                            effects.add(effect);
                        }
                    }
                }
            }
            ModeKit kit = new ModeKit(items, armor, effects);
            loaded.put(id.toLowerCase(), new Mode(id.toLowerCase(), displayName, lore, icon, kit, settings,
                    preferredArenaIds, mainMenuSlot, duelMenuSlot, usePlayerInventory, restoreBackupAfterMatch));
        }

        modes = new ConcurrentHashMap<>(loaded);
    }

    private PotionEffect parseEffect(Map<?, ?> potionMap) {
        if (potionMap == null) {
            return null;
        }
        Object typeObj = potionMap.get("type");
        if (typeObj == null) {
            return null;
        }
        PotionEffectType type = PotionEffectType.getByName(typeObj.toString());
        if (type == null) {
            return null;
        }
        int duration = getInt(potionMap, "duration", 200);
        int amplifier = getInt(potionMap, "amplifier", 0);
        boolean ambient = getBoolean(potionMap, "ambient", false);
        boolean particles = getBoolean(potionMap, "particles", true);
        return new PotionEffect(type, duration, amplifier, ambient, particles);
    }

    private int getInt(Map<?, ?> map, String key, int def) {
        Object value = map.get(key);
        return value == null ? def : Integer.parseInt(value.toString());
    }

    private boolean getBoolean(Map<?, ?> map, String key, boolean def) {
        Object value = map.get(key);
        return value == null ? def : Boolean.parseBoolean(value.toString());
    }

    private Set<String> parsePreferredArenaIds(ConfigurationSection modeSection) {
        Set<String> result = new LinkedHashSet<>();

        List<String> arenas = modeSection.getStringList("arenas");
        if (arenas != null) {
            for (String arena : arenas) {
                addArenaId(result, arena);
            }
        }

        String singleArena = modeSection.getString("arena", "");
        if (singleArena != null && !singleArena.isBlank()) {
            if (singleArena.contains(",")) {
                String[] split = singleArena.split(",");
                for (String part : split) {
                    addArenaId(result, part);
                }
            } else {
                addArenaId(result, singleArena);
            }
        }

        return result;
    }

    private void addArenaId(Set<String> collector, String raw) {
        if (collector == null || raw == null) {
            return;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty()) {
            collector.add(normalized);
        }
    }

    private Integer parseOptionalSlot(ConfigurationSection section, String path) {
        if (section == null || path == null || path.isBlank() || !section.contains(path)) {
            return null;
        }
        int slot = section.getInt(path, -1);
        return slot < 0 ? null : slot;
    }

    public Map<String, Mode> getModes() {
        return modes;
    }

    public Mode getMode(String id) {
        return modes.get(id.toLowerCase());
    }
}
