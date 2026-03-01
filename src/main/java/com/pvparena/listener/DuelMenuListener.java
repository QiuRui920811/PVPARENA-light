package com.pvparena.listener;

import com.pvparena.gui.DuelMenu;
import com.pvparena.manager.DuelManager;
import com.pvparena.util.MessageUtil;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public class DuelMenuListener implements Listener {
    private final DuelMenu duelMenu;
    private final DuelManager duelManager;

    public DuelMenuListener(DuelMenu duelMenu, DuelManager duelManager) {
        this.duelMenu = duelMenu;
        this.duelManager = duelManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.equals(duelMenu.getPlainTitle())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getInventory().getHolder() instanceof DuelMenu.DuelMenuHolder holder)) {
            return;
        }
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String modeId = meta.getPersistentDataContainer().get(duelMenu.getModeKey(), PersistentDataType.STRING);
        if (modeId == null || modeId.isEmpty()) {
            return;
        }
        UUID targetId = holder.getTargetId();
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            MessageUtil.send(player, "duel_not_found");
            player.closeInventory();
            return;
        }
        duelManager.request(player, target, modeId);
        player.closeInventory();
    }
}