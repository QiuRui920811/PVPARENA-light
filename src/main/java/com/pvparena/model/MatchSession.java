package com.pvparena.model;

import com.pvparena.manager.MatchManager;
import com.pvparena.util.MessageUtil;
import com.pvparena.util.PlayerStateUtil;
import com.pvparena.util.SchedulerUtil;
import fr.mrmicky.fastboard.FastBoard;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Trident;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class MatchSession {
    private final JavaPlugin plugin;
    private final MatchManager matchManager;
    private final UUID playerA;
    private final UUID playerB;
    private final Mode mode;
    private final Arena arena;
    private final String recoverySessionId = java.util.UUID.randomUUID().toString();
    private PlayerSnapshot snapshotA;
    private PlayerSnapshot snapshotB;
    private Location returnLocationA;
    private Location returnLocationB;
    private final Map<UUID, Double> damageDealt = new HashMap<>();
    private final Map<UUID, Double> damageTaken = new HashMap<>();
    private final Map<UUID, CombatSnapshot> combatSnapshots = new HashMap<>();
    private volatile MatchState state = MatchState.PREPARING;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask countdownTask;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask nextRoundTask;
    private volatile int roundsWonA = 0;
    private volatile int roundsWonB = 0;
    private volatile int currentRound = 1;
    private final Map<BlockKey, BlockData> originalArenaBlocks = new LinkedHashMap<>();
    private final Set<BlockKey> playerPlacedBlocks = new HashSet<>();
    private BossBar roundBossBarA;
    private BossBar roundBossBarB;
    private BossBar healthBossBarA;
    private BossBar healthBossBarB;
    private final Map<UUID, FastBoard> roundSideboards = new HashMap<>();
    private final Map<UUID, TabSnapshot> duelTabSnapshots = new HashMap<>();
    private final Map<UUID, Set<UUID>> duelHiddenPlayers = new HashMap<>();
    private final Map<UUID, String> lastRoundBoardTitles = new HashMap<>();
    private final Map<UUID, List<String>> lastRoundBoardLines = new HashMap<>();
    private final Map<UUID, String> lastDuelTabHeaders = new HashMap<>();
    private final Map<UUID, String> lastDuelTabFooters = new HashMap<>();
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask hudTask;
    private long hudTickCounter = 0L;
    private volatile boolean roundResolving = false;
    private volatile int lastResolvedRound = 0;
    private volatile int resolvingRound = 0;
    private volatile boolean winnerLeaveCountdownStarted = false;
    private volatile boolean resumedFromRecovery = false;

    public MatchSession(JavaPlugin plugin, MatchManager matchManager, UUID playerA, UUID playerB, Mode mode, Arena arena) {
        this.plugin = plugin;
        this.matchManager = matchManager;
        this.playerA = playerA;
        this.playerB = playerB;
        this.mode = mode;
        this.arena = arena;
    }

    public UUID getPlayerA() {
        return playerA;
    }

    public UUID getPlayerB() {
        return playerB;
    }

    public Mode getMode() {
        return mode;
    }

    public Arena getArena() {
        return arena;
    }

    public String getRecoverySessionId() {
        return recoverySessionId;
    }

    public void applyRecoveredProgress(int recoveredRound, int recoveredRoundsWonA, int recoveredRoundsWonB) {
        this.currentRound = Math.max(1, recoveredRound);
        this.roundsWonA = Math.max(0, recoveredRoundsWonA);
        this.roundsWonB = Math.max(0, recoveredRoundsWonB);
        this.resumedFromRecovery = true;
    }

    public PlayerSnapshot getSnapshot(UUID playerId) {
        if (playerA.equals(playerId)) {
            return snapshotA;
        }
        if (playerB.equals(playerId)) {
            return snapshotB;
        }
        return null;
    }

    public MatchState getState() {
        return state;
    }

    public boolean isMovementLocked() {
        if (winnerLeaveCountdownStarted) {
            return false;
        }
        return state == MatchState.PREPARING || state == MatchState.COUNTDOWN;
    }

    public boolean isFighting() {
        return state == MatchState.FIGHTING;
    }

    public boolean isRoundResolving() {
        return roundResolving;
    }

    public boolean beginWinnerLeaveCountdown() {
        if (winnerLeaveCountdownStarted) {
            return false;
        }
        winnerLeaveCountdownStarted = true;
        return true;
    }

    public boolean beginRoundResolution() {
        if (state != MatchState.FIGHTING || roundResolving) {
            return false;
        }
        roundResolving = true;
        resolvingRound = currentRound;
        state = MatchState.PREPARING;
        return true;
    }

    public long getRoundResolveDelayTicks() {
        return Math.max(0L, Math.min(100L, plugin.getConfig().getLong("match.round-end-showcase-ticks", 12L)));
    }

    public UUID getOpponent(UUID player) {
        if (playerA.equals(player)) {
            return playerB;
        }
        return playerA;
    }

    public void recordDamage(UUID attacker, UUID victim, double amount) {
        damageDealt.put(attacker, damageDealt.getOrDefault(attacker, 0.0) + amount);
        damageTaken.put(victim, damageTaken.getOrDefault(victim, 0.0) + amount);
    }

    public double getDamageDealt(UUID player) {
        return damageDealt.getOrDefault(player, 0.0);
    }

    public double getDamageTaken(UUID player) {
        return damageTaken.getOrDefault(player, 0.0);
    }

    public CombatSnapshot getCombatSnapshot(UUID player) {
        return combatSnapshots.get(player);
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public int getRoundsWon(UUID playerId) {
        if (playerA.equals(playerId)) {
            return roundsWonA;
        }
        if (playerB.equals(playerId)) {
            return roundsWonB;
        }
        return 0;
    }

    public boolean willDefeatEndMatch(UUID loserId) {
        if (loserId == null) {
            return false;
        }
        UUID winnerId = getOpponent(loserId);
        if (winnerId == null) {
            return false;
        }
        int nextA = roundsWonA + (winnerId.equals(playerA) ? 1 : 0);
        int nextB = roundsWonB + (winnerId.equals(playerB) ? 1 : 0);
        int target = Math.max(1, mode.getSettings().getRoundsToWin());
        return nextA >= target || nextB >= target;
    }

    public boolean allowsBlockEdit() {
        return mode.getSettings().isBuildEnabled() && isFighting();
    }

    public boolean isDropInventoryMode() {
        return mode.isUsePlayerInventory() && !mode.isRestoreBackupAfterMatch();
    }

    public void onPlayerDefeated(Player defeated, String reason) {
        boolean validResolutionWindow = isFighting() || (roundResolving && resolvingRound == currentRound);
        if (defeated == null || !validResolutionWindow) {
            return;
        }
        if (lastResolvedRound == currentRound) {
            return;
        }
        lastResolvedRound = currentRound;
        roundResolving = true;
        if (resolvingRound <= 0) {
            resolvingRound = currentRound;
        }
        state = MatchState.PREPARING;
        UUID loserId = defeated.getUniqueId();
        UUID winnerId = getOpponent(loserId);
        if (winnerId == null) {
            return;
        }

        if (winnerId.equals(playerA)) {
            roundsWonA++;
        } else {
            roundsWonB++;
        }

        Player winner = plugin.getServer().getPlayer(winnerId);
        Player loser = plugin.getServer().getPlayer(loserId);
        playRoundDefeatLightning(loser);
        if (winner != null) {
            sendRoundResultMessage(winner, true);
        }
        if (loser != null) {
            sendRoundResultMessage(loser, false);
        }
        updateRoundScoreboard();
        updateRoundBossBar();

        int target = Math.max(1, mode.getSettings().getRoundsToWin());
        if (roundsWonA >= target || roundsWonB >= target) {
            matchManager.updateRecoveryProgress(recoverySessionId, currentRound, roundsWonA, roundsWonB);
            if (mode.getSettings().isEliminatedCanSpectate()) {
                matchManager.enterRoundEliminatedSpectator(loserId, winnerId);
            }
            matchManager.endMatch(this, winnerId, reason);
            return;
        }

        if (mode.getSettings().isEliminatedCanSpectate()) {
            matchManager.enterRoundEliminatedSpectator(loserId, winnerId);
        }

        currentRound++;
        matchManager.updateRecoveryProgress(recoverySessionId, currentRound, roundsWonA, roundsWonB);
        resolvingRound = 0;
        sendRoundNextMessage();
        updateRoundBossBar();
        scheduleNextRound();
    }

    private void scheduleNextRound() {
        if (nextRoundTask != null) {
            nextRoundTask.cancel();
            nextRoundTask = null;
        }
        // Round has ended: hide opponent HP bar immediately to avoid "still fighting" feeling
        // during the inter-round delay.
        clearHealthBossBar();
        int delaySeconds = Math.max(0, mode.getSettings().getNextRoundDelaySeconds());
        state = MatchState.PREPARING;
        long delayTicks = Math.max(0L, delaySeconds * 20L);
        startNextRound(delayTicks);
    }

    public void captureBlockBeforeBreak(Block block) {
        if (block == null || !mode.getSettings().isBuildEnabled()) {
            return;
        }
        if (!isInArenaRollbackArea(block.getLocation())) {
            return;
        }
        BlockKey key = BlockKey.of(block.getLocation());
        synchronized (originalArenaBlocks) {
            originalArenaBlocks.putIfAbsent(key, block.getBlockData().clone());
        }
        matchManager.recordRollbackBaseline(recoverySessionId, block.getLocation(), block.getBlockData().clone());
    }

    public boolean canBreakPlacedBlock(Block block) {
        if (block == null || !mode.getSettings().isBuildEnabled()) {
            return false;
        }
        if (!isInArenaRollbackArea(block.getLocation())) {
            return false;
        }
        BlockKey key = BlockKey.of(block.getLocation());
        synchronized (originalArenaBlocks) {
            return playerPlacedBlocks.contains(key);
        }
    }

    public void markPlacedBlock(Block block) {
        if (block == null || !mode.getSettings().isBuildEnabled()) {
            return;
        }
        if (!isInArenaRollbackArea(block.getLocation())) {
            return;
        }
        BlockKey key = BlockKey.of(block.getLocation());
        synchronized (originalArenaBlocks) {
            playerPlacedBlocks.add(key);
        }
    }

    public void unmarkPlacedBlock(Block block) {
        if (block == null) {
            return;
        }
        BlockKey key = BlockKey.of(block.getLocation());
        synchronized (originalArenaBlocks) {
            playerPlacedBlocks.remove(key);
        }
    }

    public void captureBlockBeforePlace(BlockState replacedState) {
        if (replacedState == null || !mode.getSettings().isBuildEnabled()) {
            return;
        }
        if (!isInArenaRollbackArea(replacedState.getLocation())) {
            return;
        }
        BlockKey key = BlockKey.of(replacedState.getLocation());
        synchronized (originalArenaBlocks) {
            originalArenaBlocks.putIfAbsent(key, replacedState.getBlockData().clone());
        }
        matchManager.recordRollbackBaseline(recoverySessionId, replacedState.getLocation(), replacedState.getBlockData().clone());
    }

    public void rollbackArenaChanges() {
        rollbackArenaChangesAsync();
    }

    public CompletableFuture<Void> rollbackArenaChangesAsync() {
        List<Map.Entry<BlockKey, BlockData>> entries;
        synchronized (originalArenaBlocks) {
            playerPlacedBlocks.clear();
            if (originalArenaBlocks.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            entries = new ArrayList<>(originalArenaBlocks.entrySet());
            originalArenaBlocks.clear();
        }
        Map<String, List<Map.Entry<BlockKey, BlockData>>> byWorld = new HashMap<>();
        for (Map.Entry<BlockKey, BlockData> entry : entries) {
            byWorld.computeIfAbsent(entry.getKey().worldId, k -> new ArrayList<>()).add(entry);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        futures.addAll(applyRollbackPass(byWorld, 0L));
        long secondPassDelay = getRollbackSecondPassDelayTicks();
        if (secondPassDelay > 0L) {
            futures.addAll(applyRollbackPass(byWorld, secondPassDelay));
        }
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private int getRollbackBoundsExpand() {
        return Math.max(1, plugin.getConfig().getInt("rollback.bounds-expand", 3));
    }

    private int getRollbackBlocksPerTick() {
        return Math.max(32, plugin.getConfig().getInt("rollback.blocks-per-tick", 256));
    }

    private long getRollbackSecondPassDelayTicks() {
        return Math.max(0L, plugin.getConfig().getLong("rollback.second-pass-delay-ticks", 0L));
    }

    private List<CompletableFuture<Void>> applyRollbackPass(Map<String, List<Map.Entry<BlockKey, BlockData>>> byWorld, long delayTicks) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        int blocksPerTick = getRollbackBlocksPerTick();
        for (Map.Entry<String, List<Map.Entry<BlockKey, BlockData>>> worldEntry : byWorld.entrySet()) {
            World world = plugin.getServer().getWorld(worldEntry.getKey());
            if (world == null) {
                continue;
            }
            Map<Long, List<Map.Entry<BlockKey, BlockData>>> byChunk = new HashMap<>();
            for (Map.Entry<BlockKey, BlockData> entry : worldEntry.getValue()) {
                BlockKey key = entry.getKey();
                int chunkX = key.x >> 4;
                int chunkZ = key.z >> 4;
                long chunkKey = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
                byChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entry);
            }

            for (Map.Entry<Long, List<Map.Entry<BlockKey, BlockData>>> chunkEntry : byChunk.entrySet()) {
                int chunkX = (int) (chunkEntry.getKey() >> 32);
                int chunkZ = (int) (chunkEntry.getKey().longValue());
                List<Map.Entry<BlockKey, BlockData>> chunkBlocks = chunkEntry.getValue();
                CompletableFuture<Void> chunkDone = new CompletableFuture<>();
                futures.add(chunkDone);
                if (delayTicks <= 0L) {
                    plugin.getServer().getRegionScheduler().run(plugin, world, chunkX, chunkZ,
                            task -> applyChunkRollbackBatch(world, chunkX, chunkZ, chunkBlocks, 0, blocksPerTick, chunkDone));
                } else {
                    plugin.getServer().getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ,
                            task -> applyChunkRollbackBatch(world, chunkX, chunkZ, chunkBlocks, 0, blocksPerTick, chunkDone), delayTicks);
                }
            }
        }
        return futures;
    }

    private void applyChunkRollbackBatch(World world, int chunkX, int chunkZ,
                                         List<Map.Entry<BlockKey, BlockData>> chunkBlocks,
                                         int startIndex, int blocksPerTick,
                                         CompletableFuture<Void> done) {
        int end = Math.min(chunkBlocks.size(), startIndex + blocksPerTick);
        try {
            for (int i = startIndex; i < end; i++) {
                Map.Entry<BlockKey, BlockData> blockEntry = chunkBlocks.get(i);
                BlockKey key = blockEntry.getKey();
                BlockData original = blockEntry.getValue();
                Block block = world.getBlockAt(key.x, key.y, key.z);
                block.setType(original.getMaterial(), false);
                block.setBlockData(original, false);
            }
        } catch (Throwable throwable) {
            done.completeExceptionally(throwable);
            return;
        }
        if (end < chunkBlocks.size()) {
            plugin.getServer().getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ,
                    task -> applyChunkRollbackBatch(world, chunkX, chunkZ, chunkBlocks, end, blocksPerTick, done), 1L);
            return;
        }
        done.complete(null);
    }

    public void start() {
        roundResolving = false;
        if (!resumedFromRecovery) {
            roundsWonA = 0;
            roundsWonB = 0;
            currentRound = 1;
        }
        rollbackArenaChangesAsync().thenCompose(v -> cleanupArenaTransientArtifactsAsync()).whenComplete((rv, rex) -> {
            Player p1 = plugin.getServer().getPlayer(playerA);
            Player p2 = plugin.getServer().getPlayer(playerB);
            if (p1 == null || p2 == null) {
                matchManager.endMatch(this, null, MatchManager.REASON_PLAYER_OFFLINE);
                return;
            }
            if (arena.getSpawn1() == null || arena.getSpawn2() == null) {
                matchManager.endMatch(this, null, MatchManager.REASON_ARENA_SPAWN_UNSET);
                return;
            }
            state = MatchState.PREPARING;
            Location spawn1 = arena.getSpawn1();
            Location spawn2 = arena.getSpawn2();
            CompletableFuture<Void> f1 = prepareAndTeleport(p1, spawn1, true);
            CompletableFuture<Void> f2 = prepareAndTeleport(p2, spawn2, false);
            CompletableFuture.allOf(f1, f2).thenCompose(v -> captureArenaBaselineAsync()).whenComplete((v, ex) -> {
                if (ex != null) {
                    matchManager.endMatch(this, null, MatchManager.REASON_TELEPORT_FAILED);
                    return;
                }
                startCountdown(p1, p2);
            });
        });
    }

    private CompletableFuture<Void> prepareAndTeleport(Player player, Location spawn, boolean isA) {
        CompletableFuture<Void> prep = SchedulerUtil.runOnPlayerFuture(plugin, player, () -> {
            Location back = player.getLocation() == null ? null : player.getLocation().clone();
            if (isA) {
                returnLocationA = back;
                if (mode.isRestoreBackupAfterMatch() && !resumedFromRecovery) {
                    snapshotA = new PlayerSnapshot(player);
                    matchManager.addOrReplacePendingSnapshot(player.getUniqueId(), snapshotA);
                }
            } else {
                returnLocationB = back;
                if (mode.isRestoreBackupAfterMatch() && !resumedFromRecovery) {
                    snapshotB = new PlayerSnapshot(player);
                    matchManager.addOrReplacePendingSnapshot(player.getUniqueId(), snapshotB);
                }
            }
            if (player.isInsideVehicle()) {
                player.leaveVehicle();
            }
            if (player.getVehicle() != null) {
                player.getVehicle().eject();
            }
            if (!player.getPassengers().isEmpty()) {
                player.eject();
            }
            player.setItemOnCursor(null);
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            player.setInvulnerable(false);
            player.setInvisible(false);
            player.setCanPickupItems(true);
            player.setSilent(false);
            player.setCollidable(true);
            clearCombatResidueOnPlayer(player);
            if (!mode.isUsePlayerInventory()) {
                PlayerStateUtil.reset(player);
            }
        });
        return prep.thenCompose(v -> SchedulerUtil.teleport(plugin, player, spawn));
    }

    private CompletableFuture<Void> prepareForRoundTeleport(Player player, Location spawn) {
        CompletableFuture<Void> prep = SchedulerUtil.runOnPlayerFuture(plugin, player, () -> {
            if (player.isInsideVehicle()) {
                player.leaveVehicle();
            }
            if (player.getVehicle() != null) {
                player.getVehicle().eject();
            }
            if (!player.getPassengers().isEmpty()) {
                player.eject();
            }
            player.setItemOnCursor(null);
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            player.setInvulnerable(false);
            player.setInvisible(false);
            player.setCanPickupItems(true);
            player.setSilent(false);
            player.setCollidable(true);
            clearCombatResidueOnPlayer(player);
            if (!mode.isUsePlayerInventory()) {
                PlayerStateUtil.reset(player);
            }
        });
        return prep.thenCompose(v -> SchedulerUtil.teleport(plugin, player, spawn));
    }

    private CompletableFuture<Void> captureArenaBaselineAsync() {
        if (!mode.getSettings().isBuildEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        // Full-region baseline capture is expensive and can delay round start.
        // Default off: rely on per-block pre-change capture from break/place/explode listeners.
        if (!plugin.getConfig().getBoolean("rollback.full-baseline-capture", false)) {
            return CompletableFuture.completedFuture(null);
        }
        Location minBound = arena.getMinBound();
        Location maxBound = arena.getMaxBound();
        if (minBound == null || maxBound == null || minBound.getWorld() == null || maxBound.getWorld() == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (!minBound.getWorld().getName().equalsIgnoreCase(maxBound.getWorld().getName())) {
            return CompletableFuture.completedFuture(null);
        }

        World world = minBound.getWorld();
        int expand = getRollbackBoundsExpand();
        int minX = Math.min(minBound.getBlockX(), maxBound.getBlockX()) - expand;
        int maxX = Math.max(minBound.getBlockX(), maxBound.getBlockX()) + expand;
        int minY = Math.min(minBound.getBlockY(), maxBound.getBlockY()) - expand;
        int maxY = Math.max(minBound.getBlockY(), maxBound.getBlockY()) + expand;
        int minZ = Math.min(minBound.getBlockZ(), maxBound.getBlockZ()) - expand;
        int maxZ = Math.max(minBound.getBlockZ(), maxBound.getBlockZ()) + expand;

        String worldName = world.getName();

        int worldMinY = world.getMinHeight();
        int worldMaxY = world.getMaxHeight() - 1;
        minY = Math.max(worldMinY, minY);
        maxY = Math.min(worldMaxY, maxY);
        if (minY > maxY) {
            return CompletableFuture.completedFuture(null);
        }
        final int captureMinY = minY;
        final int captureMaxY = maxY;

        List<CompletableFuture<Void>> chunkTasks = new ArrayList<>();
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                final int cx = chunkX;
                final int cz = chunkZ;
                final int startX = Math.max(minX, cx << 4);
                final int endX = Math.min(maxX, (cx << 4) + 15);
                final int startZ = Math.max(minZ, cz << 4);
                final int endZ = Math.min(maxZ, (cz << 4) + 15);
                CompletableFuture<Void> chunkFuture = new CompletableFuture<>();
                chunkTasks.add(chunkFuture);
                plugin.getServer().getRegionScheduler().run(plugin, world, cx, cz, task -> {
                    try {
                        Map<BlockKey, BlockData> captured = new LinkedHashMap<>();
                        for (int x = startX; x <= endX; x++) {
                            for (int z = startZ; z <= endZ; z++) {
                                for (int y = captureMinY; y <= captureMaxY; y++) {
                                    Block block = world.getBlockAt(x, y, z);
                                    captured.put(new BlockKey(worldName, x, y, z), block.getBlockData().clone());
                                }
                            }
                        }
                        synchronized (originalArenaBlocks) {
                            for (Map.Entry<BlockKey, BlockData> entry : captured.entrySet()) {
                                originalArenaBlocks.putIfAbsent(entry.getKey(), entry.getValue());
                            }
                        }
                        chunkFuture.complete(null);
                    } catch (Throwable throwable) {
                        chunkFuture.completeExceptionally(throwable);
                    }
                });
            }
        }
        return CompletableFuture.allOf(chunkTasks.toArray(new CompletableFuture[0]));
    }

    public boolean isInArenaRollbackArea(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        String arenaWorld = arena.getWorldName();
        if (arenaWorld != null && !arenaWorld.isBlank()
                && !arenaWorld.equalsIgnoreCase(location.getWorld().getName())) {
            return false;
        }

        Location minBound = arena.getMinBound();
        Location maxBound = arena.getMaxBound();
        if (minBound == null || maxBound == null || minBound.getWorld() == null || maxBound.getWorld() == null) {
            return false;
        }
        if (!minBound.getWorld().getName().equalsIgnoreCase(location.getWorld().getName())
                || !maxBound.getWorld().getName().equalsIgnoreCase(location.getWorld().getName())) {
            return false;
        }

        int expand = getRollbackBoundsExpand();
        int minX = Math.min(minBound.getBlockX(), maxBound.getBlockX()) - expand;
        int maxX = Math.max(minBound.getBlockX(), maxBound.getBlockX()) + expand;
        int minY = Math.min(minBound.getBlockY(), maxBound.getBlockY()) - expand;
        int maxY = Math.max(minBound.getBlockY(), maxBound.getBlockY()) + expand;
        int minZ = Math.min(minBound.getBlockZ(), maxBound.getBlockZ()) - expand;
        int maxZ = Math.max(minBound.getBlockZ(), maxBound.getBlockZ()) + expand;

        int worldMinY = location.getWorld().getMinHeight();
        int worldMaxY = location.getWorld().getMaxHeight() - 1;
        minY = Math.max(worldMinY, minY);
        maxY = Math.min(worldMaxY, maxY);

        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public void ensureSnapshot(Player player) {
        if (!mode.isRestoreBackupAfterMatch()) {
            return;
        }
        if (player == null) {
            return;
        }
        if (playerA.equals(player.getUniqueId())) {
            if (snapshotA == null) {
                snapshotA = new PlayerSnapshot(player);
                matchManager.addPendingSnapshot(player.getUniqueId(), snapshotA);
            }
        } else if (playerB.equals(player.getUniqueId())) {
            if (snapshotB == null) {
                snapshotB = new PlayerSnapshot(player);
                matchManager.addPendingSnapshot(player.getUniqueId(), snapshotB);
            }
        }
    }

    private void startCountdown(Player p1, Player p2) {
        state = MatchState.COUNTDOWN;
        AtomicInteger seconds = new AtomicInteger(getCountdownSeconds());
        MessageUtil.send(p1, "match_vs",
            Placeholder.unparsed("opponent", p2.getName()),
            Placeholder.unparsed("mode", mode.getDisplayName()));
        MessageUtil.send(p2, "match_vs",
            Placeholder.unparsed("opponent", p1.getName()),
            Placeholder.unparsed("mode", mode.getDisplayName()));
        countdownTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!p1.isOnline() || !p2.isOnline()) {
                task.cancel();
                matchManager.endMatch(this, null, MatchManager.REASON_PLAYER_OFFLINE);
                return;
            }
            int current = seconds.getAndDecrement();
            if (current <= 0) {
                task.cancel();
                startFight(p1, p2);
                return;
            }
            showCountdown(p1, current);
            showCountdown(p2, current);
        }, 1L, 20L);
    }

    private int getCountdownSeconds() {
        return Math.max(1, plugin.getConfig().getInt("match.countdown-seconds", 5));
    }

    private Duration getCountdownFadeIn() {
        return Duration.ofMillis(Math.max(0L, plugin.getConfig().getLong("match.countdown-title.fade-in-ms", 0L)));
    }

    private Duration getCountdownStay() {
        return Duration.ofMillis(Math.max(100L, plugin.getConfig().getLong("match.countdown-title.stay-ms", 800L)));
    }

    private Duration getCountdownFadeOut() {
        return Duration.ofMillis(Math.max(0L, plugin.getConfig().getLong("match.countdown-title.fade-out-ms", 200L)));
    }

    private void showCountdown(Player player, int seconds) {
        SchedulerUtil.runOnPlayer(plugin, player, () -> {
            String plainTitle = MessageUtil.getPlainMessage("countdown_title",
                Placeholder.unparsed("seconds", String.valueOf(seconds)));
            Component titleText;
            Component animatedTitle = getAnimatedCountdownTitle(player, seconds);
            if (animatedTitle != null) {
                titleText = animatedTitle;
            } else if (plainTitle == null || plainTitle.isBlank() || plainTitle.equals("countdown_title")) {
                titleText = Component.text(String.valueOf(seconds));
            } else {
                titleText = MessageUtil.message("countdown_title",
                    Placeholder.unparsed("seconds", String.valueOf(seconds)));
            }

            Component subtitleText = getAnimatedCountdownSubtitle(player, seconds);
            if (subtitleText == null) {
                subtitleText = MessageUtil.message("countdown_subtitle");
            }

            Title title = Title.title(
                titleText,
                subtitleText,
                Title.Times.times(getCountdownFadeIn(), getCountdownStay(), getCountdownFadeOut()));
            player.showTitle(title);
            float pitch = seconds <= 1 ? 1.6f : 1.2f;
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, pitch);
            MessageUtil.send(player, "countdown_chat", Placeholder.unparsed("seconds", String.valueOf(seconds)));
        });
    }

    private Component getAnimatedCountdownTitle(Player viewer, int seconds) {
        return getAnimatedTitleFrame(viewer, seconds, "titlemsg.countdown.frames");
    }

    private Component getAnimatedCountdownSubtitle(Player viewer, int seconds) {
        return getAnimatedTitleFrame(viewer, seconds, "titlemsg.countdown.subtitle-frames");
    }

    private Component getAnimatedTitleFrame(Player viewer, int seconds, String path) {
        if (!plugin.getConfig().getBoolean("titlemsg.countdown.enabled", false)) {
            return null;
        }
        List<String> frames = plugin.getConfig().getStringList(path);
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        int frameOffset = Math.max(1, plugin.getConfig().getInt("titlemsg.countdown.frame-offset", 1));
        int index = Math.floorMod(seconds * frameOffset, frames.size());
        String frame = frames.get(index);
        if (frame == null || frame.isBlank()) {
            return null;
        }
        frame = frame.replace("{seconds}", String.valueOf(seconds));
        frame = normalizeMultiline(frame);
        if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                frame = PlaceholderAPI.setPlaceholders(viewer, frame);
            } catch (Throwable ignored) {
            }
        }
        return LegacyComponentSerializer.legacySection().deserialize(colorizeMixedText(frame));
    }

    private String normalizeMultiline(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input
                .replace("\\n", "\n")
                .replace("{nl}", "\n")
                .replace("<nl>", "\n");
    }

    private void startFight(Player p1, Player p2) {
        roundResolving = false;
        resolvingRound = 0;
        state = MatchState.FIGHTING;
        applyDuelPlayerListIsolation(p1, p2);
        matchManager.getPkManager().forceEnable(playerA);
        matchManager.getPkManager().forceEnable(playerB);
        Location spawn1 = arena.getSpawn1();
        Location spawn2 = arena.getSpawn2();
        if (spawn1 != null) {
            SchedulerUtil.teleport(plugin, p1, spawn1);
        }
        if (spawn2 != null) {
            SchedulerUtil.teleport(plugin, p2, spawn2);
        }
        equipPlayer(p1);
        equipPlayer(p2);
        if (currentRound <= 1) {
            MessageUtil.send(p1, "match_start");
            MessageUtil.send(p2, "match_start");
            matchManager.onMatchStarted(this);
        } else {
            sendRoundStartMessage(p1, p2);
            sendRoundStartMessage(p2, p1);
        }
        matchManager.onArenaFightStarted(arena);
        startHudTask();
        updateRoundBossBar();
        updateHealthBossBar();
        updateRoundScoreboard();
    }

    private void startNextRound() {
        startNextRound(0L);
    }

    private void startNextRound(long countdownDelayTicks) {
        rollbackArenaChangesAsync().thenCompose(v -> cleanupArenaTransientArtifactsAsync()).whenComplete((rv, rex) -> {
            state = MatchState.PREPARING;
            // Close round-resolving window before spectator cleanup so delayed retry tasks
            // cannot re-enter eliminated spectator mode during round reset/countdown.
            roundResolving = false;
            resolvingRound = 0;
            matchManager.clearEliminatedSpectator(playerA);
            matchManager.clearEliminatedSpectator(playerB);

            Player p1 = plugin.getServer().getPlayer(playerA);
            Player p2 = plugin.getServer().getPlayer(playerB);
            if (p1 == null || p2 == null || !p1.isOnline() || !p2.isOnline()) {
                matchManager.endMatch(this, null, MatchManager.REASON_PLAYER_OFFLINE);
                return;
            }
            if (arena.getSpawn1() == null || arena.getSpawn2() == null) {
                matchManager.endMatch(this, null, MatchManager.REASON_ARENA_SPAWN_UNSET);
                return;
            }

            CompletableFuture<Void> f1 = prepareForRoundTeleport(p1, arena.getSpawn1());
            CompletableFuture<Void> f2 = prepareForRoundTeleport(p2, arena.getSpawn2());
            CompletableFuture.allOf(f1, f2).thenCompose(v -> captureArenaBaselineAsync()).whenComplete((v, ex) -> {
                if (ex != null) {
                    matchManager.endMatch(this, null, MatchManager.REASON_TELEPORT_FAILED);
                    return;
                }
                if (countdownDelayTicks <= 0L) {
                    startCountdown(p1, p2);
                    return;
                }
                if (nextRoundTask != null) {
                    nextRoundTask.cancel();
                    nextRoundTask = null;
                }
                nextRoundTask = plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
                    nextRoundTask = null;
                    if (!p1.isOnline() || !p2.isOnline()) {
                        matchManager.endMatch(this, null, MatchManager.REASON_PLAYER_OFFLINE);
                        return;
                    }
                    if (matchManager.getMatch(p1) != this || matchManager.getMatch(p2) != this) {
                        return;
                    }
                    startCountdown(p1, p2);
                }, countdownDelayTicks);
            });
        });
    }

    private void equipPlayer(Player player) {
        SchedulerUtil.runOnPlayer(plugin, player, () -> {
            if (!mode.isUsePlayerInventory()) {
                PlayerStateUtil.reset(player);
                mode.getKit().getItems().forEach(item -> player.getInventory().addItem(item.clone()));
                List<ItemStack> armor = mode.getKit().getArmor();
                if (!armor.isEmpty()) {
                    ItemStack[] armorContents = new ItemStack[4];
                    for (int i = 0; i < Math.min(4, armor.size()); i++) {
                        armorContents[i] = armor.get(i).clone();
                    }
                    player.getInventory().setArmorContents(armorContents);
                }
                for (PotionEffect effect : mode.getKit().getPotionEffects()) {
                    player.addPotionEffect(effect);
                }
            }
            if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(mode.getSettings().getMaxHealth());
            }
            player.setHealth(mode.getSettings().getMaxHealth());
            player.setFoodLevel(mode.getSettings().getHunger());
            player.setSaturation(mode.getSettings().getSaturation());
            player.setNoDamageTicks(mode.getSettings().getNoDamageTicks());
            clearCombatResidueOnPlayer(player);
            if (!mode.isUsePlayerInventory()) {
                enforceKitInventory(player);
            }
            combatSnapshots.put(player.getUniqueId(), new CombatSnapshot(player));
        });
    }

    private CompletableFuture<Void> cleanupArenaTransientArtifactsAsync() {
        if (!mode.getSettings().isBuildEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        Location minBound = arena.getMinBound();
        Location maxBound = arena.getMaxBound();
        if (minBound == null || maxBound == null || minBound.getWorld() == null || maxBound.getWorld() == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (!minBound.getWorld().getName().equalsIgnoreCase(maxBound.getWorld().getName())) {
            return CompletableFuture.completedFuture(null);
        }

        World world = minBound.getWorld();
        int expand = getRollbackBoundsExpand();
        int minX = Math.min(minBound.getBlockX(), maxBound.getBlockX()) - expand;
        int maxX = Math.max(minBound.getBlockX(), maxBound.getBlockX()) + expand;
        int minY = Math.min(minBound.getBlockY(), maxBound.getBlockY()) - expand;
        int maxY = Math.max(minBound.getBlockY(), maxBound.getBlockY()) + expand;
        int minZ = Math.min(minBound.getBlockZ(), maxBound.getBlockZ()) - expand;
        int maxZ = Math.max(minBound.getBlockZ(), maxBound.getBlockZ()) + expand;

        int worldMinY = world.getMinHeight();
        int worldMaxY = world.getMaxHeight() - 1;
        minY = Math.max(worldMinY, minY);
        maxY = Math.min(worldMaxY, maxY);
        if (minY > maxY) {
            return CompletableFuture.completedFuture(null);
        }

        final int areaMinX = minX;
        final int areaMaxX = maxX;
        final int areaMinY = minY;
        final int areaMaxY = maxY;
        final int areaMinZ = minZ;
        final int areaMaxZ = maxZ;

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                final int cx = chunkX;
                final int cz = chunkZ;
                final int startX = Math.max(areaMinX, cx << 4);
                final int endX = Math.min(areaMaxX, (cx << 4) + 15);
                final int startZ = Math.max(areaMinZ, cz << 4);
                final int endZ = Math.min(areaMaxZ, (cz << 4) + 15);
                CompletableFuture<Void> chunkFuture = new CompletableFuture<>();
                futures.add(chunkFuture);
                plugin.getServer().getRegionScheduler().run(plugin, world, cx, cz, task -> {
                    try {
                        for (Entity entity : world.getChunkAt(cx, cz).getEntities()) {
                            if (!(entity instanceof AbstractArrow
                                    || entity instanceof Trident
                                    || entity instanceof EnderCrystal
                                    || entity instanceof TNTPrimed
                                    || entity instanceof FallingBlock)) {
                                continue;
                            }
                            Location location = entity.getLocation();
                            if (location.getBlockX() < areaMinX || location.getBlockX() > areaMaxX
                                    || location.getBlockY() < areaMinY || location.getBlockY() > areaMaxY
                                    || location.getBlockZ() < areaMinZ || location.getBlockZ() > areaMaxZ) {
                                continue;
                            }
                            entity.remove();
                        }

                        for (int x = startX; x <= endX; x++) {
                            for (int z = startZ; z <= endZ; z++) {
                                for (int y = areaMinY; y <= areaMaxY; y++) {
                                    Block block = world.getBlockAt(x, y, z);
                                    Material material = block.getType();
                                    if (material == Material.FIRE || material == Material.SOUL_FIRE) {
                                        block.setType(Material.AIR, false);
                                    }
                                }
                            }
                        }

                        chunkFuture.complete(null);
                    } catch (Throwable throwable) {
                        chunkFuture.completeExceptionally(throwable);
                    }
                });
            }
        }

        Player p1 = plugin.getServer().getPlayer(playerA);
        if (p1 != null && p1.isOnline()) {
            futures.add(SchedulerUtil.runOnPlayerFuture(plugin, p1, () -> clearCombatResidueOnPlayer(p1)));
        }
        Player p2 = plugin.getServer().getPlayer(playerB);
        if (p2 != null && p2.isOnline()) {
            futures.add(SchedulerUtil.runOnPlayerFuture(plugin, p2, () -> clearCombatResidueOnPlayer(p2)));
        }

        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private void clearCombatResidueOnPlayer(Player player) {
        if (player == null) {
            return;
        }
        try {
            player.setArrowsInBody(0);
        } catch (Throwable ignored) {
        }
        try {
            player.setBeeStingersInBody(0);
        } catch (Throwable ignored) {
        }
        try {
            player.setArrowCooldown(0);
        } catch (Throwable ignored) {
        }
        try {
            player.setFireTicks(0);
        } catch (Throwable ignored) {
        }
    }

    private void enforceKitInventory(Player player) {
        List<ItemStack> allowed = new ArrayList<>();
        for (ItemStack item : mode.getKit().getItems()) {
            if (item != null && !item.getType().isAir()) {
                allowed.add(item.clone());
            }
        }
        for (ItemStack item : mode.getKit().getArmor()) {
            if (item != null && !item.getType().isAir()) {
                allowed.add(item.clone());
            }
        }

        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            ItemStack item = storage[i];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            int keep = takeAllowed(item, allowed);
            if (keep <= 0) {
                storage[i] = null;
            } else if (keep < item.getAmount()) {
                item.setAmount(keep);
                storage[i] = item;
            }
        }
        player.getInventory().setStorageContents(storage);

        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            int keep = takeAllowed(offhand, allowed);
            if (keep <= 0) {
                player.getInventory().setItemInOffHand(null);
            } else if (keep < offhand.getAmount()) {
                offhand.setAmount(keep);
                player.getInventory().setItemInOffHand(offhand);
            }
        }

        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            ItemStack item = armor[i];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            int keep = takeAllowed(item, allowed);
            if (keep <= 0) {
                armor[i] = null;
            } else if (keep < item.getAmount()) {
                item.setAmount(keep);
                armor[i] = item;
            }
        }
        player.getInventory().setArmorContents(armor);
    }

    private int takeAllowed(ItemStack item, List<ItemStack> allowed) {
        int need = item.getAmount();
        int kept = 0;
        for (int i = 0; i < allowed.size() && need > 0; i++) {
            ItemStack allow = allowed.get(i);
            if (allow == null || allow.getType().isAir()) {
                continue;
            }
            if (!allow.isSimilar(item)) {
                continue;
            }
            int take = Math.min(need, allow.getAmount());
            kept += take;
            need -= take;
            int remaining = allow.getAmount() - take;
            if (remaining <= 0) {
                allowed.remove(i);
                i--;
            } else {
                allow.setAmount(remaining);
            }
        }
        return kept;
    }

    public void end(UUID winner, String reason) {
        roundResolving = false;
        state = MatchState.ENDING;
        matchManager.clearEliminatedSpectator(playerA);
        matchManager.clearEliminatedSpectator(playerB);
        debugRestore("end winner=" + winner + " reason=" + reason + " p1=" + playerA + " p2=" + playerB);
        stopHudTask();
        clearRoundBoards();
        clearDuelTabs();
        clearDuelPlayerListIsolation();
        clearRoundBossBar();
        clearHealthBossBar();
        if (nextRoundTask != null) {
            nextRoundTask.cancel();
            nextRoundTask = null;
        }
        if (countdownTask != null) {
            countdownTask.cancel();
        }
        Player p1 = plugin.getServer().getPlayer(playerA);
        Player p2 = plugin.getServer().getPlayer(playerB);
        if (p1 != null) {
            handleEndForPlayer(p1, winner, reason, p1.isDead());
        }
        if (p2 != null) {
            handleEndForPlayer(p2, winner, reason, p2.isDead());
        }
    }

    private void handleEndForPlayer(Player player, UUID winner, String reason, boolean wasDeadAtEnd) {
        SchedulerUtil.runOnPlayer(plugin, player, () -> {
            removeRoundBossBarFor(player);
            PlayerSnapshot snapshot = player.getUniqueId().equals(playerA) ? snapshotA : snapshotB;
            Location returnLocation = player.getUniqueId().equals(playerA) ? returnLocationA : returnLocationB;
            debugRestore("handleEndForPlayer player=" + player.getUniqueId()
                    + " wasDeadAtEnd=" + wasDeadAtEnd
                    + " winner=" + winner
                    + " reason=" + reason
                    + " snapshot=" + (snapshot != null ? snapshot.debugFingerprint() : "null"));
            // Use the death state captured at match end dispatch time to avoid scheduler timing races
            // where the player respawns before this runnable executes.
            if (wasDeadAtEnd) {
                restorePlayerScoreboard(player);
                if (!mode.isRestoreBackupAfterMatch()) {
                    normalizePostMatchState(player);
                    scheduleDeferredTeleport(player.getUniqueId(), returnLocation);
                    return;
                }
                // 玩家仍處於死亡畫面時，將快照存入待恢復，等重生事件時套回
                if (snapshot != null) {
                    debugRestore("deferRestore player=" + player.getUniqueId());
                    matchManager.addPendingSnapshot(player.getUniqueId(), snapshot);
                    scheduleDeferredRestoreRetry(player.getUniqueId());
                }
                return;
            }
            restorePlayerScoreboard(player);
            if (winner != null && player.getUniqueId().equals(winner)) {
                Title title = Title.title(MessageUtil.message("win_title"),
                    MessageUtil.message("win_subtitle"));
                player.showTitle(title);
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            } else if (winner != null) {
                Title title = Title.title(MessageUtil.message("lose_title"),
                    MessageUtil.message("lose_subtitle"));
                player.showTitle(title);
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.9f);
            } else {
                Title title = Title.title(MessageUtil.message("end_title"), MessageUtil.message(reason));
                player.showTitle(title);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
            if (!mode.isRestoreBackupAfterMatch()) {
                normalizePostMatchState(player);
                if (returnLocation != null && returnLocation.getWorld() != null) {
                    player.teleportAsync(returnLocation);
                } else {
                    player.teleportAsync(player.getWorld().getSpawnLocation());
                }
                return;
            }
            if (snapshot != null) {
                // 離開當下先存 pending，等 Scheduler 一個 tick 後再套回，避免與其他插件/事件搶寫背包
                matchManager.addPendingSnapshot(player.getUniqueId(), snapshot);
                SchedulerUtil.runOnPlayer(plugin, player, () -> {
                    com.pvparena.model.PlayerSnapshot pending = matchManager.consumePendingRestore(player);
                    if (pending == null) {
                        debugRestore("consumePendingRestore returned null for " + player.getUniqueId());
                        return;
                    }
                    debugRestore("applyPendingRestore player=" + player.getUniqueId() + " " + pending.debugFingerprint());
                    pending.restore(player);
                        com.pvparena.hook.husksync.HuskSyncWriteBack.writeBackCurrentState(plugin, player);
                    Location back = pending.getLocation();
                    if (back != null && back.getWorld() != null) {
                        player.teleportAsync(back);
                    } else {
                        player.teleportAsync(player.getWorld().getSpawnLocation());
                    }
                });
            } else {
                PlayerStateUtil.reset(player);
                player.teleportAsync(player.getWorld().getSpawnLocation());
            }
        });
    }

    private void scheduleDeferredTeleport(UUID playerId, Location target) {
        if (target == null || target.getWorld() == null) {
            return;
        }
        long[] delays = new long[]{5L, 20L, 60L};
        for (long delay : delays) {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
                Player online = plugin.getServer().getPlayer(playerId);
                if (online == null || !online.isOnline()) {
                    return;
                }
                SchedulerUtil.runOnPlayer(plugin, online, () -> {
                    if (!online.isOnline() || online.isDead() || matchManager.isInMatch(online)) {
                        return;
                    }
                    online.teleportAsync(target);
                });
            }, delay);
        }
    }

    private void normalizePostMatchState(Player player) {
        if (player == null) {
            return;
        }
        try {
            player.setInvulnerable(false);
        } catch (Throwable ignored) {
        }
        try {
            player.setInvisible(false);
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
            player.setSilent(false);
        } catch (Throwable ignored) {
        }
    }

    private void debugRestore(String msg) {
        try {
            if (plugin.getConfig().getBoolean("debug.restore", false)) {
                plugin.getLogger().info("[RestoreDebug][MatchSession] " + msg);
            }
        } catch (Throwable ignored) {
        }
    }

    private void scheduleDeferredRestoreRetry(UUID playerId) {
        long[] delays = new long[]{5L, 20L, 60L, 120L, 200L};
        for (long delay : delays) {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
                Player online = plugin.getServer().getPlayer(playerId);
                if (online == null || !online.isOnline()) {
                    debugRestore("deferredRetry offline player=" + playerId + " delay=" + delay);
                    return;
                }
                SchedulerUtil.runOnPlayer(plugin, online, () -> {
                    if (!online.isOnline()) {
                        return;
                    }
                    if (online.isDead()) {
                        debugRestore("deferredRetry still-dead player=" + playerId + " delay=" + delay);
                        return;
                    }
                    if (matchManager.isInMatch(online)) {
                        debugRestore("deferredRetry in-match player=" + playerId + " delay=" + delay);
                        return;
                    }
                    if (!matchManager.hasPendingRestore(playerId)) {
                        debugRestore("deferredRetry no-pending player=" + playerId + " delay=" + delay);
                        return;
                    }
                    PlayerSnapshot pending = matchManager.consumePendingRestore(online);
                    if (pending == null) {
                        debugRestore("deferredRetry consume-null player=" + playerId + " delay=" + delay);
                        return;
                    }
                    debugRestore("deferredRetry apply player=" + playerId + " delay=" + delay + " " + pending.debugFingerprint());
                    pending.restore(online);
                    com.pvparena.hook.husksync.HuskSyncWriteBack.writeBackCurrentState(plugin, online);
                    Location back = pending.getLocation();
                    if (back != null && back.getWorld() != null) {
                        online.teleportAsync(back);
                    }
                });
            }, delay);
        }
    }

    private void sendRoundStartMessage(Player player, Player opponent) {
        MessageUtil.send(player, "round_start",
                Placeholder.unparsed("round", String.valueOf(currentRound)),
                Placeholder.unparsed("rounds_to_win", String.valueOf(mode.getSettings().getRoundsToWin())),
                Placeholder.unparsed("your_score", String.valueOf(getRoundsWon(player.getUniqueId()))),
                Placeholder.unparsed("opponent_score", String.valueOf(getRoundsWon(opponent.getUniqueId()))));
    }

    private void sendRoundResultMessage(Player player, boolean winRound) {
        UUID playerId = player.getUniqueId();
        UUID opponentId = getOpponent(playerId);
        MessageUtil.send(player, winRound ? "round_win" : "round_lose",
                Placeholder.unparsed("round", String.valueOf(currentRound)),
                Placeholder.unparsed("rounds_to_win", String.valueOf(mode.getSettings().getRoundsToWin())),
                Placeholder.unparsed("your_score", String.valueOf(getRoundsWon(playerId))),
                Placeholder.unparsed("opponent_score", String.valueOf(getRoundsWon(opponentId))));

        String titleKey = winRound ? "round_win_title" : "round_lose_title";
        String subtitleKey = winRound ? "round_win_subtitle" : "round_lose_subtitle";
        String plainTitle = MessageUtil.getPlainMessage(titleKey,
            Placeholder.unparsed("round", String.valueOf(currentRound)),
            Placeholder.unparsed("your_score", String.valueOf(getRoundsWon(playerId))),
            Placeholder.unparsed("opponent_score", String.valueOf(getRoundsWon(opponentId))));
        if (plainTitle != null && !plainTitle.isBlank() && !plainTitle.equals(titleKey)) {
            Title roundTitle = Title.title(
                MessageUtil.message(titleKey,
                    Placeholder.unparsed("round", String.valueOf(currentRound)),
                    Placeholder.unparsed("your_score", String.valueOf(getRoundsWon(playerId))),
                    Placeholder.unparsed("opponent_score", String.valueOf(getRoundsWon(opponentId)))),
                MessageUtil.message(subtitleKey,
                    Placeholder.unparsed("round", String.valueOf(currentRound)),
                    Placeholder.unparsed("your_score", String.valueOf(getRoundsWon(playerId))),
                    Placeholder.unparsed("opponent_score", String.valueOf(getRoundsWon(opponentId)))));
            player.showTitle(roundTitle);
        }
    }

    private void sendRoundNextMessage() {
        Player p1 = plugin.getServer().getPlayer(playerA);
        Player p2 = plugin.getServer().getPlayer(playerB);
        if (p1 != null) {
            MessageUtil.send(p1, "round_next",
                    Placeholder.unparsed("round", String.valueOf(currentRound)),
                    Placeholder.unparsed("rounds_to_win", String.valueOf(mode.getSettings().getRoundsToWin())),
                    Placeholder.unparsed("your_score", String.valueOf(getRoundsWon(playerA))),
                    Placeholder.unparsed("opponent_score", String.valueOf(getRoundsWon(playerB))));
        }
        if (p2 != null) {
            MessageUtil.send(p2, "round_next",
                    Placeholder.unparsed("round", String.valueOf(currentRound)),
                    Placeholder.unparsed("rounds_to_win", String.valueOf(mode.getSettings().getRoundsToWin())),
                    Placeholder.unparsed("your_score", String.valueOf(getRoundsWon(playerB))),
                    Placeholder.unparsed("opponent_score", String.valueOf(getRoundsWon(playerA))));
        }
    }

    private boolean isRoundScoreboardEnabled() {
        return plugin.getConfig().getBoolean("round-scoreboard.enabled", true);
    }

    private boolean isRoundActionbarFallbackEnabled() {
        return plugin.getConfig().getBoolean("round-scoreboard.fallback-actionbar", false);
    }

    private long getRoundScoreboardUpdateInterval() {
        return Math.max(1L, plugin.getConfig().getLong("round-scoreboard.update-interval-ticks", 10L));
    }

    private long getRoundBossBarUpdateInterval() {
        return Math.max(1L, plugin.getConfig().getLong("round-scoreboard.bossbar-update-interval-ticks", 10L));
    }

    private boolean isRoundBossBarEnabled() {
        return plugin.getConfig().getBoolean("round-scoreboard.bossbar-enabled", true);
    }

    private void updateRoundBossBar() {
        if (!isRoundBossBarEnabled()) {
            clearRoundBossBar();
            return;
        }
        Player p1 = plugin.getServer().getPlayer(playerA);
        Player p2 = plugin.getServer().getPlayer(playerB);
        if (p1 != null) {
            SchedulerUtil.runOnPlayer(plugin, p1, () -> roundBossBarA = applyRoundBossBar(roundBossBarA, p1, p2));
        }
        if (p2 != null) {
            SchedulerUtil.runOnPlayer(plugin, p2, () -> roundBossBarB = applyRoundBossBar(roundBossBarB, p2, p1));
        }
    }

    private boolean isHealthBossBarEnabled() {
        return plugin.getConfig().getBoolean("health-bar.enabled", true);
    }

    private long getHealthBossBarUpdateInterval() {
        return Math.max(1L, plugin.getConfig().getLong("health-bar.update-interval-ticks", 10L));
    }

    private boolean isDuelTabEnabled() {
        return plugin.getConfig().getBoolean("duel-tab.enabled", true);
    }

    private boolean isDuelPlayerListIsolationEnabled() {
        return plugin.getConfig().getBoolean("duel-tab.isolate-match-players-in-tab", false);
    }

    private long getDuelTabUpdateInterval() {
        return Math.max(1L, plugin.getConfig().getLong("duel-tab.update-interval-ticks", 10L));
    }

    private long getHudUpdateInterval() {
        long min = Long.MAX_VALUE;
        if (isRoundBossBarEnabled()) {
            min = Math.min(min, getRoundBossBarUpdateInterval());
        }
        if (isHealthBossBarEnabled()) {
            min = Math.min(min, getHealthBossBarUpdateInterval());
        }
        if (isRoundScoreboardEnabled()) {
            min = Math.min(min, getRoundScoreboardUpdateInterval());
        }
        if (isDuelTabEnabled()) {
            min = Math.min(min, getDuelTabUpdateInterval());
        }
        return min == Long.MAX_VALUE ? 10L : Math.max(1L, min);
    }

    private void startHudTask() {
        stopHudTask();
        if (!isRoundBossBarEnabled() && !isHealthBossBarEnabled() && !isRoundScoreboardEnabled() && !isDuelTabEnabled()) {
            return;
        }
        hudTickCounter = 0L;
        hudTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (state == MatchState.ENDING) {
                task.cancel();
                return;
            }
            Player p1 = plugin.getServer().getPlayer(playerA);
            Player p2 = plugin.getServer().getPlayer(playerB);
            if (p1 == null || p2 == null || !p1.isOnline() || !p2.isOnline()) {
                return;
            }
            hudTickCounter++;
            if (shouldRunHudPart(getRoundBossBarUpdateInterval())) {
                updateRoundBossBar();
            }
            if (shouldRunHudPart(getHealthBossBarUpdateInterval())) {
                updateHealthBossBar();
            }
            if (shouldRunHudPart(getRoundScoreboardUpdateInterval())) {
                updateRoundScoreboard();
            }
            if (shouldRunHudPart(getDuelTabUpdateInterval())) {
                updateDuelTabs();
            }
        }, 1L, getHudUpdateInterval());
    }

    private boolean shouldRunHudPart(long intervalTicks) {
        long safe = Math.max(1L, intervalTicks);
        return hudTickCounter % safe == 0L;
    }

    private void stopHudTask() {
        if (hudTask != null) {
            hudTask.cancel();
            hudTask = null;
        }
    }

    private void updateHealthBossBar() {
        if (!isHealthBossBarEnabled()) {
            clearHealthBossBar();
            return;
        }
        if (state != MatchState.FIGHTING) {
            clearHealthBossBar();
            return;
        }
        Player p1 = plugin.getServer().getPlayer(playerA);
        Player p2 = plugin.getServer().getPlayer(playerB);
        if (p1 != null) {
            SchedulerUtil.runOnPlayer(plugin, p1, () -> healthBossBarA = applyHealthBossBar(healthBossBarA, p1, p2));
        }
        if (p2 != null) {
            SchedulerUtil.runOnPlayer(plugin, p2, () -> healthBossBarB = applyHealthBossBar(healthBossBarB, p2, p1));
        }
    }

    private BossBar applyHealthBossBar(BossBar bar, Player viewer, Player opponent) {
        if (opponent == null) {
            return bar;
        }
        double maxHealth = opponent.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null
            ? opponent.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue()
            : opponent.getMaxHealth();
        if (maxHealth <= 0) {
            maxHealth = 20.0;
        }
        double current = Math.max(0.0, Math.min(maxHealth, opponent.getHealth()));
        double progress = Math.max(0.0, Math.min(1.0, current / maxHealth));

        String plain = MessageUtil.getPlainMessage("health_bossbar_title",
            Placeholder.unparsed("opponent", opponent.getName()),
            Placeholder.unparsed("health", String.valueOf((int) Math.ceil(current))),
            Placeholder.unparsed("max", String.valueOf((int) Math.ceil(maxHealth))),
            Placeholder.unparsed("percent", String.valueOf((int) Math.round(progress * 100.0))));
        String title = (plain == null || plain.isBlank() || plain.equals("health_bossbar_title"))
            ? opponent.getName() + " HP " + (int) Math.ceil(current) + "/" + (int) Math.ceil(maxHealth)
            : plain;

        BarColor color = parseBarColor(plugin.getConfig().getString("health-bar.color", "RED"), BarColor.RED);
        BarStyle style = parseBarStyle(plugin.getConfig().getString("health-bar.style", "SOLID"), BarStyle.SOLID);

        BossBar result = bar;
        if (result == null) {
            result = plugin.getServer().createBossBar(title, color, style);
        } else {
            result.setTitle(title);
            result.setColor(color);
            result.setStyle(style);
        }
        result.setProgress(progress);
        if (!result.getPlayers().contains(viewer)) {
            result.addPlayer(viewer);
        }
        result.setVisible(true);
        return result;
    }

    private void clearHealthBossBar() {
        Player p1 = plugin.getServer().getPlayer(playerA);
        Player p2 = plugin.getServer().getPlayer(playerB);
        if (p1 != null) {
            SchedulerUtil.runOnPlayer(plugin, p1, () -> removeHealthBossBarFor(p1));
        }
        if (p2 != null) {
            SchedulerUtil.runOnPlayer(plugin, p2, () -> removeHealthBossBarFor(p2));
        }
        try {
            if (healthBossBarA != null) {
                healthBossBarA.removeAll();
                healthBossBarA = null;
            }
            if (healthBossBarB != null) {
                healthBossBarB.removeAll();
                healthBossBarB = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private void removeHealthBossBarFor(Player player) {
        if (player == null) {
            return;
        }
        try {
            if (healthBossBarA != null) {
                healthBossBarA.removePlayer(player);
                healthBossBarA.setVisible(false);
            }
            if (healthBossBarB != null) {
                healthBossBarB.removePlayer(player);
                healthBossBarB.setVisible(false);
            }
        } catch (Throwable ignored) {
        }
    }

    private BarColor parseBarColor(String raw, BarColor fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return BarColor.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private BarStyle parseBarStyle(String raw, BarStyle fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return BarStyle.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private BossBar applyRoundBossBar(BossBar bar, Player viewer, Player opponent) {
        int target = Math.max(1, mode.getSettings().getRoundsToWin());
        int yourScore = getRoundsWon(viewer.getUniqueId());
        int opponentScore = opponent != null ? getRoundsWon(opponent.getUniqueId()) : 0;
        String opponentName = opponent != null ? opponent.getName() : "-";
        String title = MessageUtil.getPlainMessage("round_bossbar_title",
            Placeholder.unparsed("mode", mode.getDisplayName()),
            Placeholder.unparsed("round", String.valueOf(currentRound)),
            Placeholder.unparsed("rounds_to_win", String.valueOf(target)),
            Placeholder.unparsed("your_score", String.valueOf(yourScore)),
            Placeholder.unparsed("opponent_score", String.valueOf(opponentScore)),
            Placeholder.unparsed("opponent", opponentName));
        if (title == null || title.isBlank() || title.equals("round_bossbar_title")) {
            title = MessageUtil.getPlainMessage("round_scoreboard_title",
                Placeholder.unparsed("round", String.valueOf(currentRound)),
                Placeholder.unparsed("rounds_to_win", String.valueOf(target)))
                + "  " + yourScore + "-" + opponentScore;
        }

        BossBar result = bar;
        if (result == null) {
            result = plugin.getServer().createBossBar(title, BarColor.BLUE, BarStyle.SEGMENTED_10);
        } else {
            result.setTitle(title);
        }
        result.setProgress(Math.max(0.0, Math.min(1.0, (double) yourScore / (double) target)));
        if (!result.getPlayers().contains(viewer)) {
            result.addPlayer(viewer);
        }
        result.setVisible(true);
        return result;
    }

    private void clearRoundBossBar() {
        Player p1 = plugin.getServer().getPlayer(playerA);
        Player p2 = plugin.getServer().getPlayer(playerB);
        if (p1 != null) {
            SchedulerUtil.runOnPlayer(plugin, p1, () -> removeRoundBossBarFor(p1));
        }
        if (p2 != null) {
            SchedulerUtil.runOnPlayer(plugin, p2, () -> removeRoundBossBarFor(p2));
        }
        try {
            if (roundBossBarA != null) {
                roundBossBarA.removeAll();
                roundBossBarA = null;
            }
            if (roundBossBarB != null) {
                roundBossBarB.removeAll();
                roundBossBarB = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private void removeRoundBossBarFor(Player player) {
        if (player == null) {
            return;
        }
        try {
            if (roundBossBarA != null) {
                roundBossBarA.removePlayer(player);
                roundBossBarA.setVisible(false);
            }
            if (roundBossBarB != null) {
                roundBossBarB.removePlayer(player);
                roundBossBarB.setVisible(false);
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateRoundScoreboard() {
        Player p1 = plugin.getServer().getPlayer(playerA);
        Player p2 = plugin.getServer().getPlayer(playerB);
        if (!isRoundScoreboardEnabled()) {
            clearRoundBoards();
            if (isRoundActionbarFallbackEnabled()) {
                sendRoundActionbar(p1, p2);
                sendRoundActionbar(p2, p1);
            }
            return;
        }
        updateRoundSidebarFor(p1, p2);
        updateRoundSidebarFor(p2, p1);
    }

    private void updateRoundSidebarFor(Player viewer, Player opponent) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        SchedulerUtil.runOnPlayer(plugin, viewer, () -> {
            if (!viewer.isOnline() || !isRoundScoreboardEnabled()) {
                deleteRoundSidebar(viewer.getUniqueId());
                return;
            }

            FastBoard board = roundSideboards.get(viewer.getUniqueId());
            if (board == null) {
                board = new FastBoard(viewer);
                roundSideboards.put(viewer.getUniqueId(), board);
            }

            String nextTitle = renderRoundText(viewer, opponent,
                    plugin.getConfig().getString("round-scoreboard.title", "&b&lPvPArena"));
            List<String> nextLines = buildRoundScoreboardLines(viewer, opponent);

            UUID viewerId = viewer.getUniqueId();
            String lastTitle = lastRoundBoardTitles.get(viewerId);
            List<String> lastLines = lastRoundBoardLines.get(viewerId);
            if (!nextTitle.equals(lastTitle)) {
                board.updateTitle(nextTitle);
                lastRoundBoardTitles.put(viewerId, nextTitle);
            }
            if (!nextLines.equals(lastLines)) {
                board.updateLines(nextLines);
                lastRoundBoardLines.put(viewerId, new ArrayList<>(nextLines));
            }
        });
    }

    private List<String> buildRoundScoreboardLines(Player viewer, Player opponent) {
        List<String> configured = plugin.getConfig().getStringList("round-scoreboard.lines");
        List<String> rawLines = new ArrayList<>();
        if (configured == null || configured.isEmpty()) {
            rawLines.add("&7&m----------------");
            rawLines.add("&fMode: &b{mode_name}");
            rawLines.add("&fArena: &a{arena}");
            rawLines.add("&fRound: &e{round}&7/&e{rounds_to_win}");
            rawLines.add("&fScore: &b{your_score}&7-&c{opponent_score}");
            rawLines.add("&fOpponent: &c{opponent_name}");
            rawLines.add("&7&m----------------");
        } else {
            rawLines.addAll(configured);
        }

        int maxLines = Math.max(1, Math.min(15, plugin.getConfig().getInt("round-scoreboard.max-lines", 15)));
        List<String> result = new ArrayList<>();
        for (String line : rawLines) {
            if (result.size() >= maxLines) {
                break;
            }
            result.add(renderRoundText(viewer, opponent, line));
        }
        return result;
    }

    private String renderRoundText(Player viewer, Player opponent, String source) {
        if (source == null) {
            return "";
        }
        String output = source;
        Map<String, String> variables = buildRoundVariables(viewer, opponent);
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            output = output.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                output = PlaceholderAPI.setPlaceholders(viewer, output);
            } catch (Throwable ignored) {
            }
        }
        return colorizeMixedText(output);
    }

    private Map<String, String> buildRoundVariables(Player viewer, Player opponent) {
        Map<String, String> variables = new HashMap<>();
        int targetRounds = Math.max(1, mode.getSettings().getRoundsToWin());
        int yourScore = getRoundsWon(viewer.getUniqueId());
        int opponentScore = opponent != null ? getRoundsWon(opponent.getUniqueId()) : 0;
        double yourHealth = Math.ceil(Math.max(0.0, viewer.getHealth()));
        double opponentHealth = opponent != null ? Math.ceil(Math.max(0.0, opponent.getHealth())) : 0.0;

        variables.put("mode", mode.getId() != null ? mode.getId() : "-");
        variables.put("mode_name", mode.getDisplayName() != null ? mode.getDisplayName() : "-");
        variables.put("arena", arena.getId() != null ? arena.getId() : "-");
        variables.put("state", state.name());
        variables.put("round", String.valueOf(currentRound));
        variables.put("rounds_to_win", String.valueOf(targetRounds));
        variables.put("your_name", viewer.getName());
        variables.put("opponent_name", opponent != null ? opponent.getName() : "-");
        variables.put("your_score", String.valueOf(yourScore));
        variables.put("opponent_score", String.valueOf(opponentScore));
        variables.put("your_health", String.valueOf((int) yourHealth));
        variables.put("opponent_health", String.valueOf((int) opponentHealth));
        variables.put("your_ping", String.valueOf(Math.max(0, viewer.getPing())));
        variables.put("opponent_ping", String.valueOf(opponent != null ? Math.max(0, opponent.getPing()) : 0));

        ConfigurationSection custom = plugin.getConfig().getConfigurationSection("round-scoreboard.variables");
        if (custom != null) {
            for (String key : custom.getKeys(false)) {
                String raw = custom.getString(key, "");
                if (raw == null) {
                    continue;
                }
                String value = raw;
                for (Map.Entry<String, String> entry : variables.entrySet()) {
                    value = value.replace("{" + entry.getKey() + "}", entry.getValue());
                }
                variables.put(key, value);
            }
        }
        return variables;
    }

    private void deleteRoundSidebar(UUID playerId) {
        FastBoard board = roundSideboards.remove(playerId);
        lastRoundBoardTitles.remove(playerId);
        lastRoundBoardLines.remove(playerId);
        if (board == null) {
            return;
        }
        try {
            board.delete();
        } catch (Throwable ignored) {
        }
    }

    private void restorePlayerScoreboard(Player player) {
        if (player == null) {
            return;
        }
        deleteRoundSidebar(player.getUniqueId());
        restoreDuelTab(player);
        restoreDuelHiddenPlayers(player);
    }

    private void clearRoundBoards() {
        if (roundSideboards.isEmpty()) {
            return;
        }
        List<UUID> ids = new ArrayList<>(roundSideboards.keySet());
        for (UUID id : ids) {
            deleteRoundSidebar(id);
        }
    }

    private void updateDuelTabs() {
        Player p1 = plugin.getServer().getPlayer(playerA);
        Player p2 = plugin.getServer().getPlayer(playerB);
        if (!isDuelTabEnabled()) {
            clearDuelTabs();
            return;
        }
        updateDuelTabFor(p1, p2);
        updateDuelTabFor(p2, p1);
    }

    private void updateDuelTabFor(Player viewer, Player opponent) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        SchedulerUtil.runOnPlayer(plugin, viewer, () -> {
            if (!viewer.isOnline() || !isDuelTabEnabled()) {
                restoreDuelTab(viewer);
                return;
            }
            duelTabSnapshots.computeIfAbsent(viewer.getUniqueId(), id ->
                new TabSnapshot(viewer.getPlayerListHeader(), viewer.getPlayerListFooter()));

            String header = renderDuelTabText(viewer, opponent,
                    plugin.getConfig().getString("duel-tab.header", "&b&lPvPArena\n&f{your_name} &7vs &f{opponent_name}"));
            String footer = renderDuelTabText(viewer, opponent,
                    plugin.getConfig().getString("duel-tab.footer", "&7Mode: &b{mode_name} &8| &7Round: &e{round}/{rounds_to_win}"));
            UUID viewerId = viewer.getUniqueId();
            String lastHeader = lastDuelTabHeaders.get(viewerId);
            String lastFooter = lastDuelTabFooters.get(viewerId);
            if (!header.equals(lastHeader) || !footer.equals(lastFooter)) {
                viewer.setPlayerListHeaderFooter(header, footer);
                lastDuelTabHeaders.put(viewerId, header);
                lastDuelTabFooters.put(viewerId, footer);
            }
        });
    }

    private String renderDuelTabText(Player viewer, Player opponent, String source) {
        if (source == null) {
            return "";
        }
        String output = normalizeMultiline(source);
        Map<String, String> variables = buildRoundVariables(viewer, opponent);
        ConfigurationSection custom = plugin.getConfig().getConfigurationSection("duel-tab.variables");
        if (custom != null) {
            for (String key : custom.getKeys(false)) {
                String raw = custom.getString(key, "");
                if (raw == null) {
                    continue;
                }
                String value = raw;
                for (Map.Entry<String, String> entry : variables.entrySet()) {
                    value = value.replace("{" + entry.getKey() + "}", entry.getValue());
                }
                variables.put(key, value);
            }
        }
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            output = output.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                output = PlaceholderAPI.setPlaceholders(viewer, output);
            } catch (Throwable ignored) {
            }
        }
        return colorizeMixedText(output);
    }

    private String colorizeMixedText(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String output = input;
        try {
            output = LegacyComponentSerializer.legacySection().serialize(
                    MiniMessage.miniMessage().deserialize(output));
        } catch (Throwable ignored) {
        }
        return ChatColor.translateAlternateColorCodes('&', output);
    }

    private void clearDuelTabs() {
        if (duelTabSnapshots.isEmpty()) {
            return;
        }
        List<UUID> ids = new ArrayList<>(duelTabSnapshots.keySet());
        for (UUID id : ids) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isOnline()) {
                restoreDuelTab(player);
            } else {
                duelTabSnapshots.remove(id);
            }
        }
    }

    private void restoreDuelTab(Player player) {
        if (player == null) {
            return;
        }
        lastDuelTabHeaders.remove(player.getUniqueId());
        lastDuelTabFooters.remove(player.getUniqueId());
        TabSnapshot snapshot = duelTabSnapshots.remove(player.getUniqueId());
        if (snapshot == null) {
            return;
        }
        try {
            player.setPlayerListHeaderFooter(snapshot.header(), snapshot.footer());
        } catch (Throwable ignored) {
        }
    }

    private void applyDuelPlayerListIsolation(Player p1, Player p2) {
        if (!isDuelPlayerListIsolationEnabled()) {
            clearDuelPlayerListIsolation();
            return;
        }
        applyDuelPlayerListIsolationFor(p1, p2);
        applyDuelPlayerListIsolationFor(p2, p1);
    }

    private void applyDuelPlayerListIsolationFor(Player viewer, Player opponent) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        SchedulerUtil.runOnPlayer(plugin, viewer, () -> {
            if (!viewer.isOnline() || !isDuelPlayerListIsolationEnabled()) {
                restoreDuelHiddenPlayers(viewer);
                return;
            }
            Set<UUID> hidden = duelHiddenPlayers.computeIfAbsent(viewer.getUniqueId(), id -> new HashSet<>());
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (online == null || !online.isOnline()) {
                    continue;
                }
                UUID targetId = online.getUniqueId();
                boolean keepVisible = targetId.equals(viewer.getUniqueId())
                        || (opponent != null && targetId.equals(opponent.getUniqueId()));
                try {
                    if (keepVisible) {
                        viewer.showPlayer(plugin, online);
                        hidden.remove(targetId);
                    } else {
                        viewer.hidePlayer(plugin, online);
                        hidden.add(targetId);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void restoreDuelHiddenPlayers(Player viewer) {
        if (viewer == null) {
            return;
        }
        Set<UUID> hidden = duelHiddenPlayers.remove(viewer.getUniqueId());
        if (hidden == null || hidden.isEmpty()) {
            return;
        }
        for (UUID hiddenId : new ArrayList<>(hidden)) {
            Player target = plugin.getServer().getPlayer(hiddenId);
            if (target == null || !target.isOnline()) {
                continue;
            }
            try {
                viewer.showPlayer(plugin, target);
            } catch (Throwable ignored) {
            }
        }
    }

    private void clearDuelPlayerListIsolation() {
        if (duelHiddenPlayers.isEmpty()) {
            return;
        }
        List<UUID> viewers = new ArrayList<>(duelHiddenPlayers.keySet());
        for (UUID viewerId : viewers) {
            Player viewer = plugin.getServer().getPlayer(viewerId);
            if (viewer != null && viewer.isOnline()) {
                restoreDuelHiddenPlayers(viewer);
            } else {
                duelHiddenPlayers.remove(viewerId);
            }
        }
    }

    private void sendRoundActionbar(Player viewer, Player opponent) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        int yourScore = getRoundsWon(viewer.getUniqueId());
        int opponentScore = opponent != null ? getRoundsWon(opponent.getUniqueId()) : 0;
        String text = MessageUtil.getPlainMessage("round_scoreboard_title",
                Placeholder.unparsed("round", String.valueOf(currentRound)),
                Placeholder.unparsed("rounds_to_win", String.valueOf(mode.getSettings().getRoundsToWin())))
                + " | " + yourScore + "-" + opponentScore + " | BO" + mode.getSettings().getRoundsToWin();
        viewer.sendActionBar(Component.text(text));
    }

    private void playRoundDefeatLightning(Player loser) {
        if (loser == null || !loser.isOnline()) {
            return;
        }
        Location location = loser.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        try {
            location.getWorld().strikeLightningEffect(location);
        } catch (Throwable ignored) {
        }
        try {
            loser.playSound(location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.25f, 1.0f);
            loser.playSound(location, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.0f, 1.1f);
        } catch (Throwable ignored) {
        }
    }

    private static final class BlockKey {
        private final String worldId;
        private final int x;
        private final int y;
        private final int z;

        private BlockKey(String worldId, int x, int y, int z) {
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private static BlockKey of(Location location) {
            return new BlockKey(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BlockKey other)) {
                return false;
            }
            return x == other.x && y == other.y && z == other.z && worldId.equals(other.worldId);
        }

        @Override
        public int hashCode() {
            int result = worldId.hashCode();
            result = 31 * result + x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }

    private record TabSnapshot(String header, String footer) {
    }
}
