package com.pvparena.listener;

import com.pvparena.gui.KitEditorMenu;
import com.pvparena.manager.KitManager;
import com.pvparena.manager.ModeManager;
import com.pvparena.model.ModeKit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class KitEditorListener implements Listener {
    private static final int[] ARMOR_SLOTS = {45, 46, 47, 48};

    private final KitManager kitManager;
    private final ModeManager modeManager;

    public KitEditorListener(KitManager kitManager, ModeManager modeManager) {
        this.kitManager = kitManager;
        this.modeManager = modeManager;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof KitEditorMenu.KitEditorHolder holder)) {
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        List<ItemStack> armor = new ArrayList<>();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (isArmorSlot(slot)) {
                armor.add(item.clone());
            } else {
                items.add(item.clone());
            }
        }
        ModeKit kit = new ModeKit(items, armor, holder.getPotionEffects());
        kitManager.saveKit(holder.getKitId(), kit);
        modeManager.load();
        if (event.getPlayer() instanceof Player player) {
            com.pvparena.util.MessageUtil.send(player, "kit_saved_gui", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("kit", holder.getKitId()));
        }
    }

    private boolean isArmorSlot(int slot) {
        for (int armorSlot : ARMOR_SLOTS) {
            if (armorSlot == slot) {
                return true;
            }
        }
        return false;
    }
}
