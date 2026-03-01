package com.pvparena.gui;

import com.pvparena.manager.MessageManager;
import com.pvparena.manager.ModeManager;
import com.pvparena.model.Mode;
import com.pvparena.config.PluginSettings;
import com.pvparena.util.GuiTextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DuelMenu {
    public static final String TITLE_KEY = "duel_menu_title";

    private final ModeManager modeManager;
    private final MessageManager messageManager;
    private final NamespacedKey modeKey;
    private final PluginSettings settings;

    public DuelMenu(JavaPlugin plugin, MessageManager messageManager, ModeManager modeManager, PluginSettings settings) {
        this.messageManager = messageManager;
        this.modeManager = modeManager;
        this.settings = settings;
        this.modeKey = new NamespacedKey(plugin, "duel_mode");
    }

    public void open(Player player, UUID targetId) {
        int size = settings.getGuiDuelSize();
        Inventory inventory = Bukkit.createInventory(new DuelMenuHolder(targetId), size,
                messageManager.getUi(TITLE_KEY));
        applyDecorations(inventory, settings.getGuiDuelDecorations());
        int slot = settings.getGuiDuelStartSlot();
        for (Mode mode : modeManager.getModes().values()) {
            ItemStack icon = new ItemStack(mode.getIcon() != null ? mode.getIcon() : Material.DIAMOND_SWORD);
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(GuiTextUtil.noItalic(Component.text(mode.getDisplayName(), NamedTextColor.AQUA)));
            meta.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, mode.getId());
            List<Component> lore = new ArrayList<>();
            for (String line : mode.getLore()) {
                lore.add(GuiTextUtil.noItalic(LegacyComponentSerializer.legacySection().deserialize(line)));
            }
            meta.lore(GuiTextUtil.noItalic(lore));
            icon.setItemMeta(meta);
            Integer override = mode.getDuelMenuSlot();
            int targetSlot = override != null ? override : slot;
            if (targetSlot >= 0 && targetSlot < size) {
                inventory.setItem(targetSlot, icon);
            }
            slot += settings.getGuiDuelStep();
        }
        player.openInventory(inventory);
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
                meta.displayName(GuiTextUtil.noItalic(LegacyComponentSerializer.legacySection().deserialize(def.getName())));
            }
            List<Component> lore = new ArrayList<>();
            for (String line : def.getLore()) {
                lore.add(GuiTextUtil.noItalic(LegacyComponentSerializer.legacySection().deserialize(line)));
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

    public NamespacedKey getModeKey() {
        return modeKey;
    }

    public static class DuelMenuHolder implements InventoryHolder {
        private final UUID targetId;

        public DuelMenuHolder(UUID targetId) {
            this.targetId = targetId;
        }

        public UUID getTargetId() {
            return targetId;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}