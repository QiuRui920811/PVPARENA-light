package com.pvparena.manager;

import com.pvparena.PvPArenaPlugin;
import com.pvparena.model.Arena;
import com.pvparena.model.ArenaStatus;
import com.pvparena.model.BotDifficulty;
import com.pvparena.model.BotMatchSession;
import com.pvparena.model.MatchSession;
import com.pvparena.model.Mode;
import com.pvparena.util.MessageUtil;
import com.pvparena.util.SchedulerUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MatchManager {
    public static final String REASON_PLAYER_DEATH = "reason_player_death";
    public static final String REASON_PLAYER_OFFLINE = "reason_player_offline";
    public static final String REASON_ARENA_SPAWN_UNSET = "reason_arena_spawn_unset";
    public static final String REASON_TELEPORT_FAILED = "reason_teleport_failed";
    public static final String REASON_BOT_DOWN = "reason_bot_down";
    public static final String REASON_BOT_SPAWN_FAILED = "reason_bot_spawn_failed";

    private final JavaPlugin plugin;
    private final ArenaManager arenaManager;
    private final QueueManager queueManager;
    private final ModeManager modeManager;
    private final PendingRestoreManager pendingRestoreManager;
    private final MatchCrashRecoveryManager crashRecoveryManager;
    private final PkManager pkManager;
    private SpectatorManager spectatorManager;
    private final Map<UUID, MatchSession> activeMatches = new ConcurrentHashMap<>();
    private final Map<UUID, BotMatchSession> botMatchesByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, BotMatchSession> botMatchesByBot = new ConcurrentHashMap<>();
    private final Map<UUID, BotDifficulty> botDifficultyByPlayer = new ConcurrentHashMap<>();
    private final boolean botEnabled = false;
    private static final int MAX_LAST_RESULTS = 2048;
    private static final int MAX_BACK_BLOCK_ENTRIES = 4096;
    private static final int MAX_EXTERNAL_SYNC_BLOCK_ENTRIES = 8192;
    private static final long DEFAULT_EXTERNAL_SYNC_BLOCK_MILLIS = 60_000L;

    private final LinkedHashMap<UUID, com.pvparena.model.MatchResult> lastResults = new LinkedHashMap<>(16, 0.75f, false);
    private final LinkedHashMap<UUID, Long> backBlockUntil = new LinkedHashMap<>(16, 0.75f, false);
    private final Map<UUID, Long> externalSyncBlockedUntil = new ConcurrentHashMap<>();
    private final Map<String, MatchCrashRecoveryManager.RecoveredMatch> pendingRecoveredMatches = new ConcurrentHashMap<>();
    private final Map<String, PendingWinnerLeave> pendingWinnerLeaves = new ConcurrentHashMap<>();
    private static final long BACK_BLOCK_MILLIS = 60_000L;

    public MatchManager(JavaPlugin plugin, ArenaManager arenaManager, QueueManager queueManager, ModeManager modeManager,
                        PendingRestoreManager pendingRestoreManager, PkManager pkManager,
                        MatchCrashRecoveryManager crashRecoveryManager) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.queueManager = queueManager;
        this.modeManager = modeManager;
        this.pendingRestoreManager = pendingRestoreManager;
        this.crashRecoveryManager = crashRecoveryManager;
        this.pkManager = pkManager;
        this.queueManager.setMatchManager(this);
    }

    public void bootstrapCrashRecovery() {
        if (crashRecoveryManager == null) {
            return;
        }
        crashRecoveryManager.recoverPendingRollbacksAsync();
        List<MatchCrashRecoveryManager.RecoveredMatch> recovered = crashRecoveryManager.loadActiveMatches();
        if (!recovered.isEmpty()) {
            for (MatchCrashRecoveryManager.RecoveredMatch match : recovered) {
                pendingRecoveredMatches.put(match.sessionId(), match);
            }
            plugin.getLogger().info("Loaded " + recovered.size() + " unfinished match(es) for crash recovery.");
        }
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tryResumeRecoveredMatches(), 40L, 60L);
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (crashRecoveryManager != null) {
                crashRecoveryManager.recoverPendingRollbacksAsync();
            }
        }, 200L, 600L);
    }

    private void tryResumeRecoveredMatches() {
        if (pendingRecoveredMatches.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, MatchCrashRecoveryManager.RecoveredMatch>> it = pendingRecoveredMatches.entrySet().iterator();
        while (it.hasNext()) {
            MatchCrashRecoveryManager.RecoveredMatch recovered = it.next().getValue();
            Player p1 = plugin.getServer().getPlayer(recovered.playerA());
            Player p2 = plugin.getServer().getPlayer(recovered.playerB());
            if (p1 == null || p2 == null || !p1.isOnline() || !p2.isOnline()) {
                continue;
            }
            if (isInMatch(p1) || isInMatch(p2)) {
                continue;
            }
            Mode mode = modeManager.getMode(recovered.modeId());
            if (mode == null) {
                crashRecoveryManager.removeActiveMatch(recovered.sessionId());
                it.remove();
                continue;
            }
            Arena arena = arenaManager.getArena(recovered.arenaId());
            if (arena == null || !arena.isReady() || arena.getStatus() != ArenaStatus.FREE) {
                continue;
            }
            queueManager.leaveQueue(p1);
            queueManager.leaveQueue(p2);
            arena.setStatus(ArenaStatus.IN_GAME);
            startRecoveredMatch(recovered, arena);
            crashRecoveryManager.removeActiveMatch(recovered.sessionId());
            it.remove();
        }
    }

    private void startRecoveredMatch(MatchCrashRecoveryManager.RecoveredMatch recovered, Arena arena) {
        if (recovered == null || arena == null) {
            return;
        }
        Player player1 = plugin.getServer().getPlayer(recovered.playerA());
        Player player2 = plugin.getServer().getPlayer(recovered.playerB());
        if (player1 == null || player2 == null) {
            arena.setStatus(ArenaStatus.FREE);
            return;
        }
        Mode mode = modeManager.getMode(recovered.modeId());
        if (mode == null) {
            arena.setStatus(ArenaStatus.FREE);
            return;
        }
        clearEliminatedSpectator(recovered.playerA());
        clearEliminatedSpectator(recovered.playerB());
        MatchSession session = new MatchSession(plugin, this, recovered.playerA(), recovered.playerB(), mode, arena);
        session.applyRecoveredProgress(recovered.currentRound(), recovered.roundsWonA(), recovered.roundsWonB());
        if (crashRecoveryManager != null) {
            crashRecoveryManager.registerActiveMatch(session.getRecoverySessionId(), recovered.playerA(), recovered.playerB(), recovered.modeId(), arena.getId(),
                    recovered.currentRound(), recovered.roundsWonA(), recovered.roundsWonB());
        }
        activeMatches.put(recovered.playerA(), session);
        activeMatches.put(recovered.playerB(), session);
        MessageUtil.send(player1, "matched_mode", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("mode", mode.getDisplayName()));
        MessageUtil.send(player2, "matched_mode", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("mode", mode.getDisplayName()));
        session.start();
    }

    public boolean isAwaitingCrashResume(UUID playerId) {
        if (playerId == null || pendingRecoveredMatches.isEmpty()) {
            return false;
        }
        for (MatchCrashRecoveryManager.RecoveredMatch recovered : pendingRecoveredMatches.values()) {
            if (playerId.equals(recovered.playerA()) || playerId.equals(recovered.playerB())) {
                return true;
            }
        }
        return false;
    }

    public boolean isInMatch(Player player) {
        return activeMatches.containsKey(player.getUniqueId())
            || botMatchesByPlayer.containsKey(player.getUniqueId());
    }

    public boolean isSpectating(Player player) {
        return player != null && spectatorManager != null && spectatorManager.isSpectating(player);
    }

    public MatchSession getMatch(Player player) {
        return activeMatches.get(player.getUniqueId());
    }

    public BotMatchSession getBotMatch(Player player) {
        return botMatchesByPlayer.get(player.getUniqueId());
    }

    public BotMatchSession getBotMatchByEntity(LivingEntity entity) {
        if (entity == null) {
            return null;
        }
        return botMatchesByBot.get(entity.getUniqueId());
    }

    public BotDifficulty getBotDifficulty(UUID playerId) {
        return botDifficultyByPlayer.getOrDefault(playerId, BotDifficulty.NORMAL);
    }

    public boolean isBotEnabled() {
        return botEnabled;
    }

    public void setBotDifficulty(UUID playerId, BotDifficulty difficulty) {
        if (playerId == null || difficulty == null) {
            return;
        }
        botDifficultyByPlayer.put(playerId, difficulty);
    }

    public void startMatch(UUID p1, UUID p2, String modeId, Arena arena) {
        Player player1 = plugin.getServer().getPlayer(p1);
        Player player2 = plugin.getServer().getPlayer(p2);
        if (player1 == null || player2 == null) {
            debugCombat("startMatch abort offline p1=" + p1 + " p2=" + p2 + " mode=" + modeId);
            arena.setStatus(ArenaStatus.FREE);
            return;
        }
        Mode mode = modeManager.getMode(modeId);
        if (mode == null) {
            debugCombat("startMatch abort mode-null mode=" + modeId + " p1=" + player1.getName() + " p2=" + player2.getName());
            arena.setStatus(ArenaStatus.FREE);
            return;
        }
        clearEliminatedSpectator(p1);
        clearEliminatedSpectator(p2);
        debugCombat("startMatch begin mode=" + mode.getId()
                + " arena=" + (arena != null ? arena.getId() : "null")
                + " p1=" + player1.getName()
                + " p2=" + player2.getName());
        MatchSession session = new MatchSession(plugin, this, p1, p2, mode, arena);
        if (crashRecoveryManager != null) {
            crashRecoveryManager.registerActiveMatch(session.getRecoverySessionId(), p1, p2, modeId, arena.getId());
        }
        activeMatches.put(p1, session);
        activeMatches.put(p2, session);
        debugCombat("startMatch mapped p1In=" + (activeMatches.get(p1) != null)
                + " p2In=" + (activeMatches.get(p2) != null)
                + " activeSize=" + activeMatches.size());
        MessageUtil.send(player1, "matched_mode", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("mode", mode.getDisplayName()));
        MessageUtil.send(player2, "matched_mode", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("mode", mode.getDisplayName()));
        session.start();
    }

    public void startBotMatch(UUID playerId, String modeId, Arena arena) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            arena.setStatus(ArenaStatus.FREE);
            return;
        }
        if (!botEnabled) {
            MessageUtil.send(player, "bot_disabled");
            arena.setStatus(ArenaStatus.FREE);
            return;
        }
        Mode mode = modeManager.getMode(modeId);
        if (mode == null) {
            arena.setStatus(ArenaStatus.FREE);
            return;
        }
        clearEliminatedSpectator(playerId);
        BotDifficulty difficulty = getBotDifficulty(playerId);
        BotMatchSession session = new BotMatchSession(plugin, this, playerId, mode, arena, difficulty);
        botMatchesByPlayer.put(playerId, session);
        MessageUtil.send(player, "matched_mode", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("mode", mode.getDisplayName()));
        session.start();
    }

    public boolean startBotMatchNow(UUID playerId, String modeId) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            return false;
        }
        if (!botEnabled) {
            MessageUtil.send(player, "bot_disabled");
            return false;
        }
        if (isInMatch(player)) {
            MessageUtil.send(player, "matching_in_match");
            return false;
        }
        Mode mode = modeManager.getMode(modeId);
        if (mode == null) {
            MessageUtil.send(player, "mode_not_found");
            return false;
        }
        if (!mode.getSettings().isLegacyPvp()) {
            MessageUtil.send(player, "bot_only_legacy");
            return false;
        }
        if (!mode.getSettings().isBotEnabled()) {
            MessageUtil.send(player, "bot_not_enabled");
            return false;
        }
        Arena arena = arenaManager.getFreeArena();
        if (arena == null) {
            MessageUtil.send(player, "arena_not_available");
            return false;
        }
        arena.setStatus(ArenaStatus.IN_GAME);
        queueManager.leaveQueue(player);
        startBotMatch(playerId, modeId, arena);
        return true;
    }

    public void addPendingSnapshot(UUID playerId, com.pvparena.model.PlayerSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        debugRestore("addPendingSnapshot " + playerId + " " + snapshot.debugFingerprint());
        pendingRestoreManager.addIfAbsent(playerId, snapshot);
    }

    public void addOrReplacePendingSnapshot(UUID playerId, com.pvparena.model.PlayerSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        debugRestore("addOrReplacePendingSnapshot " + playerId + " " + snapshot.debugFingerprint());
        pendingRestoreManager.add(playerId, snapshot);
    }

    public void endMatch(MatchSession session, UUID winner, String reason) {
        endMatch(session, winner, reason, null);
    }

    public void onMatchStarted(MatchSession session) {
        BigDoorManager doorManager = getDoorManager();
        if (doorManager != null && session != null) {
            doorManager.openArenaDoors(session.getArena());
        }
        runHookCommands("hooks.match-start-commands", session, null);
    }

    public void onArenaFightStarted(Arena arena) {
        BigDoorManager doorManager = getDoorManager();
        if (doorManager != null && arena != null) {
            doorManager.openArenaDoors(arena);
        }
    }

    public void enterRoundEliminatedSpectator(UUID spectatorId, UUID targetId) {
        if (spectatorManager == null || spectatorId == null || targetId == null) {
            return;
        }
        tryEnterRoundEliminatedSpectator(spectatorId, targetId);

        long[] delays = new long[]{1L, 5L, 10L, 20L, 40L};
        for (long delay : delays) {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task ->
                    tryEnterRoundEliminatedSpectator(spectatorId, targetId), delay);
        }
    }

    private void tryEnterRoundEliminatedSpectator(UUID spectatorId, UUID targetId) {
        Player spectator = plugin.getServer().getPlayer(spectatorId);
        Player target = plugin.getServer().getPlayer(targetId);
        if (spectator == null || target == null || !spectator.isOnline() || !target.isOnline()) {
            return;
        }
        if (spectatorManager.isSpectating(spectator)) {
            return;
        }
        if (!isInMatch(spectator) || !isInMatch(target)) {
            return;
        }
        MatchSession spectatorSession = getMatch(spectator);
        MatchSession targetSession = getMatch(target);
        if (spectatorSession == null || targetSession == null || spectatorSession != targetSession) {
            return;
        }
        if (!spectatorSession.isRoundResolving()) {
            return;
        }
        spectatorManager.enterEliminatedSpectator(spectator, target);
    }

    public void clearEliminatedSpectator(UUID playerId) {
        if (spectatorManager == null || playerId == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!spectatorManager.isSpectating(player)) {
            return;
        }
        spectatorManager.leaveSpectator(player);
    }

    public void endMatch(MatchSession session, UUID winner, String reason, UUID quitterId) {
        int winnerLeaveDelay = 0;
        if (winner != null && session != null && session.getMode() != null && session.getMode().getSettings() != null) {
            winnerLeaveDelay = Math.max(0, session.getMode().getSettings().getWinnerLeaveDelaySeconds());
        }
        if (winner != null && winnerLeaveDelay > 0 && session.beginWinnerLeaveCountdown()) {
            String sessionId = session.getRecoverySessionId();
            UUID loser = session.getOpponent(winner);
            prepareWinnerLootPhase(winner, loser);
            UUID winnerId = winner;
            UUID loserId = loser;
            AtomicInteger remain = new AtomicInteger(winnerLeaveDelay);
            io.papermc.paper.threadedregions.scheduler.ScheduledTask countdownTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
                int sec = remain.getAndDecrement();
                Player winnerPlayer = plugin.getServer().getPlayer(winnerId);
                if (sec <= 0) {
                    task.cancel();
                    pendingWinnerLeaves.remove(sessionId);
                    finalizeEndMatch(session, winner, reason, quitterId);
                    return;
                }
                enforceWinnerLootPhaseState(winnerId, loserId);
                if (winnerPlayer != null && winnerPlayer.isOnline()) {
                    SchedulerUtil.runOnPlayer(plugin, winnerPlayer, () -> winnerPlayer.sendActionBar(
                            MessageUtil.message("winner_leave_countdown",
                                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("seconds", String.valueOf(sec)))));
                }
            }, 1L, 20L);
            pendingWinnerLeaves.put(sessionId, new PendingWinnerLeave(winnerId, reason, quitterId, countdownTask));
            return;
        }
        finalizeEndMatch(session, winner, reason, quitterId);
    }

    public boolean leaveWinnerLootPhase(Player player) {
        if (player == null) {
            return false;
        }
        MatchSession session = getMatch(player);
        if (session == null) {
            return false;
        }
        String sessionId = session.getRecoverySessionId();
        PendingWinnerLeave pending = pendingWinnerLeaves.get(sessionId);
        if (pending == null) {
            return false;
        }

        pendingWinnerLeaves.remove(sessionId);
        if (pending.task() != null) {
            pending.task().cancel();
        }
        finalizeEndMatch(session, pending.winnerId(), pending.reason(), pending.quitterId());
        return true;
    }

    private void prepareWinnerLootPhase(UUID winnerId, UUID loserId) {
        if (winnerId != null) {
            Player winner = plugin.getServer().getPlayer(winnerId);
            if (winner != null && winner.isOnline()) {
                SchedulerUtil.runOnPlayer(plugin, winner, () -> {
                    if (!winner.isOnline()) {
                        return;
                    }
                    try {
                        winner.setGameMode(GameMode.SURVIVAL);
                    } catch (Throwable ignored) {
                    }
                    try {
                        winner.setInvulnerable(false);
                    } catch (Throwable ignored) {
                    }
                    try {
                        winner.setInvisible(false);
                    } catch (Throwable ignored) {
                    }
                    try {
                        winner.setCollidable(true);
                    } catch (Throwable ignored) {
                    }
                    try {
                        winner.setCanPickupItems(true);
                    } catch (Throwable ignored) {
                    }
                });
            }
        }

        if (loserId != null) {
            if (winnerId != null) {
                enterRoundEliminatedSpectator(loserId, winnerId);
            }
            Player loser = plugin.getServer().getPlayer(loserId);
            if (loser != null && loser.isOnline()) {
                SchedulerUtil.runOnPlayer(plugin, loser, () -> {
                    if (!loser.isOnline()) {
                        return;
                    }
                    try {
                        loser.setGameMode(GameMode.ADVENTURE);
                    } catch (Throwable ignored) {
                    }
                    try {
                        loser.setInvisible(true);
                    } catch (Throwable ignored) {
                    }
                    try {
                        loser.setInvulnerable(true);
                    } catch (Throwable ignored) {
                    }
                    try {
                        loser.setCollidable(false);
                    } catch (Throwable ignored) {
                    }
                    try {
                        loser.setCanPickupItems(false);
                    } catch (Throwable ignored) {
                    }
                    try {
                        loser.setAllowFlight(true);
                        loser.setFlying(true);
                    } catch (Throwable ignored) {
                    }
                });
            }
        }
    }

    private void enforceWinnerLootPhaseState(UUID winnerId, UUID loserId) {
        if (winnerId != null) {
            Player winner = plugin.getServer().getPlayer(winnerId);
            if (winner != null && winner.isOnline()) {
                SchedulerUtil.runOnPlayer(plugin, winner, () -> {
                    if (!winner.isOnline()) {
                        return;
                    }
                    try {
                        winner.setGameMode(GameMode.SURVIVAL);
                    } catch (Throwable ignored) {
                    }
                    try {
                        winner.setInvulnerable(false);
                    } catch (Throwable ignored) {
                    }
                    try {
                        winner.setInvisible(false);
                    } catch (Throwable ignored) {
                    }
                    try {
                        winner.setCollidable(true);
                    } catch (Throwable ignored) {
                    }
                    try {
                        winner.setCanPickupItems(true);
                    } catch (Throwable ignored) {
                    }
                });
            }
        }

        if (loserId != null) {
            Player loser = plugin.getServer().getPlayer(loserId);
            if (loser != null && loser.isOnline()) {
                SchedulerUtil.runOnPlayer(plugin, loser, () -> {
                    if (!loser.isOnline()) {
                        return;
                    }
                    try {
                        loser.setGameMode(GameMode.ADVENTURE);
                    } catch (Throwable ignored) {
                    }
                    try {
                        loser.setInvisible(true);
                    } catch (Throwable ignored) {
                    }
                    try {
                        loser.setInvulnerable(true);
                    } catch (Throwable ignored) {
                    }
                    try {
                        loser.setCollidable(false);
                    } catch (Throwable ignored) {
                    }
                    try {
                        loser.setCanPickupItems(false);
                    } catch (Throwable ignored) {
                    }
                    try {
                        loser.setAllowFlight(true);
                        loser.setFlying(true);
                    } catch (Throwable ignored) {
                    }
                });
            }
        }
    }

    private void finalizeEndMatch(MatchSession session, UUID winner, String reason, UUID quitterId) {
        if (session == null) {
            return;
        }
        if (activeMatches.get(session.getPlayerA()) != session && activeMatches.get(session.getPlayerB()) != session) {
            return;
        }
        PendingWinnerLeave pending = pendingWinnerLeaves.remove(session.getRecoverySessionId());
        if (pending != null && pending.task() != null) {
            pending.task().cancel();
        }
        debugCombat("endMatch begin reason=" + reason
                + " winner=" + winner
                + " p1=" + session.getPlayerA()
                + " p2=" + session.getPlayerB()
                + " activeSizeBefore=" + activeMatches.size());
        debugRestore("endMatch winner=" + winner + " reason=" + reason + " quitter=" + quitterId
            + " p1=" + session.getPlayerA() + " p2=" + session.getPlayerB());
        blockExternalSync(session.getPlayerA(), 300_000L);
        blockExternalSync(session.getPlayerB(), 300_000L);

        activeMatches.remove(session.getPlayerA());
        activeMatches.remove(session.getPlayerB());
        if (crashRecoveryManager != null) {
            crashRecoveryManager.removeActiveMatch(session.getRecoverySessionId());
        }
        debugCombat("endMatch removed p1In=" + (activeMatches.get(session.getPlayerA()) != null)
            + " p2In=" + (activeMatches.get(session.getPlayerB()) != null)
            + " activeSizeAfter=" + activeMatches.size());
        session.rollbackArenaChangesAsync().whenComplete((rv, rex) -> {
            if (rex != null) {
                plugin.getLogger().warning("Rollback completion error: " + rex.getMessage());
            } else if (crashRecoveryManager != null) {
                crashRecoveryManager.clearRollbackBlocks(session.getRecoverySessionId());
            }
            handlePendingRestoreFor(session, session.getPlayerA(), quitterId);
            handlePendingRestoreFor(session, session.getPlayerB(), quitterId);
            session.getArena().setStatus(ArenaStatus.FREE);
            clearArenaDrops(session.getArena());
            storeResult(session, winner, reason);
            sendResultSummary(session);
            synchronized (backBlockUntil) {
                backBlockUntil.put(session.getPlayerA(), System.currentTimeMillis() + BACK_BLOCK_MILLIS);
                backBlockUntil.put(session.getPlayerB(), System.currentTimeMillis() + BACK_BLOCK_MILLIS);
                trimCache(backBlockUntil, MAX_BACK_BLOCK_ENTRIES);
            }
            pkManager.restore(session.getPlayerA());
            pkManager.restore(session.getPlayerB());
            session.end(winner, reason);
            if (spectatorManager != null) {
                spectatorManager.handleMatchEnded(session);
            }
            BigDoorManager doorManager = getDoorManager();
            if (doorManager != null) {
                doorManager.closeArenaDoorsWithDelay(session.getArena());
            }
            runHookCommands("hooks.match-end-commands", session, winner);
            queueManager.tryStartMatches(session.getMode().getId());
        });
    }

    private void debugCombat(String msg) {
        try {
            if (plugin.getConfig().getBoolean("debug.combat", false)) {
                plugin.getLogger().info("[CombatDebug][MatchManager] " + msg);
            }
        } catch (Throwable ignored) {
        }
    }

    private BigDoorManager getDoorManager() {
        if (plugin instanceof PvPArenaPlugin arenaPlugin) {
            return arenaPlugin.getBigDoorManager();
        }
        return null;
    }

    private void runHookCommands(String path, MatchSession session, UUID winnerId) {
        if (session == null) {
            return;
        }
        List<String> commands = plugin.getConfig().getStringList(path);
        if (commands == null || commands.isEmpty()) {
            return;
        }

        String playerAName = plugin.getServer().getOfflinePlayer(session.getPlayerA()).getName();
        String playerBName = plugin.getServer().getOfflinePlayer(session.getPlayerB()).getName();
        if (playerAName == null || playerAName.isBlank()) {
            playerAName = session.getPlayerA().toString();
        }
        if (playerBName == null || playerBName.isBlank()) {
            playerBName = session.getPlayerB().toString();
        }

        String winnerName = "-";
        String loserName = "-";
        if (winnerId != null) {
            String resolved = plugin.getServer().getOfflinePlayer(winnerId).getName();
            winnerName = (resolved == null || resolved.isBlank()) ? winnerId.toString() : resolved;
            UUID loserId = session.getOpponent(winnerId);
            if (loserId != null) {
                String loserResolved = plugin.getServer().getOfflinePlayer(loserId).getName();
                loserName = (loserResolved == null || loserResolved.isBlank()) ? loserId.toString() : loserResolved;
            }
        }

        String arenaId = session.getArena() != null ? session.getArena().getId() : "-";
        String modeId = session.getMode() != null ? session.getMode().getId() : "-";
        String modeName = session.getMode() != null ? session.getMode().getDisplayName() : "-";

        for (String cmd : commands) {
            if (cmd == null || cmd.isBlank()) {
                continue;
            }
            String parsed = cmd.trim();
            if (parsed.startsWith("/")) {
                parsed = parsed.substring(1);
            }
            parsed = parsed
                    .replace("<arena>", arenaId)
                    .replace("<mode>", modeId)
                    .replace("<mode_name>", modeName)
                    .replace("<playerA>", playerAName)
                    .replace("<playerB>", playerBName)
                    .replace("<winner>", winnerName)
                    .replace("<loser>", loserName);
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), parsed);
        }
    }

    public void endBotMatch(BotMatchSession session, UUID winner, String reason) {
        botMatchesByPlayer.remove(session.getPlayerId());
        LivingEntity bot = session.getBot();
        if (bot != null) {
            botMatchesByBot.remove(bot.getUniqueId());
        }
        session.getArena().setStatus(ArenaStatus.FREE);
        clearArenaDrops(session.getArena());
        session.end(winner, reason);
        Player player = plugin.getServer().getPlayer(session.getPlayerId());
        if (player != null && player.isOnline()) {
            pendingRestoreManager.consume(player.getUniqueId());
        }
        queueManager.tryStartMatches(session.getMode().getId());
    }

    public void registerBotEntity(BotMatchSession session, LivingEntity bot) {
        if (session == null || bot == null) {
            return;
        }
        botMatchesByBot.put(bot.getUniqueId(), session);
    }

    private void clearArenaDrops(Arena arena) {
        if (arena == null || arena.getMinBound() == null || arena.getMaxBound() == null) {
            return;
        }
        World world = Bukkit.getWorld(arena.getWorldName());
        if (world == null) {
            return;
        }
        long[] passes = new long[]{1L, 2L, 10L, 20L};
        int minChunkX = Math.min(arena.getMinBound().getBlockX(), arena.getMaxBound().getBlockX()) >> 4;
        int maxChunkX = Math.max(arena.getMinBound().getBlockX(), arena.getMaxBound().getBlockX()) >> 4;
        int minChunkZ = Math.min(arena.getMinBound().getBlockZ(), arena.getMaxBound().getBlockZ()) >> 4;
        int maxChunkZ = Math.max(arena.getMinBound().getBlockZ(), arena.getMaxBound().getBlockZ()) >> 4;

        for (long delay : passes) {
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    final int chunkX = cx;
                    final int chunkZ = cz;
                    Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, task -> {
                        if (!world.isChunkLoaded(chunkX, chunkZ)) {
                            return;
                        }
                        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
                            if (!(entity instanceof Item
                                    || entity instanceof ExperienceOrb
                                    || entity instanceof AbstractArrow
                                    || entity instanceof Trident
                                    || entity instanceof EnderCrystal)) {
                                continue;
                            }
                            if (arena.isInsideBounds(entity.getLocation())) {
                                entity.remove();
                            }
                        }
                    }, delay);
                }
            }
        }
    }

    private void handlePendingRestoreFor(MatchSession session, UUID playerId, UUID quitterId) {
        boolean isQuitter = quitterId != null && quitterId.equals(playerId);
        Player player = plugin.getServer().getPlayer(playerId);
        debugRestore("handlePendingRestoreFor player=" + playerId
                + " isQuitter=" + isQuitter
                + " online=" + (player != null && player.isOnline())
                + " dead=" + (player != null && player.isDead()));
        if (isQuitter || player == null || !player.isOnline() || player.isDead()) {
            addPendingSnapshot(playerId, session.getSnapshot(playerId));
        } else {
            if (!pendingRestoreManager.has(playerId)) {
                addPendingSnapshot(playerId, session.getSnapshot(playerId));
            }
        }
    }

    public com.pvparena.model.PlayerSnapshot consumePendingRestore(Player player) {
        debugRestore("consumePendingRestore call " + player.getUniqueId());
        return pendingRestoreManager.consume(player.getUniqueId());
    }

    public com.pvparena.model.PlayerSnapshot peekPendingRestore(Player player) {
        debugRestore("peekPendingRestore call " + player.getUniqueId());
        return pendingRestoreManager.peek(player.getUniqueId());
    }

    public boolean hasPendingRestore(UUID playerId) {
        return pendingRestoreManager.has(playerId);
    }

    public void blockExternalSync(UUID playerId) {
        blockExternalSync(playerId, DEFAULT_EXTERNAL_SYNC_BLOCK_MILLIS);
    }

    public void blockExternalSync(UUID playerId, long durationMillis) {
        if (playerId == null) {
            return;
        }
        long until = System.currentTimeMillis() + Math.max(0L, durationMillis);
        externalSyncBlockedUntil.put(playerId, until);
        debugRestore("blockExternalSync " + playerId + " durationMs=" + durationMillis);
        if (externalSyncBlockedUntil.size() > MAX_EXTERNAL_SYNC_BLOCK_ENTRIES) {
            externalSyncBlockedUntil.entrySet().removeIf(e -> e.getValue() == null || System.currentTimeMillis() > e.getValue());
        }
    }

    private void debugRestore(String msg) {
        try {
            if (plugin.getConfig().getBoolean("debug.restore", false)) {
                plugin.getLogger().info("[RestoreDebug][MatchManager] " + msg);
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean isExternalSyncBlocked(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        Long until = externalSyncBlockedUntil.get(playerId);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() > until) {
            externalSyncBlockedUntil.remove(playerId);
            return false;
        }
        return true;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public PkManager getPkManager() {
        return pkManager;
    }

    public void recordRollbackBaseline(String sessionId, org.bukkit.Location location, org.bukkit.block.data.BlockData data) {
        if (crashRecoveryManager == null) {
            return;
        }
        if (!plugin.getConfig().getBoolean("rollback.persist-baseline-to-db", false)) {
            return;
        }
        crashRecoveryManager.recordRollbackBlock(sessionId, location, data);
    }

    public void updateRecoveryProgress(String sessionId, int currentRound, int roundsWonA, int roundsWonB) {
        if (crashRecoveryManager == null) {
            return;
        }
        crashRecoveryManager.updateActiveMatchProgress(sessionId, currentRound, roundsWonA, roundsWonB);
    }

    public void setSpectatorManager(SpectatorManager spectatorManager) {
        this.spectatorManager = spectatorManager;
    }

    public com.pvparena.model.MatchResult getLastResult(Player player) {
        synchronized (lastResults) {
            return lastResults.get(player.getUniqueId());
        }
    }

    public java.util.List<MatchSession> getActiveMatchSessions() {
        java.util.LinkedHashSet<MatchSession> unique = new java.util.LinkedHashSet<>(activeMatches.values());
        return new java.util.ArrayList<>(unique);
    }

    public boolean shouldBlockBack(Player player) {
        Long until;
        synchronized (backBlockUntil) {
            until = backBlockUntil.get(player.getUniqueId());
        }
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() > until) {
            synchronized (backBlockUntil) {
                backBlockUntil.remove(player.getUniqueId());
            }
            return false;
        }
        return true;
    }

    private void storeResult(MatchSession session, UUID winner, String reason) {
        String nameA = plugin.getServer().getOfflinePlayer(session.getPlayerA()).getName();
        String nameB = plugin.getServer().getOfflinePlayer(session.getPlayerB()).getName();
        double healthA = getHealthForResult(session, session.getPlayerA(), winner, reason);
        double healthB = getHealthForResult(session, session.getPlayerB(), winner, reason);
        com.pvparena.model.MatchResult resultA = new com.pvparena.model.MatchResult(
                nameB != null ? nameB : "Unknown",
                session.getMode().getDisplayName(),
                healthB,
                session.getCombatSnapshot(session.getPlayerB()) != null ? session.getCombatSnapshot(session.getPlayerB()).getMaxHealth() : 20.0,
                session.getDamageDealt(session.getPlayerA()),
                session.getDamageTaken(session.getPlayerA()),
                session.getCombatSnapshot(session.getPlayerB())
        );
        com.pvparena.model.MatchResult resultB = new com.pvparena.model.MatchResult(
                nameA != null ? nameA : "Unknown",
                session.getMode().getDisplayName(),
                healthA,
                session.getCombatSnapshot(session.getPlayerA()) != null ? session.getCombatSnapshot(session.getPlayerA()).getMaxHealth() : 20.0,
                session.getDamageDealt(session.getPlayerB()),
                session.getDamageTaken(session.getPlayerB()),
                session.getCombatSnapshot(session.getPlayerA())
        );
        synchronized (lastResults) {
            lastResults.put(session.getPlayerA(), resultA);
            lastResults.put(session.getPlayerB(), resultB);
            trimCache(lastResults, MAX_LAST_RESULTS);
        }
    }

    private double getHealthForResult(MatchSession session, UUID playerId, UUID winner, String reason) {
        if (session == null || playerId == null) {
            return 0.0;
        }
        if (winner != null && REASON_PLAYER_DEATH.equals(reason)) {
            UUID loser = winner.equals(session.getPlayerA()) ? session.getPlayerB() : session.getPlayerA();
            if (playerId.equals(loser)) {
                return 0.0;
            }
        }
        return getHealth(playerId);
    }

    private record PendingWinnerLeave(UUID winnerId, String reason, UUID quitterId,
                                      io.papermc.paper.threadedregions.scheduler.ScheduledTask task) {
    }

    private static <K, V> void trimCache(LinkedHashMap<K, V> map, int maxSize) {
        while (map.size() > maxSize) {
            K firstKey = map.keySet().iterator().next();
            map.remove(firstKey);
        }
    }

    private double getHealth(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        return player != null ? player.getHealth() : 0.0;
    }

    private void sendResultSummary(MatchSession session) {
        Player playerA = plugin.getServer().getPlayer(session.getPlayerA());
        Player playerB = plugin.getServer().getPlayer(session.getPlayerB());
        if (playerA != null) {
            sendSummaryTo(playerA, session.getPlayerA());
        }
        if (playerB != null) {
            sendSummaryTo(playerB, session.getPlayerB());
        }
    }

    private void sendSummaryTo(Player player, UUID playerId) {
        com.pvparena.model.MatchResult result = lastResults.get(playerId);
        if (result == null) {
            return;
        }
        Component view = MessageUtil.message("result_view")
            .clickEvent(ClickEvent.runCommand("/duel result"));
        SchedulerUtil.runOnPlayer(plugin, player, () -> {
            MessageUtil.send(player, "result_summary",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("your_health", String.format("%.1f", player.getHealth())),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("opponent_health", String.format("%.1f", result.getOpponentHealth())));
            MessageUtil.send(player, view);
        });
    }

    public void handleDeath(Player player) {
        handleDefeat(player, REASON_PLAYER_DEATH);
    }

    public void handleDefeat(Player player, String reason) {
        MatchSession session = getMatch(player);
        if (session != null) {
            session.onPlayerDefeated(player, reason == null ? REASON_PLAYER_DEATH : reason);
            return;
        }
        BotMatchSession botSession = getBotMatch(player);
        if (botSession == null) {
            return;
        }
        endBotMatch(botSession, botSession.getPlayerId(), REASON_PLAYER_DEATH);
    }

    public void handleQuit(Player player) {
        MatchSession session = getMatch(player);
        if (session != null) {
            session.ensureSnapshot(player);
            UUID winner = session.getOpponent(player.getUniqueId());
            endMatch(session, winner, REASON_PLAYER_OFFLINE, player.getUniqueId());
            return;
        }
        BotMatchSession botSession = getBotMatch(player);
        if (botSession == null) {
            return;
        }
        endBotMatch(botSession, null, REASON_PLAYER_OFFLINE);
    }
}
