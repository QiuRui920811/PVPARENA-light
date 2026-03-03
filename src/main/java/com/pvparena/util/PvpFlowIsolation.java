package com.pvparena.util;

import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class PvpFlowIsolation {
    public static final String META_KEY = "pvp_immune_advancements";
    private static final ConcurrentHashMap<UUID, AtomicInteger> ACTIVE_COUNTS = new ConcurrentHashMap<>();

    private PvpFlowIsolation() {
    }

    public static void begin(JavaPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        AtomicInteger count = ACTIVE_COUNTS.computeIfAbsent(player.getUniqueId(), id -> new AtomicInteger(0));
        int now = count.incrementAndGet();
        if (now == 1) {
            player.setMetadata(META_KEY, new FixedMetadataValue(plugin, true));
        }
    }

    public static void end(JavaPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        AtomicInteger count = ACTIVE_COUNTS.get(id);
        if (count == null) {
            player.removeMetadata(META_KEY, plugin);
            return;
        }
        int now = count.decrementAndGet();
        if (now <= 0) {
            ACTIVE_COUNTS.remove(id);
            player.removeMetadata(META_KEY, plugin);
        }
    }

    public static boolean isActive(Player player) {
        return player != null && player.hasMetadata(META_KEY);
    }
}