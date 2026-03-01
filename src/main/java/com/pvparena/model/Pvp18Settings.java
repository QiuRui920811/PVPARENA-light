package com.pvparena.model;

import org.bukkit.configuration.file.FileConfiguration;

public class Pvp18Settings {
    private final double attackSpeed;
    private final int maxNoDamageTicks;
    private final long comboWindowMs;
    private final double baseKnockback;
    private final double comboKnockback;
    private final int maxCombo;
    private final double knockbackY;
    private final double sprintKnockbackBonus;
    private final double unarmedKnockbackMultiplier;
    private final boolean unarmedCombo;
    private final boolean jumpResetEnabled;
    private final long jumpResetWindowMs;
    private final double jumpResetMultiplier;
    private final double jumpResetYMultiplier;
    private final double airborneKnockbackMultiplier;
    private final boolean landingResetsCombo;
    private final double fishMaxDistance;
    private final double fishPullMultiplier;
    private final double fishPullY;
    private final double fishDamage;
    private final double simulateDamage;
    private final boolean disableOffhand;
    private final boolean forceIFramesOnHit;
    private final double bowVelocityMultiplier;
    private final double bowDamageMultiplier;
    private final boolean bowDisablePunch;
    private final boolean bowStripTipped;
    private final boolean blockHitEnabled;
    private final long blockHitWindowMs;
    private final double blockHitDamageMultiplier;
    private final double knockbackHorizontalMultiplier;
    private final double knockbackVerticalMultiplier;
    private final boolean goldenAppleEnabled;
    private final double gaAbsorptionHearts;
    private final int gaRegenSeconds;
    private final int gaRegenAmplifier;
    private final boolean enchantedAppleEnabled;
    private final double eaAbsorptionHearts;
    private final int eaRegenSeconds;
    private final int eaRegenAmplifier;
    private final int eaResistanceSeconds;
    private final int eaFireResSeconds;

    public Pvp18Settings(FileConfiguration config) {
        this.attackSpeed = config.getDouble("attackSpeed", 1024.0);
        this.maxNoDamageTicks = config.getInt("maxNoDamageTicks", 10);
        this.comboWindowMs = config.getLong("comboWindowMs", 900L);
        this.baseKnockback = config.getDouble("baseKnockback", 0.35);
        this.comboKnockback = config.getDouble("comboKnockback", 0.06);
        this.maxCombo = config.getInt("maxCombo", 6);
        this.knockbackY = config.getDouble("knockbackY", 0.35);
        this.sprintKnockbackBonus = config.getDouble("sprintKnockbackBonus", 0.1);
        this.unarmedKnockbackMultiplier = config.getDouble("unarmedKnockbackMultiplier", 0.6);
        this.unarmedCombo = config.getBoolean("unarmedCombo", false);
        this.jumpResetEnabled = config.getBoolean("jumpReset.enabled", true);
        this.jumpResetWindowMs = config.getLong("jumpReset.windowMs", 120L);
        this.jumpResetMultiplier = config.getDouble("jumpReset.multiplier", 0.6);
        this.jumpResetYMultiplier = config.getDouble("jumpReset.yMultiplier", 0.6);
        this.airborneKnockbackMultiplier = config.getDouble("airborneKnockbackMultiplier", 0.8);
        this.landingResetsCombo = config.getBoolean("landingResetsCombo", true);
        this.fishMaxDistance = config.getDouble("fish.maxDistance", 33.0);
        this.fishPullMultiplier = config.getDouble("fish.pullMultiplier", 0.10);
        this.fishPullY = config.getDouble("fish.pullY", 0.15);
        this.fishDamage = config.getDouble("fish.damage", 0.0);
        this.simulateDamage = config.getDouble("fish.simulateDamage", 0.0001);
        this.disableOffhand = config.getBoolean("disableOffhand", true);
        this.forceIFramesOnHit = config.getBoolean("forceIFramesOnHit", true);
        this.bowVelocityMultiplier = config.getDouble("bow.velocityMultiplier", 1.05);
        this.bowDamageMultiplier = config.getDouble("bow.damageMultiplier", 1.0);
        this.bowDisablePunch = config.getBoolean("bow.disablePunch", true);
        this.bowStripTipped = config.getBoolean("bow.stripTipped", true);
        this.blockHitEnabled = config.getBoolean("blockHit.enabled", true);
        this.blockHitWindowMs = Math.max(0L, config.getLong("blockHit.windowMs", 180L));
        this.blockHitDamageMultiplier = config.getDouble("blockHit.damageMultiplier", 0.8);
        this.knockbackHorizontalMultiplier = config.getDouble("knockback.horizontalMultiplier", 1.0);
        this.knockbackVerticalMultiplier = config.getDouble("knockback.verticalMultiplier", 1.0);
        this.goldenAppleEnabled = config.getBoolean("goldenApple.enabled", true);
        this.gaAbsorptionHearts = config.getDouble("goldenApple.absorptionHearts", 2.0);
        this.gaRegenSeconds = config.getInt("goldenApple.regenSeconds", 5);
        this.gaRegenAmplifier = config.getInt("goldenApple.regenAmplifier", 1);
        this.enchantedAppleEnabled = config.getBoolean("enchantedApple.enabled", true);
        this.eaAbsorptionHearts = config.getDouble("enchantedApple.absorptionHearts", 8.0);
        this.eaRegenSeconds = config.getInt("enchantedApple.regenSeconds", 30);
        this.eaRegenAmplifier = config.getInt("enchantedApple.regenAmplifier", 4);
        this.eaResistanceSeconds = config.getInt("enchantedApple.resistanceSeconds", 300);
        this.eaFireResSeconds = config.getInt("enchantedApple.fireResSeconds", 300);
    }

