package com.pvparena.model;

public class MatchResult {
    private final String opponentName;
    private final String modeName;
    private final double opponentHealth;
    private final double opponentMaxHealth;
    private final double damageDealt;
    private final double damageTaken;
    private final CombatSnapshot opponentSnapshot;

    public MatchResult(String opponentName, String modeName, double opponentHealth, double opponentMaxHealth,
                       double damageDealt, double damageTaken, CombatSnapshot opponentSnapshot) {
        this.opponentName = opponentName;
        this.modeName = modeName;
        this.opponentHealth = opponentHealth;
        this.opponentMaxHealth = opponentMaxHealth;
        this.damageDealt = damageDealt;
        this.damageTaken = damageTaken;
        this.opponentSnapshot = opponentSnapshot;
    }

    public String getOpponentName() {
        return opponentName;
    }

    public String getModeName() {
        return modeName;
    }

    public double getOpponentHealth() {
        return opponentHealth;
    }

    public double getOpponentMaxHealth() {
        return opponentMaxHealth;
    }

    public double getDamageDealt() {
        return damageDealt;
    }

    public double getDamageTaken() {
        return damageTaken;
    }

    public CombatSnapshot getOpponentSnapshot() {
        return opponentSnapshot;
    }
}
