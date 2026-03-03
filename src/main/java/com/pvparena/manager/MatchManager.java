package com.pvparena.manager;

import com.pvparena.PvPArenaPlugin;
import com.pvparena.model.Arena;
import com.pvparena.model.ArenaStatus;
import com.pvparena.model.BotDifficulty;
import com.pvparena.model.BotMatchSession;
import com.pvparena.model.MatchSession;
import com.pvparena.model.Mode;
import com.pvparena.rollback.ArenaRollbackService;
import com.pvparena.util.MessageUtil;
import com.pvparena.util.SchedulerUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MatchManager {
    public static final String REASON_PLAYER_DEATH = "reason_player_death";
    public static final String REASON_PLAYER_OFFLINE = "reason_player_offline";
    public static final String REASON_TIME_LIMIT_DRAW = "reason_time_limit_draw";
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
    private final ArenaRollbackService rollbackService;
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
    private final Map<String, Set<UUID>> lootPhaseEarlyLeavers = new ConcurrentHashMap<>();
    private final Set<String> preheatedArenaIds = ConcurrentHashMap.newKeySet();
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
        this.rollbackService = new ArenaRollbackService(plugin);
        this.pkManager = pkManager;
        this.queueManager.setMatchManager(this);
    }

    public void bootstrapCrashRecovery() {
        rollbackService.recoverPendingSnapshots();
        if (crashRecoveryManager == null) {
            return;
        }
        crashRecoveryManager.recoverPendingRollbacksAsync();
        crashRecoveryManager.loadActiveMatchesAsync(recovered -> {
            if (recovered == null || recovered.isEmpty()) {
                return;
            }
            for (MatchCrashRecoveryManager.RecoveredMatch match : recovered) {
                pendingRecoveredMatches.put(match.sessionId(), match);
            }
            plugin.getLogger().info("Loaded " + recovered.size() + " unfinished match(es) for crash recovery.");
        });
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
        long staggerTicks = Math.max(0L, plugin.getConfig().getLong("match.start-stagger-ticks", 1L));
        if (staggerTicks <= 0L) {
            session.start();
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
            if (activeMatches.get(recovered.playerA()) != session || activeMatches.get(recovered.playerB()) != session) {
                return;
            }
            session.start();
        }, staggerTicks);
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
        startMatch(p1, p2, modeId, arena, 0);
    }

    public void startMatch(UUID p1, UUID p2, String modeId, Arena arena, int duelTimeLimitSeconds) {
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
        session.setDuelTimeLimitSeconds(duelTimeLimitSeconds);
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
        long staggerTicks = Math.max(0L, plugin.getConfig().getLong("match.start-stagger-ticks", 1L));
        if (staggerTicks <= 0L) {
            session.start();
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
            if (activeMatches.get(p1) != session || activeMatches.get(p2) != session) {
                return;
            }
            session.start();
        }, staggerTicks);
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
        if (winner != null && REASON_PLAYER_OFFLINE.equals(reason)) {
            winnerLeaveDelay = Math.max(1, winnerLeaveDelay);
        }
        if (winner != null && winnerLeaveDelay > 0 && session.beginWinnerLeaveCountdown()) {
            String sessionId = session.getRecoverySessionId();
            UUID loser = session.getOpponent(winner);
            String soundKey = getWinnerLeaveWaitSoundKey();
            float soundVolume = getWinnerLeaveWaitSoundVolume();
            float soundPitch = getWinnerLeaveWaitSoundPitch();
            prepareWinnerLootPhase(winner, loser);
            playWinnerLeaveWaitSound(winner, soundKey, soundVolume, soundPitch);
            playWinnerLeaveWaitSound(loser, soundKey, soundVolume, soundPitch);
            UUID winnerId = winner;
            UUID loserId = loser;
            AtomicInteger remain = new AtomicInteger(winnerLeaveDelay);
            io.papermc.paper.threadedregions.scheduler.ScheduledTask countdownTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
                int sec = remain.getAndDecrement();
                Player winnerPlayer = plugin.getServer().getPlayer(winnerId);
                if (sec <= 0) {
                    task.cancel();
                    pendingWinnerLeaves.remove(sessionId);
                    stopWinnerLeaveWaitSound(winnerId, soundKey);
                    stopWinnerLeaveWaitSound(loserId, soundKey);
                    finalizeEndMatch(session, winner, reason, quitterId);
                    return;
                }
                enforceWinnerLootPhaseState(sessionId, winnerId, loserId);
                if (winnerPlayer != null && winnerPlayer.isOnline()) {
                    SchedulerUtil.runOnPlayer(plugin, winnerPlayer, () -> winnerPlayer.sendActionBar(
                            MessageUtil.message("winner_leave_countdown",
                                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("seconds", String.valueOf(sec)))));
                }
            }, 1L, 20L);
            pendingWinnerLeaves.put(sessionId, new PendingWinnerLeave(winnerId, loserId, reason, quitterId, countdownTask, soundKey));
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

        UUID playerId = player.getUniqueId();
        UUID winnerId = pending.winnerId();
        UUID loserId = session.getOpponent(winnerId);

        if (winnerId != null && winnerId.equals(playerId)) {
            pendingWinnerLeaves.remove(sessionId);
            if (pending.task() != null) {
                pending.task().cancel();
            }
            stopWinnerLeaveWaitSound(winnerId, pending.soundKey());
            stopWinnerLeaveWaitSound(pending.loserId(), pending.soundKey());
            finalizeEndMatch(session, pending.winnerId(), pending.reason(), pending.quitterId());
            return true;
        }

        if (loserId != null && loserId.equals(playerId)) {
            markLootPhaseEarlyLeave(sessionId, playerId);
            stopWinnerLeaveWaitSound(playerId, pending.soundKey());
            releaseSingleLoserFromLootPhase(session, player);
            return true;
        }

        return false;
    }

    private void markLootPhaseEarlyLeave(String sessionId, UUID playerId) {
        if (sessionId == null || playerId == null) {
            return;
        }
        lootPhaseEarlyLeavers.compute(sessionId, (id, existing) -> {
            Set<UUID> set = existing != null ? existing : ConcurrentHashMap.newKeySet();
            set.add(playerId);
            return set;
        });
    }

    private void releaseSingleLoserFromLootPhase(MatchSession session, Player loser) {
        if (session == null || loser == null) {
            return;
        }
        UUID loserId = loser.getUniqueId();
        activeMatches.remove(loserId);
        clearEliminatedSpectator(loserId);
        blockExternalSync(loserId, 120_000L);
        session.endSinglePlayer(loserId, session.getOpponent(loserId), REASON_PLAYER_DEATH);
        pkManager.restore(loserId);
        restorePostMatchPlayerState(loser);
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
                    MessageUtil.send(loser, "duel_loser_leave_hint",
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("command", "/duel dleave"));
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

    private void enforceWinnerLootPhaseState(String sessionId, UUID winnerId, UUID loserId) {
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
            Set<UUID> leavers = lootPhaseEarlyLeavers.get(sessionId);
            if (leavers != null && leavers.contains(loserId)) {
                return;
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
        if (pending != null) {
            stopWinnerLeaveWaitSound(pending.winnerId(), pending.soundKey());
            stopWinnerLeaveWaitSound(pending.loserId(), pending.soundKey());
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

        Set<UUID> earlyLeaversRaw = lootPhaseEarlyLeavers.remove(session.getRecoverySessionId());
        final Set<UUID> earlyLeavers = earlyLeaversRaw != null ? earlyLeaversRaw : java.util.Collections.emptySet();

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
            if (!earlyLeavers.contains(session.getPlayerA())) {
                handlePendingRestoreFor(session, session.getPlayerA(), quitterId);
            }
            if (!earlyLeavers.contains(session.getPlayerB())) {
                handlePendingRestoreFor(session, session.getPlayerB(), quitterId);
            }
            session.getArena().setStatus(ArenaStatus.FREE);
            if (shouldClearArenaDropsOnEnd(session)) {
                clearArenaDrops(session.getArena());
            }
            storeResult(session, winner, reason);
            sendResultSummary(session);
            synchronized (backBlockUntil) {
                backBlockUntil.put(session.getPlayerA(), System.currentTimeMillis() + BACK_BLOCK_MILLIS);
                backBlockUntil.put(session.getPlayerB(), System.currentTimeMillis() + BACK_BLOCK_MILLIS);
                trimCache(backBlockUntil, MAX_BACK_BLOCK_ENTRIES);
            }
            pkManager.restore(session.getPlayerA());
            pkManager.restore(session.getPlayerB());
            Player playerAOnline = plugin.getServer().getPlayer(session.getPlayerA());
            if (playerAOnline != null && playerAOnline.isOnline()) {
                restorePostMatchPlayerState(playerAOnline);
            }
            Player playerBOnline = plugin.getServer().getPlayer(session.getPlayerB());
            if (playerBOnline != null && playerBOnline.isOnline()) {
                restorePostMatchPlayerState(playerBOnline);
            }
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
        int minChunkX = Math.min(arena.getMinBound().getBlockX(), arena.getMaxBound().getBlockX()) >> 4;
        int maxChunkX = Math.max(arena.getMinBound().getBlockX(), arena.getMaxBound().getBlockX()) >> 4;
        int minChunkZ = Math.min(arena.getMinBound().getBlockZ(), arena.getMaxBound().getBlockZ()) >> 4;
        int maxChunkZ = Math.max(arena.getMinBound().getBlockZ(), arena.getMaxBound().getBlockZ()) >> 4;
        java.util.ArrayDeque<long[]> chunks = new java.util.ArrayDeque<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                chunks.offer(new long[]{cx, cz});
            }
        }
        final int chunksPerTick = 2;
        Runnable runner = new Runnable() {
            @Override
            public void run() {
                int processed = 0;
                while (processed < chunksPerTick && !chunks.isEmpty()) {
                    long[] coord = chunks.poll();
                    int chunkX = (int) coord[0];
                    int chunkZ = (int) coord[1];
                    Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, task -> {
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
                    });
                    processed++;
                }
                if (!chunks.isEmpty()) {
                    Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> run(), 1L);
                }
            }
        };
        Bukkit.getGlobalRegionScheduler().run(plugin, t -> runner.run());
    }

    private boolean shouldClearArenaDropsOnEnd(MatchSession session) {
        if (session == null) {
            return false;
        }
        return plugin.getConfig().getBoolean("match.clear-arena-drops-on-end", true);
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

    public ArenaRollbackService getRollbackService() {
        return rollbackService;
    }

    public boolean tryMarkArenaPreheated(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) {
            return false;
        }
        return preheatedArenaIds.add(arenaId.toLowerCase(Locale.ROOT));
    }

    public void warmupArenaChunksAsync(Collection<Arena> arenas) {
        if (arenas == null || arenas.isEmpty()) {
            return;
        }
        List<Arena> queue = new ArrayList<>();
        for (Arena arena : arenas) {
            if (arena == null || arena.getId() == null || arena.getId().isBlank()) {
                continue;
            }
            if (arena.getSpawn1() == null || arena.getSpawn2() == null
                    || arena.getSpawn1().getWorld() == null || arena.getSpawn2().getWorld() == null) {
                continue;
            }
            if (!arena.getSpawn1().getWorld().getName().equalsIgnoreCase(arena.getSpawn2().getWorld().getName())) {
                continue;
            }
            if (!tryMarkArenaPreheated(arena.getId())) {
                continue;
            }
            queue.add(arena);
        }
        if (queue.isEmpty()) {
            return;
        }
        runArenaChunkWarmupQueue(queue, 0, getArenaWarmupDelayTicks());
    }

    private void runArenaChunkWarmupQueue(List<Arena> queue, int index, long delayTicks) {
        if (queue == null || index >= queue.size()) {
            return;
        }
        Arena arena = queue.get(index);
        warmupSingleArenaChunksAsync(arena).whenComplete((v, ex) ->
                plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin,
                        task -> runArenaChunkWarmupQueue(queue, index + 1, delayTicks), Math.max(1L, delayTicks)));
    }

    private CompletableFuture<Void> warmupSingleArenaChunksAsync(Arena arena) {
        if (arena == null || arena.getSpawn1() == null || arena.getSpawn1().getWorld() == null) {
            return CompletableFuture.completedFuture(null);
        }
        int radius = Math.max(0, plugin.getConfig().getInt("match.preheat-radius-chunks", 1));
        int perTick = Math.max(1, plugin.getConfig().getInt("match.preheat-chunks-per-tick", 2));
        World world = arena.getSpawn1().getWorld();

        Set<Long> chunkKeys = new HashSet<>();
        collectPreheatChunks(chunkKeys, arena.getSpawn1(), radius);
        collectPreheatChunks(chunkKeys, arena.getSpawn2(), radius);
        if (chunkKeys.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> done = new CompletableFuture<>();
        runArenaPreheatBatch(world, new ArrayList<>(chunkKeys), 0, perTick, done);
        return done.exceptionally(ex -> null);
    }

    private void runArenaPreheatBatch(World world, List<Long> pendingChunks, int startIndex, int perTick, CompletableFuture<Void> done) {
        if (done.isDone()) {
            return;
        }
        if (world == null || pendingChunks == null || pendingChunks.isEmpty() || startIndex >= pendingChunks.size()) {
            done.complete(null);
            return;
        }

        int end = Math.min(pendingChunks.size(), startIndex + Math.max(1, perTick));
        for (int i = startIndex; i < end; i++) {
            long key = pendingChunks.get(i);
            int chunkX = unpackChunkX(key);
            int chunkZ = unpackChunkZ(key);
            world.getChunkAtAsync(chunkX, chunkZ, true).exceptionally(ex -> null);
        }

        if (end < pendingChunks.size()) {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin,
                    task -> runArenaPreheatBatch(world, pendingChunks, end, perTick, done), 1L);
            return;
        }
        done.complete(null);
    }

    private void collectPreheatChunks(Set<Long> chunkKeys, Location center, int radius) {
        if (chunkKeys == null || center == null || center.getWorld() == null) {
            return;
        }
        int centerChunkX = center.getBlockX() >> 4;
        int centerChunkZ = center.getBlockZ() >> 4;
        for (int x = centerChunkX - radius; x <= centerChunkX + radius; x++) {
            for (int z = centerChunkZ - radius; z <= centerChunkZ + radius; z++) {
                chunkKeys.add(packChunkKey(x, z));
            }
        }
    }

    private long getArenaWarmupDelayTicks() {
        return Math.max(1L, plugin.getConfig().getLong("match.preheat-arena-delay-ticks", 2L));
    }

    private long packChunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private int unpackChunkX(long packedKey) {
        return (int) (packedKey >> 32);
    }

    private int unpackChunkZ(long packedKey) {
        return (int) packedKey;
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

    private String getWinnerLeaveWaitSoundKey() {
        if (!plugin.getConfig().getBoolean("match.winner-leave-wait.play-sound", true)) {
            return null;
        }
        String configured = plugin.getConfig().getString("match.winner-leave-wait.sound", "music_disc.otherside");
        if (configured == null) {
            return null;
        }
        String value = configured.trim();
        if (value.isEmpty() || "none".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value)
                || "disabled".equalsIgnoreCase(value)) {
            return null;
        }
        value = value.toLowerCase(Locale.ROOT);
        if (!value.contains(":")) {
            value = "minecraft:" + value;
        }
        return value;
    }

    private float getWinnerLeaveWaitSoundVolume() {
        double configured = plugin.getConfig().getDouble("match.winner-leave-wait.volume", 1.0D);
        return (float) Math.max(0.0D, Math.min(2.0D, configured));
    }

    private float getWinnerLeaveWaitSoundPitch() {
        double configured = plugin.getConfig().getDouble("match.winner-leave-wait.pitch", 1.0D);
        return (float) Math.max(0.5D, Math.min(2.0D, configured));
    }

    private void playWinnerLeaveWaitSound(UUID playerId, String soundKey, float volume, float pitch) {
        if (playerId == null || soundKey == null || soundKey.isBlank()) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        SchedulerUtil.runOnPlayer(plugin, player, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.stopSound(soundKey);
            player.playSound(player, soundKey, SoundCategory.RECORDS, volume, pitch);
        });
    }

    private void stopWinnerLeaveWaitSound(UUID playerId, String soundKey) {
        if (playerId == null || soundKey == null || soundKey.isBlank()) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        SchedulerUtil.runOnPlayer(plugin, player, () -> {
            if (player.isOnline()) {
                player.stopSound(soundKey);
            }
        });
    }

    private record PendingWinnerLeave(UUID winnerId, UUID loserId, String reason, UUID quitterId,
                                      io.papermc.paper.threadedregions.scheduler.ScheduledTask task,
                                      String soundKey) {
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
            boolean dropInventoryMode = session.isDropInventoryMode();
            if (dropInventoryMode) {
                processOfflineDefeat(player, true);
            } else {
                processOfflineDefeat(player, false);
            }
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

    private void processOfflineDefeat(Player quitter, boolean dropItems) {
        if (quitter == null) {
            return;
        }
        Location location = quitter.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }

        List<ItemStack> drops = List.of();
        if (dropItems) {
            drops = collectPlayerDrops(quitter);
            clearPlayerInventoryForDrop(quitter);
        }

        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        List<ItemStack> finalDrops = drops;
        plugin.getServer().getRegionScheduler().run(plugin, world, chunkX, chunkZ, task -> {
            try {
                world.strikeLightningEffect(location);
            } catch (Throwable ignored) {
            }
            if (!dropItems || finalDrops.isEmpty()) {
                return;
            }
            for (ItemStack item : finalDrops) {
                if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
                    continue;
                }
                world.dropItemNaturally(location, item);
            }
        });
    }

    private List<ItemStack> collectPlayerDrops(Player player) {
        java.util.ArrayList<ItemStack> drops = new java.util.ArrayList<>();
        if (player == null) {
            return drops;
        }
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                drops.add(item.clone());
            }
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() != Material.AIR && offhand.getAmount() > 0) {
            drops.add(offhand.clone());
        }
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null && armor.getType() != Material.AIR && armor.getAmount() > 0) {
                drops.add(armor.clone());
            }
        }
        return drops;
    }

    private void clearPlayerInventoryForDrop(Player player) {
        if (player == null) {
            return;
        }
        try {
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[4]);
            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            player.setItemOnCursor(null);
        } catch (Throwable ignored) {
        }
    }

    private void restorePostMatchPlayerState(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        SchedulerUtil.runOnPlayer(plugin, player, () -> {
            if (!player.isOnline()) {
                return;
            }
            try {
                if (player.getGameMode() == GameMode.ADVENTURE) {
                    player.setGameMode(GameMode.SURVIVAL);
                }
            } catch (Throwable ignored) {
            }
            try {
                player.setInvisible(false);
            } catch (Throwable ignored) {
            }
            try {
                player.setInvulnerable(false);
            } catch (Throwable ignored) {
            }
            try {
                player.setCollidable(true);
            } catch (Throwable ignored) {
            }
            try {
                player.setCanPickupItems(true);
            } catch (Throwable ignored) {
            }
            try {
                player.setAllowFlight(false);
                player.setFlying(false);
            } catch (Throwable ignored) {
            }
        });
    }

}
