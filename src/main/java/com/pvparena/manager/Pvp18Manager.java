package com.pvparena.manager;

import com.pvparena.model.Pvp18Settings;
import com.pvparena.util.SchedulerUtil;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Pvp18Manager {
    public static final String TAG = "pvp_18";
    private final JavaPlugin plugin;
    private final Pvp18PacketManager packetManager;
    private Pvp18Settings settings;
    private final Map<UUID, Double> originalAttackSpeed = new HashMap<>();
    private final Map<UUID, Integer> originalMaxNoDamageTicks = new HashMap<>();
    private final Map<UUID, ItemStack> originalOffhand = new HashMap<>();
    private final Set<UUID> tagged = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> combo = new HashMap<>();
    private final Map<UUID, Long> lastHit = new HashMap<>();
    private final Map<UUID, Long> lastJump = new HashMap<>();
    private final Map<Long, Long> recentFishPullHits = new ConcurrentHashMap<>();
    private final Map<Long, Long> fishDamagePulseBypassUntil = new ConcurrentHashMap<>();

    public Pvp18Manager(JavaPlugin plugin, Pvp18PacketManager packetManager, Pvp18Settings settings) {
        this.plugin = plugin;
        this.packetManager = packetManager;
        this.settings = settings;
    }

    public void updateSettings(Pvp18Settings settings) {
        if (settings != null) {
            this.settings = settings;
        }
    }

    public Pvp18Settings getSettings() {
        return settings;
    }

    public boolean isTagged(Player player) {
        return player.getScoreboardTags().contains(TAG);
    }

    public boolean isTaggedUuid(UUID playerId) {
        return tagged.contains(playerId);
    }

    public boolean isOffhandDisabled() {
        return settings.isDisableOffhand();
    }

    public void enable18Pvp(Player player) {
        if (player == null) {
            return;
        }
        SchedulerUtil.runOnPlayer(plugin, player, () -> {
            if (!player.getScoreboardTags().contains(TAG)) {
                player.addScoreboardTag(TAG);
            }
            tagged.add(player.getUniqueId());
            if (packetManager != null) {
                packetManager.mark(player.getUniqueId());
            }
            if (player.getAttribute(Attribute.GENERIC_ATTACK_SPEED) != null
                    && !originalAttackSpeed.containsKey(player.getUniqueId())) {
                originalAttackSpeed.put(player.getUniqueId(),
                        player.getAttribute(Attribute.GENERIC_ATTACK_SPEED).getBaseValue());
                player.getAttribute(Attribute.GENERIC_ATTACK_SPEED).setBaseValue(settings.getAttackSpeed());
            }
            if (settings.isDisableOffhand() && !originalOffhand.containsKey(player.getUniqueId())) {
                originalOffhand.put(player.getUniqueId(), player.getInventory().getItemInOffHand());
                player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            }
            if (!originalMaxNoDamageTicks.containsKey(player.getUniqueId())) {
                originalMaxNoDamageTicks.put(player.getUniqueId(), player.getMaximumNoDamageTicks());
            }
            player.setMaximumNoDamageTicks(settings.getMaxNoDamageTicks());
            combo.remove(player.getUniqueId());
            lastHit.remove(player.getUniqueId());
            lastJump.remove(player.getUniqueId());
        });
    }

    public void disable18Pvp(Player player) {
        if (player == null) {
            return;
        }
        SchedulerUtil.runOnPlayer(plugin, player, () -> {
            player.removeScoreboardTag(TAG);
            tagged.remove(player.getUniqueId());
            if (packetManager != null) {
                packetManager.unmark(player.getUniqueId());
            }
            if (player.getAttribute(Attribute.GENERIC_ATTACK_SPEED) != null
                    && originalAttackSpeed.containsKey(player.getUniqueId())) {
                player.getAttribute(Attribute.GENERIC_ATTACK_SPEED)
                        .setBaseValue(originalAttackSpeed.remove(player.getUniqueId()));
            }
            if (originalOffhand.containsKey(player.getUniqueId())) {
                player.getInventory().setItemInOffHand(originalOffhand.remove(player.getUniqueId()));
            }
            Integer maxTicks = originalMaxNoDamageTicks.remove(player.getUniqueId());
            if (maxTicks != null) {
                player.setMaximumNoDamageTicks(maxTicks);
            }
            combo.remove(player.getUniqueId());
            lastHit.remove(player.getUniqueId());
            lastJump.remove(player.getUniqueId());
        });
    }

    public void handle18Attack(Player attacker, Player victim) {
        if (attacker == null || victim == null) {
            return;
        }
        boolean unarmed = attacker.getInventory().getItemInMainHand().getType() == Material.AIR;
        long now = System.currentTimeMillis();
        int currentCombo = 1;
        boolean allowCombo = !unarmed || settings.isUnarmedCombo();
        Long last = lastHit.get(attacker.getUniqueId());
        if (allowCombo && last != null && now - last <= settings.getComboWindowMs()) {
            currentCombo = combo.getOrDefault(attacker.getUniqueId(), 0) + 1;
        }
        combo.put(attacker.getUniqueId(), allowCombo ? currentCombo : 1);
        lastHit.put(attacker.getUniqueId(), now);

        if (victim.isBlocking() && victim.getInventory().getItemInOffHand().getType() == Material.SHIELD) {
            victim.setCooldown(Material.SHIELD, 20);
        }

        Vector direction = victim.getLocation().toVector().subtract(attacker.getLocation().toVector());
        direction.setY(0);
        if (direction.lengthSquared() == 0) {
            return;
        }
        direction.normalize();
        double baseKb = settings.getBaseKnockback();
        if (unarmed) {
            baseKb *= settings.getUnarmedKnockbackMultiplier();
        }
        double comboBoost = allowCombo ? Math.min(currentCombo, settings.getMaxCombo()) * settings.getComboKnockback() : 0.0;
        double sprintBoost = attacker.isSprinting() ? settings.getSprintKnockbackBonus() : 0.0;
        double horizontal = (baseKb + comboBoost + sprintBoost) * settings.getKnockbackHorizontalMultiplier();
        double y = settings.getKnockbackY() * settings.getKnockbackVerticalMultiplier();
        Vector knockback = direction.multiply(horizontal);
        knockback.setY(y);
        if (!attacker.isOnGround()) {
            knockback.multiply(settings.getAirborneKnockbackMultiplier());
        }
        if (isJumpResetActive(victim)) {
            knockback.multiply(settings.getJumpResetMultiplier());
            knockback.setY(knockback.getY() * settings.getJumpResetYMultiplier());
        }
        victim.setVelocity(knockback);
        playLegacyBlueHit(victim);
    }

    private void playLegacyBlueHit(Player victim) {
        if (victim == null || victim.getWorld() == null) {
            return;
        }
        try {
            Particle blue = Particle.valueOf("ENCHANTED_HIT");
            victim.getWorld().spawnParticle(blue,
                    victim.getLocation().add(0.0, 1.0, 0.0),
                    12, 0.25, 0.35, 0.25, 0.01);
        } catch (Throwable ignored) {
        }
    }

    public void recordJump(Player player) {
        if (player == null || !settings.isJumpResetEnabled()) {
            return;
        }
        lastJump.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void resetCombo(Player player) {
        if (player == null || !settings.isLandingResetsCombo()) {
            return;
        }
        combo.remove(player.getUniqueId());
        lastHit.remove(player.getUniqueId());
    }

    public boolean isJumpResetActiveFor(Player player) {
        return isJumpResetActive(player);
    }

    private boolean isJumpResetActive(Player player) {
        if (player == null || !settings.isJumpResetEnabled()) {
            return false;
        }
        Long time = lastJump.get(player.getUniqueId());
        if (time == null) {
            return false;
        }
        return System.currentTimeMillis() - time <= settings.getJumpResetWindowMs();
    }

    public void applyIFrames(Player victim) {
        if (victim == null || !settings.isForceIFramesOnHit()) {
            return;
        }
        SchedulerUtil.runOnPlayer(plugin, victim, () -> victim.setNoDamageTicks(settings.getMaxNoDamageTicks()));
    }

    public void applyNoCritical(Player attacker, EntityDamageByEntityEvent event) {
        if (attacker == null || event == null) {
            return;
        }
        if (!isCritical(attacker)) {
            return;
        }
        double damage = event.getDamage();
        if (damage > 0) {
            event.setDamage(damage / 1.5);
        }
    }

    public void applyBowDamage(Player attacker, EntityDamageByEntityEvent event) {
        if (attacker == null || event == null) {
            return;
        }
        if (event.getCause() != EntityDamageByEntityEvent.DamageCause.PROJECTILE) {
            return;
        }
        double mult = settings.getBowDamageMultiplier();
        if (mult != 1.0) {
            event.setDamage(event.getDamage() * mult);
        }
    }

    public void applyGoldenApple(Player player, boolean enchanted) {
        if (player == null) {
            return;
        }
        SchedulerUtil.runOnPlayer(plugin, player, () -> {
            if (enchanted && settings.isEnchantedAppleEnabled()) {
                double absHearts = Math.max(0.0, settings.getEaAbsorptionHearts());
                player.setAbsorptionAmount(absHearts * 2.0);
                player.removePotionEffect(PotionEffectType.REGENERATION);
                player.removePotionEffect(PotionEffectType.RESISTANCE);
                player.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
                if (settings.getEaRegenSeconds() > 0) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, settings.getEaRegenSeconds() * 20, Math.max(0, settings.getEaRegenAmplifier())));
                }
                if (settings.getEaResistanceSeconds() > 0) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, settings.getEaResistanceSeconds() * 20, 0));
                }
                if (settings.getEaFireResSeconds() > 0) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, settings.getEaFireResSeconds() * 20, 0));
                }
                return;
            }
            if (!enchanted && settings.isGoldenAppleEnabled()) {
                double absHearts = Math.max(0.0, settings.getGaAbsorptionHearts());
                player.setAbsorptionAmount(absHearts * 2.0);
                player.removePotionEffect(PotionEffectType.REGENERATION);
                if (settings.getGaRegenSeconds() > 0) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, settings.getGaRegenSeconds() * 20, Math.max(0, settings.getGaRegenAmplifier())));
                }
            }
        });
    }

    private boolean isCritical(Player attacker) {
        if (attacker.isOnGround()) {
            return false;
        }
        if (attacker.isInWater() || attacker.isInLava() || attacker.isSwimming()) {
            return false;
        }
        if (attacker.isClimbing()) {
            return false;
        }
        if (attacker.isFlying() || attacker.getAllowFlight()) {
            return false;
        }
        if (attacker.isInsideVehicle()) {
            return false;
        }
        if (attacker.isSprinting()) {
            return false;
        }
        if (attacker.hasPotionEffect(PotionEffectType.BLINDNESS)) {
            return false;
        }
        return attacker.getFallDistance() > 0.0f;
    }

    public void handleFishingPull(Player attacker, Player victim, boolean simulateHit) {
        if (attacker == null || victim == null) {
            return;
        }
        if (attacker.getWorld() == null || victim.getWorld() == null) {
            return;
        }
        if (!attacker.getWorld().equals(victim.getWorld())) {
            return;
        }
        if (attacker.getLocation().distance(victim.getLocation()) > settings.getFishMaxDistance()) {
            return;
        }
        long now = System.currentTimeMillis();
        long key = fishPullKey(attacker.getUniqueId(), victim.getUniqueId());
        Long last = recentFishPullHits.put(key, now);
        if (last != null && now - last < 75L) {
            return;
        }
        if (recentFishPullHits.size() > 4096) {
            long cutoff = now - 2000L;
            recentFishPullHits.entrySet().removeIf(e -> e.getValue() == null || e.getValue() < cutoff);
        }
        SchedulerUtil.runOnPlayer(plugin, victim, () -> {
            int original = victim.getNoDamageTicks();
            // Rod hit should feel like a normal hand hit (1.8-style), not custom pull/push physics.
            handle18Attack(attacker, victim);
            if (simulateHit) {
                double beforeHealth = victim.getHealth();
                double beforeAbsorption = victim.getAbsorptionAmount();
                victim.setNoDamageTicks(0);
                markFishDamagePulse(attacker, victim, 250L);
                double pulse = Math.max(1.0, settings.getSimulateDamage());
                victim.damage(pulse, attacker);
                double afterHealth = victim.getHealth();
                double max = victim.getMaxHealth();
                if (afterHealth < beforeHealth) {
                    victim.setHealth(Math.min(max, afterHealth + (beforeHealth - afterHealth)));
                }
                if (victim.getAbsorptionAmount() < beforeAbsorption) {
                    victim.setAbsorptionAmount(beforeAbsorption);
                }
            }
            if (settings.getFishDamage() > 0.0) {
                victim.setNoDamageTicks(0);
                markFishDamagePulse(attacker, victim, 250L);
                victim.damage(settings.getFishDamage(), attacker);
            }
            victim.setNoDamageTicks(original);
        });
    }

    public boolean consumeFishDamagePulseBypass(Player attacker, Player victim) {
        if (attacker == null || victim == null) {
            return false;
        }
        long key = fishPullKey(attacker.getUniqueId(), victim.getUniqueId());
        Long until = fishDamagePulseBypassUntil.get(key);
        if (until == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now > until) {
            fishDamagePulseBypassUntil.remove(key);
            return false;
        }
        fishDamagePulseBypassUntil.remove(key);
        return true;
    }

    private void markFishDamagePulse(Player attacker, Player victim, long ttlMillis) {
        if (attacker == null || victim == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long key = fishPullKey(attacker.getUniqueId(), victim.getUniqueId());
        fishDamagePulseBypassUntil.put(key, now + Math.max(1L, ttlMillis));
        if (fishDamagePulseBypassUntil.size() > 4096) {
            fishDamagePulseBypassUntil.entrySet().removeIf(e -> e.getValue() == null || e.getValue() < now);
        }
    }

    private long fishPullKey(UUID attacker, UUID victim) {
        long a = attacker.getMostSignificantBits() ^ attacker.getLeastSignificantBits();
        long b = victim.getMostSignificantBits() ^ victim.getLeastSignificantBits();
        return (a * 1315423911L) ^ b;
    }
}
