package com.pvparena.model;

import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Mode {
    private final String id;
    private final String displayName;
    private final List<String> lore;
    private final Material icon;
    private final ModeKit kit;
    private final ModeSettings settings;
    private final Set<String> preferredArenaIds;
    private final Integer mainMenuSlot;
    private final Integer duelMenuSlot;
    private final boolean usePlayerInventory;
    private final boolean restoreBackupAfterMatch;

    public Mode(String id, String displayName, List<String> lore, Material icon, ModeKit kit,
                ModeSettings settings, Set<String> preferredArenaIds,
                Integer mainMenuSlot, Integer duelMenuSlot,
                boolean usePlayerInventory, boolean restoreBackupAfterMatch) {
        this.id = id;
        this.displayName = displayName;
        this.lore = lore;
        this.icon = icon;
        this.kit = kit;
        this.settings = settings;
        this.mainMenuSlot = mainMenuSlot;
        this.duelMenuSlot = duelMenuSlot;
        this.usePlayerInventory = usePlayerInventory;
        this.restoreBackupAfterMatch = restoreBackupAfterMatch;
        if (preferredArenaIds == null || preferredArenaIds.isEmpty()) {
            this.preferredArenaIds = Collections.emptySet();
        } else {
            this.preferredArenaIds = Collections.unmodifiableSet(new LinkedHashSet<>(preferredArenaIds));
        }
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<String> getLore() {
        return lore;
    }

    public Material getIcon() {
        return icon;
    }

    public ModeKit getKit() {
        return kit;
    }

    public ModeSettings getSettings() {
        return settings;
    }

    public Set<String> getPreferredArenaIds() {
        return preferredArenaIds;
    }

    public boolean hasArenaRestriction() {
        return !preferredArenaIds.isEmpty();
    }

    public Integer getMainMenuSlot() {
        return mainMenuSlot;
    }

    public Integer getDuelMenuSlot() {
        return duelMenuSlot;
    }

    public boolean isUsePlayerInventory() {
        return usePlayerInventory;
    }

    public boolean isRestoreBackupAfterMatch() {
        return restoreBackupAfterMatch;
    }
}
