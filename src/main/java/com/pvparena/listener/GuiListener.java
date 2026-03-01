package com.pvparena.listener;

import com.pvparena.gui.MainMenu;
import com.pvparena.manager.ModeManager;
import com.pvparena.manager.QueueManager;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public class GuiListener implements Listener {
    private final MainMenu mainMenu;
    private final QueueManager queueManager;
    private final ModeManager modeManager;
    private final NamespacedKey modeKey;

    public GuiListener(JavaPlugin plugin, MainMenu mainMenu, QueueManager queueManager, ModeManager modeManager) {
        this.mainMenu = mainMenu;
        this.queueManager = queueManager;
        this.modeManager = modeManager;
        this.modeKey = new NamespacedKey(plugin, "mode");
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.equals(mainMenu.getPlainTitle())) {
            return;
        }
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String key = meta.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING);
        if (key == null || key.isEmpty()) {
            key = meta.getLocalizedName();
        }
        if (key == null || key.isEmpty()) {
            return;
        }
        if (key.equals("queue_cancel")) {
            queueManager.leaveQueue(player);
            player.closeInventory();
            return;
        }
        if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT) {
            com.pvparena.util.MessageUtil.send(player, "bot_disabled");
            return;
        }
        queueManager.joinQueue(player, key);
        player.closeInventory();
    }
}
