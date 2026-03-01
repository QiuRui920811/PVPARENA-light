package com.pvparena.listener;

import com.pvparena.config.PluginSettings;
import com.pvparena.hook.husksync.HuskSyncWriteBack;
import com.pvparena.manager.MatchManager;
import com.pvparena.manager.PkManager;
import com.pvparena.manager.QueueManager;
import com.pvparena.model.Arena;
import com.pvparena.util.SchedulerUtil;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class PlayerListener implements Listener {
    private static final long COMMAND_BLOCKED_MESSAGE_COOLDOWN_MS = 1000L;

    private final QueueManager queueManager;
    private final MatchManager matchManager;
    private final PkManager pkManager;
    private final PluginSettings settings;
    private final JavaPlugin plugin;
    private final Map<UUID, GameMode> pvEntryModes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> commandBlockedMessageAt = new ConcurrentHashMap<>();

    public PlayerListener(JavaPlugin plugin, QueueManager queueManager, MatchManager matchManager, PkManager pkManager, PluginSettings settings) {
        this.plugin = plugin;
        this.queueManager = queueManager;
        this.matchManager = matchManager;
        this.pkManager = pkManager;
        this.settings = settings;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!matchManager.isInMatch(player)) {
            queueManager.leaveQueue(player);
        }
        commandBlockedMessageAt.remove(player.getUniqueId());
        pvEntryModes.remove(player.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (matchManager.isAwaitingCrashResume(player.getUniqueId())) {
            debugRestore("onJoin awaiting crash-resume " + player.getUniqueId());
            if (shouldProtect(player)) {
                applyPvEntryLock(player);
                enforcePkEntryIfNeeded(player, player.getLocation());
            }
            return;
        }
        var snapshot = matchManager.peekPendingRestore(player);
        if (snapshot != null) {
            debugRestore("onJoin pending " + player.getUniqueId() + " " + snapshot.debugFingerprint());
            matchManager.blockExternalSync(player.getUniqueId());
            long[] delays = isPluginEnabled("HuskSync")
                    ? new long[]{5L, 40L, 80L, 160L, 240L}
                    : new long[]{2L, 40L, 80L};

            for (long delay : delays) {
                SchedulerUtil.runOnPlayerLater(plugin, player, delay, () -> {
                    debugRestore("join delayed check player=" + player.getUniqueId() + " delay=" + delay);
                    if (!player.isOnline()) {
                        debugRestore("join skip offline " + player.getUniqueId());
                        return;
                    }
                    if (matchManager.isInMatch(player)) {
                        debugRestore("join skip in-match " + player.getUniqueId());
                        return;
                    }
                    if (!matchManager.hasPendingRestore(player.getUniqueId())) {
                        debugRestore("join skip no-pending-now " + player.getUniqueId());
                        return;
                    }
                    matchManager.blockExternalSync(player.getUniqueId());
                    debugRestore("join apply restore " + player.getUniqueId() + " " + snapshot.debugFingerprint());
                    snapshot.restore(player);
                    HuskSyncWriteBack.writeBackCurrentState(plugin, player);

                    try {
                        player.updateInventory();
                    } catch (Throwable ignored) {
                    }

                    Location target = snapshot.getLocation();
                    Location fallback = getSafeFallbackSpawn();

                    Location destination = target;
                    if (destination == null || destination.getWorld() == null || isInsideAnyArena(destination)) {
                        destination = fallback;
                    }
                    if (destination == null || destination.getWorld() == null) {
                        return;
                    }

                    final Location finalDestination = destination;
                    player.teleportAsync(finalDestination).whenComplete((success, throwable) -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        debugRestore("join teleportAsync result player=" + player.getUniqueId()
                                + " success=" + success + " throwable=" + (throwable != null));
                        if (throwable == null && Boolean.TRUE.equals(success)) {
                            matchManager.consumePendingRestore(player);
                            restorePvEntryIfNeeded(player);
                            return;
                        }
                        SchedulerUtil.runOnPlayer(plugin, player, () -> {
                            if (!player.isOnline() || matchManager.isInMatch(player)) {
                                return;
                            }
                            if (!matchManager.hasPendingRestore(player.getUniqueId())) {
                                return;
                            }
                            boolean ok;
                            try {
                                ok = player.teleport(finalDestination);
                            } catch (Throwable ignored) {
                                ok = false;
                            }
                            if (ok) {
                                debugRestore("join sync-teleport success consume " + player.getUniqueId());
                                matchManager.consumePendingRestore(player);
                                restorePvEntryIfNeeded(player);
                            }
                        });
                    });
                });
            }

            SchedulerUtil.runOnPlayerLater(plugin, player, 120L, () -> {
                if (!player.isOnline() || matchManager.isInMatch(player)) {
                    return;
                }
                if (!matchManager.hasPendingRestore(player.getUniqueId())) {
                    return;
                }
                if (!shouldProtect(player)) {
                    return;
                }
                Location fallback = getSafeFallbackSpawn();
                if (fallback == null || fallback.getWorld() == null) {
                    return;
                }
                player.teleportAsync(fallback).whenComplete((success, throwable) -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    debugRestore("join failsafe teleportAsync result player=" + player.getUniqueId()
                            + " success=" + success + " throwable=" + (throwable != null));
                    if (throwable == null && Boolean.TRUE.equals(success)) {
                        matchManager.consumePendingRestore(player);
                        restorePvEntryIfNeeded(player);
                        return;
                    }
                    SchedulerUtil.runOnPlayer(plugin, player, () -> {
                        if (!player.isOnline() || matchManager.isInMatch(player)) {
                            return;
                        }
                        if (!matchManager.hasPendingRestore(player.getUniqueId())) {
                            return;
                        }
                        boolean ok;
                        try {
                            ok = player.teleport(fallback);
                        } catch (Throwable ignored) {
                            ok = false;
                        }
                        if (ok) {
                            debugRestore("join failsafe sync-teleport success consume " + player.getUniqueId());
                            matchManager.consumePendingRestore(player);
                            restorePvEntryIfNeeded(player);
                        }
                    });
                });
            });
        } else {
            debugRestore("onJoin no-pending " + player.getUniqueId());
            SchedulerUtil.runOnPlayerLater(plugin, player, 2L, () -> {
                if (isInsideAnyArena(player)) {
                    Location fallback = getSafeFallbackSpawn();
                    if (fallback != null && fallback.getWorld() != null) {
                        player.teleportAsync(fallback);
                    }
                }
            });
        }
        if (shouldProtect(player)) {
            applyPvEntryLock(player);
            enforcePkEntryIfNeeded(player, player.getLocation());
        }
    }

    private Location getSafeFallbackSpawn() {
        java.util.List<World> worlds = plugin.getServer().getWorlds();
        if (worlds.isEmpty()) {
            return null;
        }

        World overworld = plugin.getServer().getWorld("world");
        if (overworld != null) {
            if (settings.isProtectionArenaOnly() || !settings.isProtectedWorld(overworld.getName())) {
                return overworld.getSpawnLocation();
            }
        }

        for (World world : worlds) {
            if (world == null) {
                continue;
            }
            if (settings.isProtectionArenaOnly() || !settings.isProtectedWorld(world.getName())) {
                return world.getSpawnLocation();
            }
        }

        return worlds.get(0).getSpawnLocation();
    }

    private boolean isPluginEnabled(String name) {
        try {
            var other = plugin.getServer().getPluginManager().getPlugin(name);
            return other != null && other.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void debugRestore(String msg) {
        try {
            if (settings.isRestoreDebug()) {
                plugin.getLogger().info("[RestoreDebug][PlayerListener] " + msg);
            }
        } catch (Throwable ignored) {
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) {
            return;
        }
        if (matchManager.isInMatch(player)) {
            restorePvEntryIfNeeded(player);
            return;
        }
        if (!shouldProtect(player)) {
            restorePvEntryIfNeeded(player);
            return;
        }
        applyPvEntryLock(player);
        enforcePkEntryIfNeeded(player, event.getTo());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (matchManager.isInMatch(player)) {
            return;
        }
        if (event.getTo() == null || event.getTo().getWorld() == null) {
            return;
        }
        if (!shouldProtect(player)) {
            return;
        }
        if (shouldDenyWorldEntry(player, event.getTo())) {
            event.setCancelled(true);
            com.pvparena.util.MessageUtil.send(player, "pk_world_entry_blocked");
            return;
        }
        applyPvEntryLock(player);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (matchManager.isInMatch(player)) {
            return;
        }
        if (!shouldProtect(player)) {
            restorePvEntryIfNeeded(player);
            return;
        }
        applyPvEntryLock(player);
        enforcePkEntryIfNeeded(player, player.getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (shouldLockPvWorld(event.getPlayer()) && settings.isBlockInteract()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (shouldLockPvWorld(event.getPlayer()) && settings.isBlockEntityInteract()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (shouldLockPvWorld(event.getPlayer()) && settings.isBlockEntityInteract()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (shouldLockPvWorld(player) && settings.isBlockBreak()) {
            event.setCancelled(true);
            var block = event.getBlock();
            SchedulerUtil.runOnPlayerLater(plugin, player, 1L, () -> {
                if (!player.isOnline()) {
                    return;
                }
                try {
                    player.sendBlockChange(block.getLocation(), block.getBlockData());
                } catch (Throwable ignored) {
                }
            });
        }
    }

    @EventHandler(ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (shouldLockPvWorld(player) && settings.isBlockPlace()) {
            event.setCancelled(true);
            var block = event.getBlock();
            SchedulerUtil.runOnPlayerLater(plugin, player, 1L, () -> {
                if (!player.isOnline()) {
                    return;
                }
                try {
                    player.sendBlockChange(block.getLocation(), block.getBlockData());
                } catch (Throwable ignored) {
                }
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (shouldLockPvWorld(event.getPlayer()) && settings.isBlockDrop()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && shouldLockPvWorld(player) && settings.isBlockPickup()) {
            event.setCancelled(true);
        }
    }

    private boolean isInsideAnyArena(Player player) {
        if (player == null) {
            return false;
        }
        return isInsideAnyArena(player.getLocation());
    }

    private boolean isInsideAnyArena(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        for (Arena arena : matchManager.getArenaManager().getArenas()) {
            if (arena == null || arena.getMinBound() == null || arena.getMaxBound() == null) {
                continue;
            }
            if (arena.getWorldName() == null) {
                continue;
            }
            if (!arena.getWorldName().equalsIgnoreCase(location.getWorld().getName())) {
                continue;
            }
            if (arena.isInsideBounds(location)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInPvWorld(Player player) {
        return player.getWorld() != null
                && settings.isProtectedWorld(player.getWorld().getName());
    }

    private boolean shouldProtect(Player player) {
        if (matchManager.isSpectating(player)) {
            return false;
        }
        if (!settings.isProtectionEnabled()) {
            return false;
        }
        if (settings.isProtectionArenaOnly()) {
            return isInsideAnyArena(player);
        }
        return isInPvWorld(player);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String msg = event.getMessage().toLowerCase(Locale.ROOT);
        if (isAdminSetupCommand(player, msg)) {
            if (event.isCancelled()) {
                event.setCancelled(false);
            }
            return;
        }
        if (isDuelLeaveCommand(msg)) {
            if (event.isCancelled()) {
                event.setCancelled(false);
            }
            return;
        }
        if (player.isOp()) {
            return;
        }
        boolean inMatch = matchManager.isInMatch(player);
        boolean protect = shouldProtect(player);

        if (inMatch || protect) {
            if (!event.isCancelled()) {
                event.setCancelled(true);
            }
            sendCommandBlockedMessageIfDue(player);
            return;
        }
        if (!msg.startsWith("/back")) {
            return;
        }
        if (!player.isOp() && (matchManager.isInMatch(player)
                || shouldProtect(player)
                || matchManager.shouldBlockBack(player))) {
            event.setCancelled(true);
            com.pvparena.util.MessageUtil.send(player, "back_blocked");
        }
    }
    private boolean shouldLockPvWorld(Player player) {
        if (player == null || player.isOp() || player.hasPermission("pvparena.admin") || !settings.isProtectionEnabled()) {
            return false;
        }
        if (matchManager.isSpectating(player)) {
            return false;
        }
        if (matchManager.isInMatch(player)) {
            return false;
        }
        if (settings.isProtectionArenaOnly()) {
            return isInsideAnyArena(player);
        }
        return isInPvWorld(player);
    }

    private void applyPvEntryLock(Player player) {
        if (player == null || matchManager.isInMatch(player)) {
            return;
        }
        pvEntryModes.putIfAbsent(player.getUniqueId(), player.getGameMode());
        if (settings.isProtectionLockAdventure() && player.getGameMode() != GameMode.ADVENTURE) {
            player.setGameMode(GameMode.ADVENTURE);
        }
    }

    private void restorePvEntryIfNeeded(Player player) {
        if (player == null) {
            return;
        }
        if (shouldProtect(player)) {
            return;
        }
        GameMode previous = pvEntryModes.remove(player.getUniqueId());
        if (previous != null && player.getGameMode() != previous) {
            player.setGameMode(previous);
        }
    }

    private boolean shouldDenyWorldEntry(Player player, Location target) {
        if (player == null || target == null || target.getWorld() == null) {
            return false;
        }
        if (matchManager.isSpectating(player)) {
            return false;
        }
        if (!settings.isProtectionEnabled() || settings.isProtectionArenaOnly()) {
            return false;
        }
        if (!settings.isRequirePkToEnterWorld()) {
            return false;
        }
        if (player.isOp() || matchManager.isInMatch(player)) {
            return false;
        }
        if (!settings.isProtectedWorld(target.getWorld().getName())) {
            return false;
        }
        return !pkManager.isEnabled(player.getUniqueId());
    }

    private void enforcePkEntryIfNeeded(Player player, Location target) {
        if (!shouldDenyWorldEntry(player, target)) {
            return;
        }
        com.pvparena.util.MessageUtil.send(player, "pk_world_entry_blocked");
        Location fallback = getSafeFallbackSpawn();
        if (fallback != null && fallback.getWorld() != null) {
            player.teleportAsync(fallback);
        }
    }

    private void sendCommandBlockedMessageIfDue(Player player) {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = commandBlockedMessageAt.put(player.getUniqueId(), now);
        if (previous != null && (now - previous) < COMMAND_BLOCKED_MESSAGE_COOLDOWN_MS) {
            return;
        }
        com.pvparena.util.MessageUtil.send(player, "command_blocked");
    }

    private boolean isAdminSetupCommand(Player player, String message) {
        if (player == null || message == null) {
            return false;
        }
        if (!player.isOp() && !player.hasPermission("pvparena.admin")) {
            return false;
        }
        String command = extractCommandLabel(message);
        return Set.of("arena", "pvparena", "paparena").contains(command);
    }

    private String extractCommandLabel(String message) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int space = trimmed.indexOf(' ');
        String token = space >= 0 ? trimmed.substring(0, space) : trimmed;
        if (token.startsWith("/")) {
            token = token.substring(1);
        }
        int namespace = token.lastIndexOf(':');
        if (namespace >= 0 && namespace + 1 < token.length()) {
            token = token.substring(namespace + 1);
        }
        return token.toLowerCase(Locale.ROOT);
    }

    private boolean isDuelLeaveCommand(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isBlank()) {
            return false;
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 2) {
            return false;
        }
        String root = parts[0].toLowerCase(Locale.ROOT);
        int namespaced = root.lastIndexOf(':');
        if (namespaced >= 0 && namespaced + 1 < root.length()) {
            root = root.substring(namespaced + 1);
        }
        String sub = parts[1].toLowerCase(Locale.ROOT);
        return "duel".equals(root) && "leave".equals(sub);
    }

}
