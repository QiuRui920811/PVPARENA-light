package com.pvparena.model;

public class ModeSettings {
    private final double maxHealth;
    private final int hunger;
    private final float saturation;
    private final int noDamageTicks;
    private final boolean legacyPvp;
    private final boolean botEnabled;
    private final int roundsToWin;
    private final int nextRoundDelaySeconds;
    private final int winnerLeaveDelaySeconds;
    private final boolean buildEnabled;
    private final boolean rollbackEnabled;
    private final boolean breakPlacedBlocksOnly;
    private final boolean dropOnFinalRoundOnly;
    private final boolean spectatorEnabled;
    private final boolean eliminatedCanSpectate;
    private final boolean publicSpectatorEnabled;

    public ModeSettings(double maxHealth, int hunger, float saturation, int noDamageTicks,
                        boolean legacyPvp, boolean botEnabled,
                        int roundsToWin, int nextRoundDelaySeconds, int winnerLeaveDelaySeconds, boolean buildEnabled,
                        boolean rollbackEnabled,
                        boolean breakPlacedBlocksOnly, boolean dropOnFinalRoundOnly,
                        boolean spectatorEnabled, boolean eliminatedCanSpectate, boolean publicSpectatorEnabled) {
        this.maxHealth = maxHealth;
        this.hunger = hunger;
        this.saturation = saturation;
        this.noDamageTicks = noDamageTicks;
        this.legacyPvp = legacyPvp;
        this.botEnabled = botEnabled;
        this.roundsToWin = roundsToWin;
        this.nextRoundDelaySeconds = nextRoundDelaySeconds;
        this.winnerLeaveDelaySeconds = winnerLeaveDelaySeconds;
        this.buildEnabled = buildEnabled;
        this.rollbackEnabled = rollbackEnabled;
        this.breakPlacedBlocksOnly = breakPlacedBlocksOnly;
        this.dropOnFinalRoundOnly = dropOnFinalRoundOnly;
        this.spectatorEnabled = spectatorEnabled;
        this.eliminatedCanSpectate = eliminatedCanSpectate;
        this.publicSpectatorEnabled = publicSpectatorEnabled;
    }

    public double getMaxHealth() {
        return maxHealth;
    }

    public int getHunger() {
        return hunger;
    }

    public float getSaturation() {
        return saturation;
    }

    public int getNoDamageTicks() {
        return noDamageTicks;
    }

    public boolean isLegacyPvp() {
        return legacyPvp;
    }

    public boolean isBotEnabled() {
        return botEnabled;
    }

    public int getRoundsToWin() {
        return roundsToWin;
    }

    public int getNextRoundDelaySeconds() {
        return nextRoundDelaySeconds;
    }

    public int getWinnerLeaveDelaySeconds() {
        return winnerLeaveDelaySeconds;
    }

    public boolean isBuildEnabled() {
        return buildEnabled;
    }

    public boolean isRollbackEnabled() {
        return rollbackEnabled;
    }

    public boolean isBreakPlacedBlocksOnly() {
        return breakPlacedBlocksOnly;
    }

    public boolean isDropOnFinalRoundOnly() {
        return dropOnFinalRoundOnly;
    }

    public boolean isSpectatorEnabled() {
        return spectatorEnabled;
    }

    public boolean isEliminatedCanSpectate() {
        return eliminatedCanSpectate;
    }

    public boolean isPublicSpectatorEnabled() {
        return publicSpectatorEnabled;
    }
}
