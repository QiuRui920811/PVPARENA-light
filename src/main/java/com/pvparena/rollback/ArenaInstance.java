package com.pvparena.rollback;

public class ArenaInstance {
    private final String sessionId;
    private final String arenaId;
    private final ArenaChangeRecorder changeRecorder;
    private volatile boolean frozen;

    public ArenaInstance(String sessionId, String arenaId, ArenaChangeRecorder changeRecorder) {
        this.sessionId = sessionId;
        this.arenaId = arenaId;
        this.changeRecorder = changeRecorder;
        this.frozen = false;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getArenaId() {
        return arenaId;
    }

    public ArenaChangeRecorder getChangeRecorder() {
        return changeRecorder;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void freeze() {
        this.frozen = true;
    }
}
