package com.pvparena.rollback;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DirtyBlockTracker {
    private final Set<DirtyBlock> dirtyBlocks = ConcurrentHashMap.newKeySet();

    public void markDirty(int x, int y, int z) {
        dirtyBlocks.add(new DirtyBlock(x, y, z));
    }

    public void markDirty(DirtyBlock dirtyBlock) {
        if (dirtyBlock == null) {
            return;
        }
        dirtyBlocks.add(dirtyBlock);
    }

    public List<DirtyBlock> drainAll() {
        List<DirtyBlock> drained = new ArrayList<>(dirtyBlocks);
        dirtyBlocks.clear();
        return drained;
    }

    public int size() {
        return dirtyBlocks.size();
    }
}