    public double getAttackSpeed() {
        return attackSpeed;
    }

    public int getMaxNoDamageTicks() {
        return maxNoDamageTicks;
    }

    public long getComboWindowMs() {
        return comboWindowMs;
    }

    public double getBaseKnockback() {
        return baseKnockback;
    }

    public double getComboKnockback() {
        return comboKnockback;
    }

    public int getMaxCombo() {
        return maxCombo;
    }

    public double getKnockbackY() {
        return knockbackY;
    }

    public double getSprintKnockbackBonus() {
        return sprintKnockbackBonus;
    }

    public double getUnarmedKnockbackMultiplier() {
        return unarmedKnockbackMultiplier;
    }

    public boolean isUnarmedCombo() {
        return unarmedCombo;
    }

    public boolean isJumpResetEnabled() {
        return jumpResetEnabled;
    }

    public long getJumpResetWindowMs() {
        return jumpResetWindowMs;
    }

    public double getJumpResetMultiplier() {
        return jumpResetMultiplier;
    }

    public double getJumpResetYMultiplier() {
        return jumpResetYMultiplier;
    }

    public double getAirborneKnockbackMultiplier() {
        return airborneKnockbackMultiplier;
    }

    public boolean isLandingResetsCombo() {
        return landingResetsCombo;
    }

    public double getFishMaxDistance() {
        return fishMaxDistance;
    }

    public double getFishPullMultiplier() {
        return fishPullMultiplier;
    }

    public double getFishPullY() {
        return fishPullY;
    }

    public double getFishDamage() {
        return fishDamage;
    }

    public double getSimulateDamage() {
        return simulateDamage;
    }

    public boolean isDisableOffhand() {
        return disableOffhand;
    }

    public boolean isForceIFramesOnHit() {
        return forceIFramesOnHit;
    }

    public double getBowVelocityMultiplier() {
        return bowVelocityMultiplier;
    }

    public double getBowDamageMultiplier() {
        return bowDamageMultiplier;
    }

    public boolean isBowDisablePunch() {
        return bowDisablePunch;
    }

    public boolean isBowStripTipped() {
        return bowStripTipped;
    }

    public boolean isBlockHitEnabled() {
        return blockHitEnabled;
    }

    public long getBlockHitWindowMs() {
        return blockHitWindowMs;
    }

    public double getBlockHitDamageMultiplier() {
        return blockHitDamageMultiplier;
    }

    public double getKnockbackHorizontalMultiplier() {
        return knockbackHorizontalMultiplier;
    }

    public double getKnockbackVerticalMultiplier() {
        return knockbackVerticalMultiplier;
    }

    public boolean isGoldenAppleEnabled() {
        return goldenAppleEnabled;
    }

    public double getGaAbsorptionHearts() {
        return gaAbsorptionHearts;
    }

    public int getGaRegenSeconds() {
        return gaRegenSeconds;
    }

    public int getGaRegenAmplifier() {
        return gaRegenAmplifier;
    }

    public boolean isEnchantedAppleEnabled() {
        return enchantedAppleEnabled;
    }

    public double getEaAbsorptionHearts() {
        return eaAbsorptionHearts;
    }

    public int getEaRegenSeconds() {
        return eaRegenSeconds;
    }

    public int getEaRegenAmplifier() {
        return eaRegenAmplifier;
    }

    public int getEaResistanceSeconds() {
        return eaResistanceSeconds;
    }

    public int getEaFireResSeconds() {
        return eaFireResSeconds;
    }

}
