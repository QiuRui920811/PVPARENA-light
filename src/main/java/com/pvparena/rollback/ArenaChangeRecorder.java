package com.pvparena.rollback;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ArenaChangeRecorder {
    private final String worldName;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;
    private final ConcurrentHashMap<Long, String> originalStates = new ConcurrentHashMap<>();

    public ArenaChangeRecorder(String worldName, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        this.worldName = worldName;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    public String getWorldName() {
        return worldName;
    }

    public void record(Block block, NmsBlockWriter writer) {
        if (block == null || writer == null || block.getWorld() == null) {
            return;
        }
        if (!worldName.equalsIgnoreCase(block.getWorld().getName())) {
            return;
        }

        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        if (!contains(x, y, z)) {
            return;
        }

        long key = asLong(x, y, z);
        originalStates.computeIfAbsent(key, k -> {
            String state = writer.getBlockDataString(block.getWorld(), x, y, z);
            return (state == null || state.isBlank()) ? "minecraft:air" : state;
        });
    }

    public void recordOriginal(BlockState originalState) {
        if (originalState == null || originalState.getWorld() == null) {
            return;
        }
        if (!worldName.equalsIgnoreCase(originalState.getWorld().getName())) {
            return;
        }
        int x = originalState.getX();
        int y = originalState.getY();
        int z = originalState.getZ();
        if (!contains(x, y, z)) {
            return;
        }
        long key = asLong(x, y, z);
        originalStates.computeIfAbsent(key, k -> {
            String state = originalState.getBlockData() == null
                    ? null
                    : originalState.getBlockData().getAsString();
            return (state == null || state.isBlank()) ? "minecraft:air" : state;
        });
    }

    public Map<Long, ArrayDeque<BlockRollbackTask>> drainGroupedByChunk() {
        Map<Long, ArrayDeque<BlockRollbackTask>> grouped = new ConcurrentHashMap<>();
        for (Map.Entry<Long, String> entry : originalStates.entrySet()) {
            long key = entry.getKey();
            String state = entry.getValue();
            if (state == null || state.isBlank()) {
                continue;
            }
            int x = unpackX(key);
            int y = unpackY(key);
            int z = unpackZ(key);
            long chunkKey = chunkKey(x >> 4, z >> 4);
            grouped.computeIfAbsent(chunkKey, ignored -> new ArrayDeque<>())
                    .addLast(new BlockRollbackTask(x, y, z, state));
        }
        originalStates.clear();
        return grouped;
    }

    public int size() {
        return originalStates.size();
    }

    private boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public static long asLong(int x, int y, int z) {
        return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    public static int unpackY(long packed) {
        int y = (int) (packed & 0xFFFL);
        return y >= 2048 ? y - 4096 : y;
    }

    public static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    public static int unpackChunkX(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    public static int unpackChunkZ(long chunkKey) {
        return (int) chunkKey;
    }
}
