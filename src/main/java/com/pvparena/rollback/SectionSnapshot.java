package com.pvparena.rollback;

public class SectionSnapshot {
    private final int chunkX;
    private final int chunkZ;
    private final int sectionY;
    private final short[] blockPaletteIndex;
    private final String[] palette;

    public SectionSnapshot(int chunkX, int chunkZ, int sectionY, short[] blockPaletteIndex, String[] palette) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.sectionY = sectionY;
        this.blockPaletteIndex = blockPaletteIndex;
        this.palette = palette;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public int getSectionY() {
        return sectionY;
    }

    public short[] getBlockPaletteIndex() {
        return blockPaletteIndex;
    }

    public String[] getPalette() {
        return palette;
    }

    public String getBlockDataString(int x, int y, int z) {
        int localX = x & 15;
        int localY = y & 15;
        int localZ = z & 15;
        int index = (localY << 8) | (localZ << 4) | localX;
        if (index < 0 || index >= blockPaletteIndex.length) {
            return null;
        }
        int paletteIndex = Short.toUnsignedInt(blockPaletteIndex[index]);
        if (paletteIndex < 0 || paletteIndex >= palette.length) {
            return null;
        }
        return palette[paletteIndex];
    }
}
