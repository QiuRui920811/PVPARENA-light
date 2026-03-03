package com.pvparena.rollback;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public class ArenaSnapshot {
    private final UUID arenaId;
    private final String worldName;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;
    private final Map<Long, SectionSnapshot> sections;

    public ArenaSnapshot(UUID arenaId, String worldName, int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                         Map<Long, SectionSnapshot> sections) {
        this.arenaId = arenaId;
        this.worldName = worldName;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.sections = sections;
    }

    public UUID getArenaId() {
        return arenaId;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getMinX() {
        return minX;
    }

    public int getMaxX() {
        return maxX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMaxY() {
        return maxY;
    }

    public int getMinZ() {
        return minZ;
    }

    public int getMaxZ() {
        return maxZ;
    }

    public Map<Long, SectionSnapshot> getSections() {
        return Collections.unmodifiableMap(sections);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public String resolveBlockDataString(int x, int y, int z) {
        if (!contains(x, y, z)) {
            return null;
        }
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int sectionY = y >> 4;
        SectionSnapshot section = sections.get(sectionKey(chunkX, chunkZ, sectionY));
        if (section == null) {
            return null;
        }
        return section.getBlockDataString(x, y, z);
    }

    public static long sectionKey(int chunkX, int chunkZ, int sectionY) {
        long x = ((long) chunkX & 0x3FFFFFL) << 42;
        long z = ((long) chunkZ & 0x3FFFFFL) << 20;
        long y = (long) sectionY & 0xFFFFFL;
        return x | z | y;
    }
}
