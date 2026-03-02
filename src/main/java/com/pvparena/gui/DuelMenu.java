package com.pvparena.gui;

import com.pvparena.config.PluginSettings;
import com.pvparena.manager.ArenaManager;
import com.pvparena.manager.MessageManager;
import com.pvparena.manager.ModeManager;
import com.pvparena.model.Arena;
import com.pvparena.model.Mode;
import com.pvparena.util.GuiTextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class DuelMenu {
    public static final String TITLE_KEY = "duel_menu_title";

    private static final int[] TIME_OPTIONS_SECONDS = new int[]{180, 300, 600, 900};

    private final ArenaManager arenaManager;
    private final ModeManager modeManager;
    private final MessageManager messageManager;
    private final PluginSettings settings;

    public DuelMenu(MessageManager messageManager, ModeManager modeManager, ArenaManager arenaManager, PluginSettings settings) {
        this.messageManager = messageManager;
        this.modeManager = modeManager;
        this.arenaManager = arenaManager;
        this.settings = settings;
    }

    public void open(Player player, UUID targetId) {
        int size = settings.getGuiDuelSize();
        DuelMenuHolder holder = createHolder(targetId);
        Inventory inventory = Bukkit.createInventory(holder, size,
                messageManager.getUi(TITLE_KEY));
        render(inventory, holder);
        player.openInventory(inventory);
    }

    public void render(Inventory inventory, DuelMenuHolder holder) {
        if (inventory == null || holder == null) {
            return;
        }
        applyDecorations(inventory, settings.getGuiDuelDecorations());
        PluginSettings.GuiControlItem submitControl = settings.getGuiDuelSubmitControl();
        if (submitControl.getSlot() < inventory.getSize()) {
            inventory.setItem(submitControl.getSlot(), createButton(
                    submitControl.getMaterial(),
                    submitControl.getName(),
                    submitControl.getLore(),
                    submitControl.isGlow()));
        }
        PluginSettings.GuiControlItem cancelControl = settings.getGuiDuelCancelControl();
        if (cancelControl.getSlot() < inventory.getSize()) {
            inventory.setItem(cancelControl.getSlot(), createButton(
                    cancelControl.getMaterial(),
                    cancelControl.getName(),
                    cancelControl.getLore(),
                    cancelControl.isGlow()));
        }

        Mode mode = getSelectedMode(holder);
        String modeName = mode != null ? mode.getDisplayName() : "Not set";
        String arenaId = holder.getSelectedArenaId();
        String mapName = arenaId != null ? arenaId : "No map";
        int seconds = holder.getSelectedTimeSeconds();

        PluginSettings.GuiControlItem modeControl = settings.getGuiDuelModeControl();
        if (modeControl.getSlot() < inventory.getSize()) {
            Material modeIcon = mode != null && mode.getIcon() != null ? mode.getIcon() : modeControl.getMaterial();
            inventory.setItem(modeControl.getSlot(), createButton(
                    modeIcon,
                    applyControlPlaceholders(modeControl.getName(), modeName, mapName, seconds),
                    applyControlPlaceholders(modeControl.getLore(), modeName, mapName, seconds),
                    modeControl.isGlow()));
        }

        PluginSettings.GuiControlItem timeControl = settings.getGuiDuelTimeControl();
        if (timeControl.getSlot() < inventory.getSize()) {
            inventory.setItem(timeControl.getSlot(), createButton(
                    timeControl.getMaterial(),
                    applyControlPlaceholders(timeControl.getName(), modeName, mapName, seconds),
                    applyControlPlaceholders(timeControl.getLore(), modeName, mapName, seconds),
                    timeControl.isGlow()));
        }

        PluginSettings.GuiControlItem mapControl = settings.getGuiDuelMapControl();
        if (mapControl.getSlot() < inventory.getSize()) {
            if (holder.mapLockedFeedbackActive) {
            PluginSettings.GuiControlItem feedback = settings.getGuiDuelMapLockedFeedbackControl();
            inventory.setItem(mapControl.getSlot(), createButton(
                feedback.getMaterial(),
                applyControlPlaceholders(feedback.getName(), modeName, mapName, seconds),
                applyControlPlaceholders(feedback.getLore(), modeName, mapName, seconds),
                feedback.isGlow()));
            } else {
                Material mapIcon = mapControl.getMaterial();
                Arena selectedArena = getSelectedArena(holder);
                if (selectedArena != null && selectedArena.getDuelMapIcon() != null) {
                    mapIcon = selectedArena.getDuelMapIcon();
                } else if (mode != null && mode.getDuelMapIcon() != null) {
                    mapIcon = mode.getDuelMapIcon();
                }
            inventory.setItem(mapControl.getSlot(), createButton(
                mapIcon,
                applyControlPlaceholders(mapControl.getName(), modeName, mapName, seconds),
                applyControlPlaceholders(mapControl.getLore(), modeName, mapName, seconds),
                mapControl.isGlow()));
            }
        }
    }

    public int getSubmitSlot() {
        return settings.getGuiDuelSubmitControl().getSlot();
    }

    public int getModeSlot() {
        return settings.getGuiDuelModeControl().getSlot();
    }

    public int getTimeSlot() {
        return settings.getGuiDuelTimeControl().getSlot();
    }

    public int getMapSlot() {
        return settings.getGuiDuelMapControl().getSlot();
    }

    public int getCancelSlot() {
        return settings.getGuiDuelCancelControl().getSlot();
    }

    public int getMapLockedFeedbackTicks() {
        return settings.getGuiDuelMapLockedFeedbackTicks();
    }

    public boolean isButtonSoundEnabled() {
        return settings.isGuiDuelButtonSoundEnabled() && settings.getGuiDuelButtonSound() != null;
    }

    public String getButtonSound() {
        return settings.getGuiDuelButtonSound();
    }

    public float getButtonSoundVolume() {
        return settings.getGuiDuelButtonSoundVolume();
    }

    public float getButtonSoundPitch() {
        return settings.getGuiDuelButtonSoundPitch();
    }

    public boolean isMapLocked(DuelMenuHolder holder) {
        return holder != null && holder.mapLocked;
    }

    public void showMapLockedFeedback(DuelMenuHolder holder) {
        if (holder == null) {
            return;
        }
        holder.mapLockedFeedbackActive = true;
    }

    public void clearMapLockedFeedback(DuelMenuHolder holder) {
        if (holder == null) {
            return;
        }
        holder.mapLockedFeedbackActive = false;
    }

    public String getSelectedModeDisplayName(DuelMenuHolder holder) {
        Mode mode = getSelectedMode(holder);
        return mode != null ? mode.getDisplayName() : "";
    }

    public void cycleMode(DuelMenuHolder holder, int step) {
        if (holder == null || holder.modeIds.isEmpty()) {
            return;
        }
        holder.modeIndex = floorMod(holder.modeIndex + step, holder.modeIds.size());
        rebuildArenaSelection(holder);
    }

    public void cycleArena(DuelMenuHolder holder, int step) {
        if (holder == null || holder.arenaIds.isEmpty() || holder.mapLocked) {
            return;
        }
        holder.arenaIndex = floorMod(holder.arenaIndex + step, holder.arenaIds.size());
    }

    public void cycleTime(DuelMenuHolder holder, int step) {
        if (holder == null || holder.timeOptionsSeconds.length == 0) {
            return;
        }
        holder.timeIndex = floorMod(holder.timeIndex + step, holder.timeOptionsSeconds.length);
    }

    private DuelMenuHolder createHolder(UUID targetId) {
        List<String> modeIds = modeManager.getModes().values().stream()
                .sorted(Comparator.comparing(Mode::getId, String.CASE_INSENSITIVE_ORDER))
                .map(Mode::getId)
                .toList();
        DuelMenuHolder holder = new DuelMenuHolder(targetId, new ArrayList<>(modeIds), TIME_OPTIONS_SECONDS.clone());
        rebuildArenaSelection(holder);
        return holder;
    }

    private void rebuildArenaSelection(DuelMenuHolder holder) {
        holder.arenaIds.clear();
        holder.mapLocked = false;
        holder.mapLockedFeedbackActive = false;
        Mode mode = getSelectedMode(holder);
        if (mode == null) {
            holder.arenaIndex = 0;
            return;
        }
        List<Arena> arenas = new ArrayList<>(arenaManager.getArenas());
        arenas.sort(Comparator.comparing(Arena::getId, String.CASE_INSENSITIVE_ORDER));
        for (Arena arena : arenas) {
            if (arena == null || !arena.isReady()) {
                continue;
            }
            if (mode.hasArenaRestriction() && !mode.getPreferredArenaIds().contains(arena.getId().toLowerCase())) {
                continue;
            }
            holder.arenaIds.add(arena.getId());
        }
        holder.mapLocked = mode.hasArenaRestriction();
        if (holder.arenaIds.isEmpty()) {
            holder.arenaIndex = 0;
            return;
        }
        holder.arenaIndex = holder.mapLocked ? 0 : floorMod(holder.arenaIndex, holder.arenaIds.size());
    }

    private ItemStack createButton(Material material, String name, List<String> lore, boolean glow) {
        ItemStack item = new ItemStack(material != null ? material : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(GuiTextUtil.noItalic(GuiTextUtil.deserialize(name)));
        List<Component> loreComponents = new ArrayList<>();
        if (lore != null) {
            for (String line : lore) {
                loreComponents.add(GuiTextUtil.noItalic(GuiTextUtil.deserialize(line)));
            }
        }
        if (!loreComponents.isEmpty()) {
            meta.lore(GuiTextUtil.noItalic(loreComponents));
        }
        if (glow) {
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
            meta = item.getItemMeta();
        }
        item.setItemMeta(meta);
        return item;
    }

    private Mode getSelectedMode(DuelMenuHolder holder) {
        if (holder == null) {
            return null;
        }
        String modeId = holder.getSelectedModeId();
        if (modeId == null || modeId.isBlank()) {
            return null;
        }
        return modeManager.getMode(modeId);
    }

    private Arena getSelectedArena(DuelMenuHolder holder) {
        if (holder == null) {
            return null;
        }
        String arenaId = holder.getSelectedArenaId();
        if (arenaId == null || arenaId.isBlank()) {
            return null;
        }
        return arenaManager.getArena(arenaId);
    }

    private int floorMod(int value, int size) {
        if (size <= 0) {
            return 0;
        }
        return Math.floorMod(value, size);
    }

    private String applyControlPlaceholders(String text, String modeName, String mapName, int seconds) {
        if (text == null) {
            return "";
        }
        return text
                .replace("{mode}", modeName == null ? "" : modeName)
                .replace("{map}", mapName == null ? "" : mapName)
                .replace("{seconds}", String.valueOf(Math.max(0, seconds)))
                .replace("{minutes}", String.valueOf(Math.max(0, seconds) / 60));
    }

    private List<String> applyControlPlaceholders(List<String> lines, String modeName, String mapName, int seconds) {
        List<String> out = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return out;
        }
        for (String line : lines) {
            out.add(applyControlPlaceholders(line, modeName, mapName, seconds));
        }
        return out;
    }

    private void applyDecorations(Inventory inventory, List<PluginSettings.GuiDecorationItem> decorations) {
        if (decorations == null || decorations.isEmpty()) {
            return;
        }
        for (PluginSettings.GuiDecorationItem def : decorations) {
            if (def == null) {
                continue;
            }
            int slot = def.getSlot();
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            ItemStack item = new ItemStack(def.getMaterial());
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }
            if (def.getName() != null && !def.getName().isBlank()) {
                meta.displayName(GuiTextUtil.noItalic(GuiTextUtil.deserialize(def.getName())));
            }
            List<Component> lore = new ArrayList<>();
            for (String line : def.getLore()) {
                lore.add(GuiTextUtil.noItalic(GuiTextUtil.deserialize(line)));
            }
            if (!lore.isEmpty()) {
                meta.lore(GuiTextUtil.noItalic(lore));
            }
            if (def.isGlow()) {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                item.setItemMeta(meta);
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
                meta = item.getItemMeta();
            }
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }
    }

    public String getPlainTitle() {
        return messageManager.getPlain("ui." + TITLE_KEY);
    }

    public static class DuelMenuHolder implements InventoryHolder {
        private final UUID targetId;
        private final List<String> modeIds;
        private final List<String> arenaIds = new ArrayList<>();
        private final int[] timeOptionsSeconds;
        private int modeIndex = 0;
        private int arenaIndex = 0;
        private int timeIndex = 1;
        private boolean mapLocked = false;
        private boolean mapLockedFeedbackActive = false;

        public DuelMenuHolder(UUID targetId, List<String> modeIds, int[] timeOptionsSeconds) {
            this.targetId = targetId;
            this.modeIds = modeIds != null ? modeIds : new ArrayList<>();
            this.timeOptionsSeconds = timeOptionsSeconds != null ? timeOptionsSeconds : new int[]{300};
        }

        public UUID getTargetId() {
            return targetId;
        }

        public String getSelectedModeId() {
            if (modeIds.isEmpty()) {
                return null;
            }
            int index = Math.floorMod(modeIndex, modeIds.size());
            return modeIds.get(index);
        }

        public String getSelectedArenaId() {
            if (arenaIds.isEmpty()) {
                return null;
            }
            int index = Math.floorMod(arenaIndex, arenaIds.size());
            return arenaIds.get(index);
        }

        public int getSelectedTimeSeconds() {
            if (timeOptionsSeconds.length == 0) {
                return 300;
            }
            int index = Math.floorMod(timeIndex, timeOptionsSeconds.length);
            return Math.max(60, timeOptionsSeconds[index]);
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
