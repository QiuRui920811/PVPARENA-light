package com.pvparena.manager;

import com.pvparena.model.Arena;
import com.pvparena.model.ArenaStatus;
import com.pvparena.model.Mode;
import com.pvparena.config.PluginSettings;
import com.pvparena.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class QueueManager {
    private final JavaPlugin plugin;
    private final ArenaManager arenaManager;
    private final ModeManager modeManager;
    private final PluginSettings settings;
    private final Map<String, Deque<UUID>> queues = new ConcurrentHashMap<>();
    private final Map<String, Object> queueLocks = new ConcurrentHashMap<>();
    private final Map<UUID, io.papermc.paper.threadedregions.scheduler.ScheduledTask> timeoutTasks = new ConcurrentHashMap<>();
    private final Map<UUID, io.papermc.paper.threadedregions.scheduler.ScheduledTask> botFallbackTasks = new ConcurrentHashMap<>();
    private MatchManager matchManager;

    public QueueManager(JavaPlugin plugin, ArenaManager arenaManager, ModeManager modeManager, PluginSettings settings) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.modeManager = modeManager;
        this.settings = settings;
    }

    public void setMatchManager(MatchManager matchManager) {
        this.matchManager = matchManager;
    }

    public boolean isQueued(Player player) {
        for (Deque<UUID> queue : queues.values()) {
            if (queue.contains(player.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    public void joinQueue(Player player, String modeId) {
        Mode mode = modeManager.getMode(modeId);
        if (mode == null) {
            MessageUtil.send(player, "mode_not_found");
            return;
        }
        if (matchManager.isInMatch(player)) {
            MessageUtil.send(player, "matching_in_match");
            return;
        }
        if (isQueued(player)) {
            MessageUtil.send(player, "queue_already");
            return;
        }
        Deque<UUID> queue = queues.computeIfAbsent(modeId, key -> new ConcurrentLinkedDeque<>());
        synchronized (lockFor(modeId)) {
            queue.add(player.getUniqueId());
        }
        MessageUtil.send(player, "queue_join", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("mode", mode.getDisplayName()));
        if (mode.getSettings().isBotEnabled() && mode.getSettings().isLegacyPvp() && matchManager.isBotEnabled()) {
            scheduleBotFallback(player, modeId, ticks(settings.getQueueBotFallbackSeconds())) ;
        } else {
            scheduleTimeout(player, modeId);
        }
        tryStartMatches(modeId);
    }

    public void leaveQueue(Player player) {
        for (Map.Entry<String, Deque<UUID>> entry : queues.entrySet()) {
            String modeId = entry.getKey();
            Deque<UUID> queue = entry.getValue();
            boolean removed;
            synchronized (lockFor(modeId)) {
                removed = queue.remove(player.getUniqueId());
            }
            if (removed) {
                MessageUtil.send(player, "queue_leave");
                cancelTimeout(player.getUniqueId());
                cancelBotFallback(player.getUniqueId());
                return;
            }
        }
        MessageUtil.send(player, "queue_not_in");
    }

    public int getQueueSize(String modeId) {
        Deque<UUID> queue = queues.get(modeId);
        return queue != null ? queue.size() : 0;
    }

    public void tryStartMatches(String modeId) {
        Deque<UUID> queue = queues.get(modeId);
        if (queue == null) {
            return;
        }
        Mode mode = modeManager.getMode(modeId);
        if (mode == null) {
            return;
        }
        synchronized (lockFor(modeId)) {
            while (queue.size() >= 2) {
                Arena arena = arenaManager.getFreeArena(mode);
                if (arena == null) {
                    return;
                }
                UUID p1 = queue.poll();
                UUID p2 = queue.poll();
                if (p1 == null || p2 == null) {
                    return;
                }
                if (matchManager == null) {
                    return;
                }
                arena.setStatus(ArenaStatus.IN_GAME);
                cancelTimeout(p1);
                cancelTimeout(p2);
                cancelBotFallback(p1);
                cancelBotFallback(p2);
                matchManager.startMatch(p1, p2, modeId, arena);
            }
        }
    }

    private void scheduleTimeout(Player player, String modeId) {
        cancelTimeout(player.getUniqueId());
        io.papermc.paper.threadedregions.scheduler.ScheduledTask task = plugin.getServer()
                .getGlobalRegionScheduler().runDelayed(plugin, t -> {
                    Deque<UUID> queue = queues.get(modeId);
                    if (queue != null) {
                        boolean removed;
                        synchronized (lockFor(modeId)) {
                            removed = queue.remove(player.getUniqueId());
                        }
                        if (removed) {
                            MessageUtil.send(player, "queue_timeout");
                        }
                    }
                }, ticks(settings.getQueueTimeoutSeconds()));
        timeoutTasks.put(player.getUniqueId(), task);
    }

    private void cancelTimeout(UUID playerId) {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask task = timeoutTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void scheduleBotFallback(Player player, String modeId, long delayTicks) {
        cancelBotFallback(player.getUniqueId());
        io.papermc.paper.threadedregions.scheduler.ScheduledTask task = plugin.getServer()
            .getGlobalRegionScheduler().runDelayed(plugin, t -> {
                Deque<UUID> queue = queues.get(modeId);
                if (queue == null) {
                    return;
                }
                synchronized (lockFor(modeId)) {
                    if (!queue.contains(player.getUniqueId())) {
                        return;
                    }
                    Mode mode = modeManager.getMode(modeId);
                    if (mode == null) {
                        return;
                    }
                    Arena arena = arenaManager.getFreeArena(mode);
                    if (arena == null) {
                        scheduleBotFallback(player, modeId, ticks(settings.getQueueBotRetrySeconds()));
                        return;
                    }
                    queue.remove(player.getUniqueId());
                    arena.setStatus(ArenaStatus.IN_GAME);
                    cancelTimeout(player.getUniqueId());
                    cancelBotFallback(player.getUniqueId());
                    matchManager.startBotMatch(player.getUniqueId(), modeId, arena);
                }
            }, delayTicks);
        botFallbackTasks.put(player.getUniqueId(), task);
    }

    private long ticks(int seconds) {
        return Math.max(1L, seconds * 20L);
    }

    private void cancelBotFallback(UUID playerId) {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask task = botFallbackTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private Object lockFor(String modeId) {
        return queueLocks.computeIfAbsent(modeId, key -> new Object());
    }
}
