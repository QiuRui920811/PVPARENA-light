package com.pvparena.gui;

import com.pvparena.manager.MessageManager;
import com.pvparena.manager.ModeManager;
import com.pvparena.manager.QueueManager;
import com.pvparena.model.Mode;
import com.pvparena.config.PluginSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class MainMenu {
    private final JavaPlugin plugin;
    private final ModeManager modeManager;
    private final QueueManager queueManager;
    private final MessageManager messageManager;
    private final NamespacedKey modeKey;
    private final PluginSettings settings;

    public static final String TITLE_KEY = "main_menu_title";

    public MainMenu(JavaPlugin plugin, MessageManager messageManager, ModeManager modeManager, QueueManager queueManager, PluginSettings settings) {
        this.plugin = plugin;
        this.messageManager = messageManager;
        this.modeManager = modeManager;
        this.queueManager = queueManager;
        this.settings = settings;
        this.modeKey = new NamespacedKey(plugin, "mode");
    }

    public void open(Player player) {
        int size = settings.getGuiMainSize();
        Inventory inventory = Bukkit.createInventory(null, size, messageManager.getUi(TITLE_KEY));
        populate(inventory, player);
        player.openInventory(inventory);
    }

    public void refreshOpenMenus() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!isMainMenuOpen(player)) {
                continue;
            }
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top == null || top.getSize() != settings.getGuiMainSize()) {
                continue;
            }
            populate(top, player);
            try {
                player.updateInventory();
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean isMainMenuOpen(Player player) {
        try {
            String title = PlainTextComponentSerializer.plainText().serialize(player.getOpenInventory().title());
            return title.equals(getPlainTitle());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void populate(Inventory inventory, Player viewer) {
        inventory.clear();
        int size = settings.getGuiMainSize();
        applyDecorations(inventory, settings.getGuiMainDecorations());
        int slot = settings.getGuiMainStartSlot();
        for (Mode mode : modeManager.getModes().values()) {
            ItemStack icon = new ItemStack(mode.getIcon() != null ? mode.getIcon() : Material.DIAMOND_SWORD);
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text(mode.getDisplayName(), NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            meta.setLocalizedName(mode.getId());
            meta.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, mode.getId());
            List<Component> lore = new ArrayList<>();
                lore.add(messageManager.getUi("main_menu_queue_count",
                    Placeholder.unparsed("count", String.valueOf(queueManager.getQueueSize(mode.getId())))));
            for (String line : mode.getLore()) {
                lore.add(LegacyComponentSerializer.legacySection().deserialize(line)
                        .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            meta.lore(lore);
            icon.setItemMeta(meta);
            Integer override = mode.getMainMenuSlot();
            if (override == null) {
                override = settings.getGuiMainModeSlot(mode.getId());
            }
            int targetSlot = override != null ? override : slot;
            if (targetSlot >= 0 && targetSlot < size) {
                inventory.setItem(targetSlot, icon);
            }
            slot += settings.getGuiMainStep();
        }
        if (queueManager.isQueued(viewer)) {
            ItemStack cancel = new ItemStack(Material.BARRIER);
            ItemMeta meta = cancel.getItemMeta();
            meta.displayName(messageManager.getUi("main_menu_cancel").decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            meta.setLocalizedName("queue_cancel");
            meta.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, "queue_cancel");
            cancel.setItemMeta(meta);
            int cancelSlot = settings.getGuiMainCancelSlot();
            if (cancelSlot < size) {
                inventory.setItem(cancelSlot, cancel);
            }
        }
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
                meta.displayName(LegacyComponentSerializer.legacySection().deserialize(def.getName())
                        .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            List<Component> lore = new ArrayList<>();
            for (String line : def.getLore()) {
                lore.add(LegacyComponentSerializer.legacySection().deserialize(line)
                        .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            if (!lore.isEmpty()) {
                meta.lore(lore);
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
}
