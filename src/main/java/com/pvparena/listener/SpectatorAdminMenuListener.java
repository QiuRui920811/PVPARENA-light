package com.pvparena.listener;

import com.pvparena.PvPArenaPlugin;
import com.pvparena.gui.SpectatorAdminMenu;
import com.pvparena.util.MessageUtil;
import com.pvparena.util.SchedulerUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public class SpectatorAdminMenuListener implements Listener {
    private final PvPArenaPlugin plugin;
    private final SpectatorAdminMenu menu;

    public SpectatorAdminMenuListener(PvPArenaPlugin plugin, SpectatorAdminMenu menu) {
        this.plugin = plugin;
        this.menu = menu;
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

        if (!player.hasPermission("pvparena.admin")) {
            MessageUtil.send(player, "no_permission");
            return;
        }

        String key = menu.readSettingKey(event.getCurrentItem());
        String path = menu.toConfigPath(key);
        if (path == null) {
            return;
        }

        boolean current = plugin.getConfig().getBoolean(path, false);
        boolean updated = !current;
        plugin.getConfig().set(path, updated);
        plugin.saveConfig();
        plugin.reloadAll();

        MessageUtil.send(player, "spectator_setting_updated",
                Placeholder.unparsed("setting", key),
                Placeholder.unparsed("value", MessageUtil.getRaw(updated ? "spectator_state_on" : "spectator_state_off")));

        SchedulerUtil.runOnPlayer(plugin, player, () -> menu.open(player));
    }
}
