package com.pvparena.manager;

import com.pvparena.model.PlayerSnapshot;
import com.pvparena.util.SQLiteDriverLoader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PendingRestoreManager {
    private final JavaPlugin plugin;
    private Connection connection;
    private final Map<UUID, PendingRestoreEntry> pending = new ConcurrentHashMap<>();
    private static final long MAX_ENTRY_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000; // 7 days
    private static final int MAX_ENTRIES = 5000;

    private static final class PendingRestoreEntry {
        private final PlayerSnapshot snapshot;
        private final long createdAt;

        private PendingRestoreEntry(PlayerSnapshot snapshot, long createdAt) {
            this.snapshot = snapshot;
            this.createdAt = createdAt;
        }
    }

    public PendingRestoreManager(JavaPlugin plugin) {
        this.plugin = plugin;
        initDatabase();
        load();
    }

    private void initDatabase() {
        try {
            new SQLiteDriverLoader(plugin).ensureDriver();
            File dbFile = new File(plugin.getDataFolder(), "pending-restores.db");
            dbFile.getParentFile().mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS pending_restores (uuid TEXT PRIMARY KEY, data TEXT)");
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to init pending restore DB: " + ex.getMessage());
        }
    }

    public void load() {
        pending.clear();
        if (connection == null) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, data FROM pending_restores")) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString("uuid"));
                String data = rs.getString("data");
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(data == null ? "" : data);
                PlayerSnapshot snapshot = PlayerSnapshot.deserialize(yaml.getConfigurationSection("data") != null
                        ? yaml.getConfigurationSection("data").getValues(true)
                        : yaml.getValues(true));
                long createdAt = yaml.getLong("createdAt", System.currentTimeMillis());
                if (now - createdAt > MAX_ENTRY_AGE_MILLIS) {
                    delete(id);
                    continue;
                }
                if (snapshot != null) {
                    pending.put(id, new PendingRestoreEntry(snapshot, createdAt));
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to load pending restores: " + ex.getMessage());
        }
        pruneIfNeeded(now);
    }

    public void save() {
        if (connection == null) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DELETE FROM pending_restores");
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to clear pending restores: " + ex.getMessage());
        }
        for (Map.Entry<UUID, PendingRestoreEntry> entry : pending.entrySet()) {
            insert(entry.getKey(), entry.getValue().snapshot, entry.getValue().createdAt);
        }
    }

    public void add(UUID playerId, PlayerSnapshot snapshot) {
        long now = System.currentTimeMillis();
        pruneIfNeeded(now);
        pending.put(playerId, new PendingRestoreEntry(snapshot, now));
        insert(playerId, snapshot, now);
        debug("add/replace " + playerId + " " + (snapshot != null ? snapshot.debugFingerprint() : "null"));
    }

    public boolean addIfAbsent(UUID playerId, PlayerSnapshot snapshot) {
        long now = System.currentTimeMillis();
        pruneIfNeeded(now);
        PendingRestoreEntry existing = pending.putIfAbsent(playerId, new PendingRestoreEntry(snapshot, now));
        if (existing != null) {
            debug("addIfAbsent skipped " + playerId + " existing=" + existing.snapshot.debugFingerprint());
            return false;
        }
        insert(playerId, snapshot, now);
        debug("addIfAbsent inserted " + playerId + " " + (snapshot != null ? snapshot.debugFingerprint() : "null"));
        return true;
    }

    private void pruneIfNeeded(long now) {
        if (pending.isEmpty()) {
            return;
        }

        // Prune expired entries
        for (Map.Entry<UUID, PendingRestoreEntry> entry : pending.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (now - entry.getValue().createdAt > MAX_ENTRY_AGE_MILLIS) {
                pending.remove(entry.getKey());
                delete(entry.getKey());
            }
        }

        // Cap total entries to avoid unbounded memory/disk growth on servers with many quitters.
        while (pending.size() > MAX_ENTRIES) {
            UUID oldestId = null;
            long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<UUID, PendingRestoreEntry> e : pending.entrySet()) {
                if (e.getValue().createdAt < oldestTs) {
                    oldestTs = e.getValue().createdAt;
                    oldestId = e.getKey();
                }
            }
            if (oldestId == null) {
                break;
            }
            pending.remove(oldestId);
            delete(oldestId);
        }
    }

    public PlayerSnapshot consume(UUID playerId) {
        PendingRestoreEntry entry = pending.remove(playerId);
        if (entry == null) {
            debug("consume miss " + playerId);
            return null;
        }
        delete(playerId);
        debug("consume hit " + playerId + " " + entry.snapshot.debugFingerprint());
        return entry.snapshot;
    }

    public PlayerSnapshot peek(UUID playerId) {
        PendingRestoreEntry entry = pending.get(playerId);
        if (entry == null) {
            debug("peek miss " + playerId);
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - entry.createdAt > MAX_ENTRY_AGE_MILLIS) {
            pending.remove(playerId);
            delete(playerId);
            debug("peek expired " + playerId);
            return null;
        }
        debug("peek hit " + playerId + " " + entry.snapshot.debugFingerprint());
        return entry.snapshot;
    }

    private void debug(String msg) {
        try {
            if (plugin.getConfig().getBoolean("debug.restore", false)) {
                plugin.getLogger().info("[RestoreDebug][Pending] " + msg);
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean has(UUID playerId) {
        return pending.containsKey(playerId);
    }

    public Map<UUID, PlayerSnapshot> getPending() {
        Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
        for (Map.Entry<UUID, PendingRestoreEntry> entry : pending.entrySet()) {
            snapshots.put(entry.getKey(), entry.getValue().snapshot);
        }
        return snapshots;
    }

    private void insert(UUID playerId, PlayerSnapshot snapshot, long createdAt) {
        if (connection == null) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO pending_restores (uuid, data) VALUES (?, ?)") ) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("data", snapshot.serialize());
            yaml.set("createdAt", createdAt);
            String data = yaml.saveToString();
            ps.setString(1, playerId.toString());
            ps.setString(2, data);
            ps.executeUpdate();
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to save pending restore: " + ex.getMessage());
        }
    }

    private void delete(UUID playerId) {
        if (connection == null) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM pending_restores WHERE uuid = ?") ) {
            ps.setString(1, playerId.toString());
            ps.executeUpdate();
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to delete pending restore: " + ex.getMessage());
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to close pending restore DB: " + ex.getMessage());
        }
    }
}
