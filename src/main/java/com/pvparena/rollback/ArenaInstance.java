package com.pvparena.rollback;

public class ArenaInstance {
    private final String sessionId;
    private final String arenaId;
    private final ArenaSnapshot snapshot;
    private final DirtyBlockTracker dirtyBlockTracker;

    public ArenaInstance(String sessionId, String arenaId, ArenaSnapshot snapshot, DirtyBlockTracker dirtyBlockTracker) {
        this.sessionId = sessionId;
        this.arenaId = arenaId;
        this.snapshot = snapshot;
        this.dirtyBlockTracker = dirtyBlockTracker;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getArenaId() {
        return arenaId;
    }

    public ArenaSnapshot getSnapshot() {
        return snapshot;
    }

    public DirtyBlockTracker getDirtyBlockTracker() {
        return dirtyBlockTracker;
    }
}
