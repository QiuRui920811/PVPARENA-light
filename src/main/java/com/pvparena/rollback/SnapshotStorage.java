package com.pvparena.rollback;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SnapshotStorage {
    private static final int FORMAT_VERSION = 1;
    private final File snapshotDir;

    public SnapshotStorage(JavaPlugin plugin) {
        this.snapshotDir = new File(plugin.getDataFolder(), "arenaSnapshots");
        if (!snapshotDir.exists()) {
            snapshotDir.mkdirs();
        }
    }

    public File getSnapshotFile(String sessionId) {
        return new File(snapshotDir, sessionId + ".snapshot");
    }

    public File getArenaBaselineFile(String arenaId) {
        return new File(snapshotDir, "baseline_" + sanitizeFileName(arenaId) + ".snapshot");
    }

    public void save(String sessionId, ArenaSnapshot snapshot) throws IOException {
        File file = getSnapshotFile(sessionId);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(FORMAT_VERSION);
            writeString(out, snapshot.getArenaId().toString());
            writeString(out, snapshot.getWorldName());
            out.writeInt(snapshot.getMinX());
            out.writeInt(snapshot.getMaxX());
            out.writeInt(snapshot.getMinY());
            out.writeInt(snapshot.getMaxY());
            out.writeInt(snapshot.getMinZ());
            out.writeInt(snapshot.getMaxZ());
            Map<Long, SectionSnapshot> sections = snapshot.getSections();
            out.writeInt(sections.size());
            for (SectionSnapshot section : sections.values()) {
                out.writeInt(section.getChunkX());
                out.writeInt(section.getChunkZ());
                out.writeInt(section.getSectionY());
                String[] palette = section.getPalette();
                out.writeInt(palette.length);
                for (String p : palette) {
                    writeString(out, p);
                }
                short[] indexes = section.getBlockPaletteIndex();
                out.writeInt(indexes.length);
                for (short index : indexes) {
                    out.writeShort(index);
                }
            }
        }
    }

    public ArenaSnapshot load(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported snapshot format version: " + version);
            }
            UUID arenaId = UUID.fromString(readString(in));
            String worldName = readString(in);
            int minX = in.readInt();
            int maxX = in.readInt();
            int minY = in.readInt();
            int maxY = in.readInt();
            int minZ = in.readInt();
            int maxZ = in.readInt();
            int sectionCount = in.readInt();
            Map<Long, SectionSnapshot> sections = new HashMap<>();
            for (int i = 0; i < sectionCount; i++) {
                int chunkX = in.readInt();
                int chunkZ = in.readInt();
                int sectionY = in.readInt();
                int paletteSize = in.readInt();
                String[] palette = new String[paletteSize];
                for (int p = 0; p < paletteSize; p++) {
                    palette[p] = readString(in);
                }
                int indexLength = in.readInt();
                short[] indexes = new short[indexLength];
                for (int idx = 0; idx < indexLength; idx++) {
                    indexes[idx] = in.readShort();
                }
                SectionSnapshot section = new SectionSnapshot(chunkX, chunkZ, sectionY, indexes, palette);
                sections.put(ArenaSnapshot.sectionKey(chunkX, chunkZ, sectionY), section);
            }
            return new ArenaSnapshot(arenaId, worldName, minX, maxX, minY, maxY, minZ, maxZ, sections);
        }
    }

    public void saveArenaBaseline(String arenaId, ArenaSnapshot snapshot) throws IOException {
        saveToFile(getArenaBaselineFile(arenaId), snapshot);
    }

    public ArenaSnapshot loadArenaBaseline(String arenaId) throws IOException {
        File file = getArenaBaselineFile(arenaId);
        if (!file.exists()) {
            return null;
        }
        return load(file);
    }

    public List<File> listSnapshotFiles() {
        File[] files = snapshotDir.listFiles((dir, name) -> name.endsWith(".snapshot") && !name.startsWith("baseline_"));
        List<File> result = new ArrayList<>();
        if (files == null) {
            return result;
        }
        for (File file : files) {
            result.add(file);
        }
        return result;
    }

    public void delete(String sessionId) {
        File file = getSnapshotFile(sessionId);
        if (file.exists()) {
            file.delete();
        }
    }

    private void saveToFile(File file, ArenaSnapshot snapshot) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(FORMAT_VERSION);
            writeString(out, snapshot.getArenaId().toString());
            writeString(out, snapshot.getWorldName());
            out.writeInt(snapshot.getMinX());
            out.writeInt(snapshot.getMaxX());
            out.writeInt(snapshot.getMinY());
            out.writeInt(snapshot.getMaxY());
            out.writeInt(snapshot.getMinZ());
            out.writeInt(snapshot.getMaxZ());
            Map<Long, SectionSnapshot> sections = snapshot.getSections();
            out.writeInt(sections.size());
            for (SectionSnapshot section : sections.values()) {
                out.writeInt(section.getChunkX());
                out.writeInt(section.getChunkZ());
                out.writeInt(section.getSectionY());
                String[] palette = section.getPalette();
                out.writeInt(palette.length);
                for (String p : palette) {
                    writeString(out, p);
                }
                short[] indexes = section.getBlockPaletteIndex();
                out.writeInt(indexes.length);
                for (short index : indexes) {
                    out.writeShort(index);
                }
            }
        }
    }

    private String sanitizeFileName(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length <= 0) {
            return "";
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
