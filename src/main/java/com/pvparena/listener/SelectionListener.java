package com.pvparena.listener;

import com.pvparena.config.PluginSettings;
import com.pvparena.manager.SelectionManager;
import com.pvparena.util.MessageUtil;
import com.pvparena.util.SelectionToolUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.block.Block;

public class SelectionListener implements Listener {
    private final JavaPlugin plugin;
    private final SelectionManager selectionManager;
    private final PluginSettings settings;

    public SelectionListener(JavaPlugin plugin, SelectionManager selectionManager, PluginSettings settings) {
        this.plugin = plugin;
        this.selectionManager = selectionManager;
        this.settings = settings;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("pvparena.admin")) {
            return;
        }
        if (!SelectionToolUtil.isSelectionTool(plugin, settings, event.getItem())) {
            return;
        }
        Action action = event.getAction();

        if (settings.isSelectionSneakLeftOpenAdminMenu()
                && player.isSneaking()
                && (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK)) {
            String cmd = settings.getSelectionSneakLeftCommand();
            if (cmd != null && !cmd.isBlank()) {
                player.performCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
            }
            event.setCancelled(true);
            return;
        }

        if (action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) {
            Location selected = resolveSelectionLocation(player, event);
            if (selected == null) {
                MessageUtil.send(player, "selection_required");
                event.setCancelled(true);
                return;
            }
            selectionManager.setPos1(player.getUniqueId(), selected);
            MessageUtil.send(player, "selection_point1_set",
                Placeholder.unparsed("x", String.valueOf(selected.getBlockX())),
                Placeholder.unparsed("y", String.valueOf(selected.getBlockY())),
                Placeholder.unparsed("z", String.valueOf(selected.getBlockZ())));
            event.setCancelled(true);
        } else if (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) {
            Location selected = resolveSelectionLocation(player, event);
            if (selected == null) {
                MessageUtil.send(player, "selection_required");
                event.setCancelled(true);
                return;
            }
            selectionManager.setPos2(player.getUniqueId(), selected);
            MessageUtil.send(player, "selection_point2_set",
                Placeholder.unparsed("x", String.valueOf(selected.getBlockX())),
                Placeholder.unparsed("y", String.valueOf(selected.getBlockY())),
                Placeholder.unparsed("z", String.valueOf(selected.getBlockZ())));
            event.setCancelled(true);
        }
    }

    private Location resolveSelectionLocation(Player player, PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked != null) {
            return clicked.getLocation();
        }
        Block target = player.getTargetBlockExact(12);
        return target != null ? target.getLocation() : null;
    }
}
