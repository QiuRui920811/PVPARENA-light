package com.pvparena.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;

public class SchedulerUtil {
    public static void runOnPlayer(JavaPlugin plugin, Player player, Runnable action) {
        if (plugin == null || !plugin.isEnabled() || player == null || action == null) {
            return;
        }
        player.getScheduler().run(plugin, task -> action.run(), null);
    }

    public static CompletableFuture<Void> runOnPlayerFuture(JavaPlugin plugin, Player player, Runnable action) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (plugin == null || !plugin.isEnabled() || player == null || action == null) {
            future.completeExceptionally(new IllegalStateException("Plugin disabled or invalid player/action"));
            return future;
        }
        player.getScheduler().run(plugin, task -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable ex) {
                future.completeExceptionally(ex);
            }
        }, null);
        return future;
    }

    public static void runOnPlayerLater(JavaPlugin plugin, Player player, long delayTicks, Runnable action) {
        if (plugin == null || !plugin.isEnabled() || player == null || action == null) {
            return;
        }
        player.getScheduler().runDelayed(plugin, task -> action.run(), null, delayTicks);
    }

    public static CompletableFuture<Void> teleport(JavaPlugin plugin, Player player, Location location) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (plugin == null || !plugin.isEnabled() || player == null || location == null) {
            future.completeExceptionally(new IllegalStateException("Plugin disabled or invalid player/location"));
            return future;
        }
        player.getScheduler().run(plugin, task -> player.teleportAsync(location)
                .thenRun(() -> future.complete(null))
                .exceptionally(ex -> {
                    future.completeExceptionally(ex);
                    return null;
                }), null);
        return future;
    }
}
