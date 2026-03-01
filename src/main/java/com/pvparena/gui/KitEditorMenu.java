package com.pvparena.gui;

import com.pvparena.manager.KitManager;
import com.pvparena.model.ModeKit;
import com.pvparena.util.MessageUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class KitEditorMenu {
    private static final int[] ARMOR_SLOTS = {45, 46, 47, 48};

    private final JavaPlugin plugin;
    private final KitManager kitManager;

    public KitEditorMenu(JavaPlugin plugin, KitManager kitManager) {
        this.plugin = plugin;
        this.kitManager = kitManager;
    }

    public void open(Player player, String kitId) {
        ModeKit kit = kitManager.getKit(kitId);
        List<PotionEffect> effects = kit != null ? kit.getPotionEffects() : Collections.emptyList();
        KitEditorHolder holder = new KitEditorHolder(kitId.toLowerCase(), effects);
        Inventory inv = Bukkit.createInventory(holder, 54,
            MessageUtil.getPlainMessage("kit_editor_title", Placeholder.unparsed("kit", kitId)));

        if (kit != null) {
            int slot = 0;
            for (ItemStack item : kit.getItems()) {
                if (item == null || item.getType().isAir()) {
                    continue;
                }
                while (isArmorSlot(slot) && slot < inv.getSize()) {
                    slot++;
                }
                if (slot >= inv.getSize()) {
                    break;
                }
                inv.setItem(slot++, item.clone());
            }
            ItemStack[] armor = kit.getArmor().toArray(new ItemStack[0]);
            for (int i = 0; i < ARMOR_SLOTS.length && i < armor.length; i++) {
                ItemStack piece = armor[i];
                if (piece != null && !piece.getType().isAir()) {
                    inv.setItem(ARMOR_SLOTS[i], piece.clone());
                }
            }
        }

        plugin.getServer().getRegionScheduler().run(plugin, player.getLocation(), task -> player.openInventory(inv));
    }

    private boolean isArmorSlot(int slot) {
        for (int armorSlot : ARMOR_SLOTS) {
            if (armorSlot == slot) {
                return true;
            }
        }
        return false;
    }

    public static class KitEditorHolder implements InventoryHolder {
        private final String kitId;
        private final List<PotionEffect> potionEffects;

        public KitEditorHolder(String kitId, List<PotionEffect> potionEffects) {
            this.kitId = kitId;
            this.potionEffects = new ArrayList<>(potionEffects);
        }

        public String getKitId() {
            return kitId;
        }

        public List<PotionEffect> getPotionEffects() {
            return potionEffects;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
