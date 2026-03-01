package com.pvparena.listener;

import com.pvparena.gui.BotDifficultyMenu;
import com.pvparena.manager.MatchManager;
import com.pvparena.model.BotDifficulty;
import com.pvparena.util.MessageUtil;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class BotMenuListener implements Listener {
    private final BotDifficultyMenu menu;
    private final MatchManager matchManager;

    public BotMenuListener(BotDifficultyMenu menu, MatchManager matchManager) {
        this.menu = menu;
        this.matchManager = matchManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.equals(menu.getPlainTitle())) {
            return;
        }
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String diffId = meta.getPersistentDataContainer().get(menu.getDifficultyKey(), PersistentDataType.STRING);
        if (diffId == null || diffId.isEmpty()) {
            return;
        }
        BotDifficulty difficulty = BotDifficulty.fromId(diffId);
        matchManager.setBotDifficulty(player.getUniqueId(), difficulty);
        String modeId = menu.consumePendingMode(player.getUniqueId());
        if (modeId == null || modeId.isEmpty()) {
            MessageUtil.send(player, "bot_no_mode");
            player.closeInventory();
            return;
        }
        matchManager.startBotMatchNow(player.getUniqueId(), modeId);
        MessageUtil.send(player, "bot_difficulty_set");
        player.closeInventory();
    }
}
