package com.pvparena.model;

public enum BotDifficulty {
    EASY("easy", 0.26, 0.16, 650L, 0.04, 3.0),
    NORMAL("normal", 0.32, 0.22, 520L, 0.07, 4.0),
    HARD("hard", 0.38, 0.28, 420L, 0.12, 5.0);

    private final String id;
    private final double moveSpeed;
    private final double strafeSpeed;
    private final long attackCooldownMs;
    private final double jumpChance;
    private final double baseDamage;

    BotDifficulty(String id, double moveSpeed, double strafeSpeed, long attackCooldownMs, double jumpChance, double baseDamage) {
        this.id = id;
        this.moveSpeed = moveSpeed;
        this.strafeSpeed = strafeSpeed;
        this.attackCooldownMs = attackCooldownMs;
        this.jumpChance = jumpChance;
        this.baseDamage = baseDamage;
    }

    public String getId() {
        return id;
    }

    public double getMoveSpeed() {
        return moveSpeed;
    }

    public double getStrafeSpeed() {
        return strafeSpeed;
    }

    public long getAttackCooldownMs() {
        return attackCooldownMs;
    }

    public double getJumpChance() {
        return jumpChance;
    }

    public double getBaseDamage() {
        return baseDamage;
    }

    public static BotDifficulty fromId(String id) {
        if (id == null) {
            return NORMAL;
        }
        for (BotDifficulty difficulty : values()) {
            if (difficulty.id.equalsIgnoreCase(id)) {
                return difficulty;
            }
        }
        return NORMAL;
    }
}
