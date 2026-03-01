package com.pvparena.manager;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class MatchCrashRecoveryManager {
    private final JavaPlugin plugin;
    private Connection connection;

    public MatchCrashRecoveryManager(JavaPlugin plugin) {
        this.plugin = plugin;
        init();
    }

    private void init() {
        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) {
                plugin.getLogger().warning("Failed to create plugin data folder for crash recovery DB.");
            }
            File db = new File(dir, "match-recovery.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS active_matches ("
                        + "session_id TEXT PRIMARY KEY,"
                        + "player_a TEXT NOT NULL,"
                        + "player_b TEXT NOT NULL,"
                        + "mode_id TEXT NOT NULL,"
                        + "arena_id TEXT NOT NULL,"
                    + "current_round INTEGER NOT NULL DEFAULT 1,"
                    + "rounds_won_a INTEGER NOT NULL DEFAULT 0,"
                    + "rounds_won_b INTEGER NOT NULL DEFAULT 0,"
                        + "created_at INTEGER NOT NULL"
                        + ")");
                ensureColumn(st, "active_matches", "current_round", "INTEGER NOT NULL DEFAULT 1");
                ensureColumn(st, "active_matches", "rounds_won_a", "INTEGER NOT NULL DEFAULT 0");
                ensureColumn(st, "active_matches", "rounds_won_b", "INTEGER NOT NULL DEFAULT 0");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS rollback_blocks ("
                        + "session_id TEXT NOT NULL,"
                        + "world TEXT NOT NULL,"
                        + "x INTEGER NOT NULL,"
                        + "y INTEGER NOT NULL,"
                        + "z INTEGER NOT NULL,"
                        + "block_data TEXT NOT NULL,"
                        + "PRIMARY KEY(session_id, world, x, y, z)"
                        + ")");
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to initialize match crash recovery DB: " + ex.getMessage());
        }
    }

    private void ensureColumn(Statement st, String table, String column, String def) {
        try {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + def);
        } catch (Exception ignored) {
        }
    }

    public void registerActiveMatch(String sessionId, UUID playerA, UUID playerB, String modeId, String arenaId) {
        registerActiveMatch(sessionId, playerA, playerB, modeId, arenaId, 1, 0, 0);
    }

    public void registerActiveMatch(String sessionId, UUID playerA, UUID playerB, String modeId, String arenaId,
                                    int currentRound, int roundsWonA, int roundsWonB) {
        if (connection == null || sessionId == null || playerA == null || playerB == null || modeId == null || arenaId == null) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO active_matches (session_id, player_a, player_b, mode_id, arena_id, current_round, rounds_won_a, rounds_won_b, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)") ) {
            ps.setString(1, sessionId);
            ps.setString(2, playerA.toString());
            ps.setString(3, playerB.toString());
            ps.setString(4, modeId);
            ps.setString(5, arenaId);
            ps.setInt(6, Math.max(1, currentRound));
            ps.setInt(7, Math.max(0, roundsWonA));
            ps.setInt(8, Math.max(0, roundsWonB));
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to register active match for recovery: " + ex.getMessage());
        }
    }

    public void updateActiveMatchProgress(String sessionId, int currentRound, int roundsWonA, int roundsWonB) {
        if (connection == null || sessionId == null) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE active_matches SET current_round = ?, rounds_won_a = ?, rounds_won_b = ? WHERE session_id = ?")) {
            ps.setInt(1, Math.max(1, currentRound));
            ps.setInt(2, Math.max(0, roundsWonA));
            ps.setInt(3, Math.max(0, roundsWonB));
            ps.setString(4, sessionId);
            ps.executeUpdate();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to update active match progress: " + ex.getMessage());
        }
    }

    public void removeActiveMatch(String sessionId) {
        if (connection == null || sessionId == null) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM active_matches WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to remove active match recovery entry: " + ex.getMessage());
        }
    }

    public List<RecoveredMatch> loadActiveMatches() {
        List<RecoveredMatch> out = new ArrayList<>();
        if (connection == null) {
            return out;
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT session_id, player_a, player_b, mode_id, arena_id, current_round, rounds_won_a, rounds_won_b FROM active_matches")) {
            while (rs.next()) {
                try {
                    out.add(new RecoveredMatch(
                            rs.getString("session_id"),
                            UUID.fromString(rs.getString("player_a")),
                            UUID.fromString(rs.getString("player_b")),
                            rs.getString("mode_id"),
                            rs.getString("arena_id"),
                            Math.max(1, rs.getInt("current_round")),
                            Math.max(0, rs.getInt("rounds_won_a")),
                            Math.max(0, rs.getInt("rounds_won_b"))
                    ));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load active matches for crash recovery: " + ex.getMessage());
        }
        return out;
    }

    public void recordRollbackBlock(String sessionId, Location location, BlockData blockData) {
        if (connection == null || sessionId == null || location == null || location.getWorld() == null || blockData == null) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO rollback_blocks (session_id, world, x, y, z, block_data) VALUES (?, ?, ?, ?, ?, ?)") ) {
            ps.setString(1, sessionId);
            ps.setString(2, location.getWorld().getName());
            ps.setInt(3, location.getBlockX());
            ps.setInt(4, location.getBlockY());
            ps.setInt(5, location.getBlockZ());
            ps.setString(6, blockData.getAsString());
            ps.executeUpdate();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to persist rollback block: " + ex.getMessage());
        }
    }

    public void clearRollbackBlocks(String sessionId) {
        if (connection == null || sessionId == null) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM rollback_blocks WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to clear rollback blocks for session: " + ex.getMessage());
        }
    }

    public void recoverPendingRollbacksAsync() {
        List<RollbackBlock> all = loadRollbackBlocks();
        if (all.isEmpty()) {
            return;
        }

        Set<String> activeSessionIds = loadActiveSessionIds();
        List<RollbackBlock> orphaned = new ArrayList<>();
        for (RollbackBlock block : all) {
            if (!activeSessionIds.contains(block.sessionId())) {
                orphaned.add(block);
            }
        }
        if (orphaned.isEmpty()) {
            return;
        }

        Map<String, List<RollbackBlock>> byWorld = new HashMap<>();
        for (RollbackBlock block : orphaned) {
            byWorld.computeIfAbsent(block.world(), k -> new ArrayList<>()).add(block);
        }

        Set<String> recoveredSessionIds = new HashSet<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        int blocksPerTick = 256;

        for (Map.Entry<String, List<RollbackBlock>> worldEntry : byWorld.entrySet()) {
            World world = plugin.getServer().getWorld(worldEntry.getKey());
            if (world == null) {
                continue;
            }

            Map<Long, List<RollbackBlock>> byChunk = new HashMap<>();
            for (RollbackBlock block : worldEntry.getValue()) {
                recoveredSessionIds.add(block.sessionId());
                int chunkX = block.x() >> 4;
                int chunkZ = block.z() >> 4;
                long chunkKey = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
                byChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(block);
            }

            for (Map.Entry<Long, List<RollbackBlock>> chunkEntry : byChunk.entrySet()) {
                int chunkX = (int) (chunkEntry.getKey() >> 32);
                int chunkZ = (int) (chunkEntry.getKey().longValue());
                List<RollbackBlock> chunkBlocks = chunkEntry.getValue();
                CompletableFuture<Void> done = new CompletableFuture<>();
                futures.add(done);
                plugin.getServer().getRegionScheduler().run(plugin, world, chunkX, chunkZ,
                        task -> applyChunkRollback(world, chunkBlocks, 0, blocksPerTick, done));
            }
        }

        if (futures.isEmpty()) {
            return;
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((v, ex) -> {
            if (ex != null) {
                plugin.getLogger().warning("Crash rollback recovery completed with errors: " + ex.getMessage());
            }
            if (!recoveredSessionIds.isEmpty()) {
                clearRollbackBlocksForSessions(recoveredSessionIds);
            }
        });
    }

    private Set<String> loadActiveSessionIds() {
        Set<String> out = new HashSet<>();
        if (connection == null) {
            return out;
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT session_id FROM active_matches")) {
            while (rs.next()) {
                String id = rs.getString("session_id");
                if (id != null && !id.isBlank()) {
                    out.add(id);
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load active match session ids: " + ex.getMessage());
        }
        return out;
    }

    private void applyChunkRollback(World world, List<RollbackBlock> blocks, int index, int blocksPerTick,
                                    CompletableFuture<Void> done) {
        if (blocks == null || index >= blocks.size()) {
            done.complete(null);
            return;
        }
        int end = Math.min(index + blocksPerTick, blocks.size());
        for (int i = index; i < end; i++) {
            RollbackBlock block = blocks.get(i);
            try {
                BlockData data = plugin.getServer().createBlockData(block.blockData());
                world.getBlockAt(block.x(), block.y(), block.z()).setBlockData(data, false);
            } catch (Throwable ignored) {
            }
        }
        if (end >= blocks.size()) {
            done.complete(null);
            return;
        }
        int nextChunkX = blocks.get(end).x() >> 4;
        int nextChunkZ = blocks.get(end).z() >> 4;
        plugin.getServer().getRegionScheduler().runDelayed(plugin, world, nextChunkX, nextChunkZ,
                task -> applyChunkRollback(world, blocks, end, blocksPerTick, done), 1L);
    }

    private List<RollbackBlock> loadRollbackBlocks() {
        List<RollbackBlock> out = new ArrayList<>();
        if (connection == null) {
            return out;
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT session_id, world, x, y, z, block_data FROM rollback_blocks")) {
            while (rs.next()) {
                out.add(new RollbackBlock(
                        rs.getString("session_id"),
                        rs.getString("world"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z"),
                        rs.getString("block_data")
                ));
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load rollback blocks for crash recovery: " + ex.getMessage());
        }
        return out;
    }

    private void clearRollbackBlocksForSessions(Set<String> sessionIds) {
        if (connection == null || sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        for (String sessionId : sessionIds) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM rollback_blocks WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to clear rollback blocks for recovered session '" + sessionId + "': " + ex.getMessage());
            }
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to close match crash recovery DB: " + ex.getMessage());
        }
    }

    public record RecoveredMatch(String sessionId, UUID playerA, UUID playerB, String modeId, String arenaId,
                                 int currentRound, int roundsWonA, int roundsWonB) {
    }

    private record RollbackBlock(String sessionId, String world, int x, int y, int z, String blockData) {
    }
}
