package com.pvparena.hook.husksync;

import com.pvparena.manager.MatchManager;
import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.event.BukkitDataSaveEvent;
import net.william278.husksync.event.BukkitPreSyncEvent;
import net.william278.husksync.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.logging.Level;

public final class HuskSyncApiHook implements Listener {
    private final JavaPlugin plugin;
    private final MatchManager matchManager;
    @SuppressWarnings("unused")
    private final BukkitHuskSyncAPI huskSyncApi;

    public HuskSyncApiHook(JavaPlugin plugin, MatchManager matchManager) {
        this.plugin = plugin;
        this.matchManager = matchManager;
        this.huskSyncApi = BukkitHuskSyncAPI.getInstance();

        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("Hooked into HuskSync API (blocking match-time saves/syncs; unlocking players when blocked)");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHuskSyncDataSave(BukkitDataSaveEvent event) {
        try {
            User user = event.getUser();
            UUID uuid = user.getUuid();

            Player player = Bukkit.getPlayer(uuid);
            boolean block = (player != null && matchManager.isInMatch(player))
                    || matchManager.hasPendingRestore(uuid)
                    || matchManager.isExternalSyncBlocked(uuid);

            if (!block) {
                return;
            }

            event.setCancelled(true);
            // Important: HuskSync uses a player-lock while syncing/saving.
            // If we cancel, proactively unlock to avoid leaving the player stuck.
            event.getPlugin().unlockPlayer(uuid);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Error handling HuskSync BukkitDataSaveEvent", t);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHuskSyncPreSync(BukkitPreSyncEvent event) {
        try {
            UUID uuid = event.getUser().getUuid();

            Player player = Bukkit.getPlayer(uuid);
            boolean block = (player != null && matchManager.isInMatch(player))
                    || matchManager.hasPendingRestore(uuid)
                    || matchManager.isExternalSyncBlocked(uuid);

            if (!block) {
                return;
            }

            event.setCancelled(true);
            // If we cancel sync, ensure player isn't left locked.
            event.getPlugin().unlockPlayer(uuid);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Error handling HuskSync BukkitPreSyncEvent", t);
        }
    }
}
