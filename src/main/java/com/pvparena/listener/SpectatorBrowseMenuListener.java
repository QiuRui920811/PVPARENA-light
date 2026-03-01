package com.pvparena.listener;

import com.pvparena.gui.SpectatorBrowseMenu;
import com.pvparena.manager.SpectatorManager;
import com.pvparena.util.MessageUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.UUID;

public class SpectatorBrowseMenuListener implements Listener {
    private final SpectatorBrowseMenu menu;
    private final SpectatorManager spectatorManager;

    public SpectatorBrowseMenuListener(SpectatorBrowseMenu menu, SpectatorManager spectatorManager) {
        this.menu = menu;
        this.spectatorManager = spectatorManager;
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
        String action = menu.readAction(event.getCurrentItem());
        if (action == null || action.isBlank()) {
            return;
        }

        if (action.startsWith("prev:") || action.startsWith("next:")) {
            String[] parts = action.split(":", 2);
            if (parts.length < 2) {
                return;
            }
            try {
                int page = Integer.parseInt(parts[1]);
                menu.open(player, page);
            } catch (NumberFormatException ignored) {
            }
            return;
        }

        if (action.equals("close")) {
            player.closeInventory();
            return;
        }

        if (action.equals("spectate")) {
            UUID targetId = menu.readTarget(event.getCurrentItem());
            if (targetId == null) {
                return;
            }
            Player target = player.getServer().getPlayer(targetId);
            if (target == null || !target.isOnline()) {
                MessageUtil.send(player, "spectator_target_not_in_match", Placeholder.unparsed("target", "unknown"));
                return;
            }
            if (spectatorManager.enterSpectator(player, target)) {
                player.closeInventory();
            }
        }
    }
}
