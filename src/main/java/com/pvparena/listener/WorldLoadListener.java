package com.pvparena.listener;

import com.pvparena.PvPArenaPlugin;
import com.pvparena.model.Arena;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

public class WorldLoadListener implements Listener {
    private final PvPArenaPlugin plugin;

    public WorldLoadListener(PvPArenaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (plugin == null || plugin.getArenaManager() == null) {
            return;
        }

        String loadedWorld = event.getWorld() != null ? event.getWorld().getName() : null;
        if (loadedWorld == null || loadedWorld.isEmpty()) {
            return;
        }

        boolean needsReload = false;
        for (Arena arena : plugin.getArenaManager().getArenas()) {
            String worldName = arena.getWorldName();
            if (worldName != null && worldName.equalsIgnoreCase(loadedWorld) && !arena.isReady()) {
                needsReload = true;
                break;
            }
        }

        if (needsReload) {
            plugin.getLogger().info("World '" + loadedWorld + "' loaded; reloading arenas to resolve deferred locations.");
            plugin.getArenaManager().load();
        }

        plugin.setupPvWorld();
    }
}
