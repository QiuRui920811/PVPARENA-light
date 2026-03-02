package com.pvparena.listener;

import com.pvparena.config.PluginSettings;
import com.pvparena.hook.husksync.HuskSyncWriteBack;
import com.pvparena.manager.MatchManager;
import com.pvparena.model.MatchSession;
import com.pvparena.util.SchedulerUtil;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.EntityEffect;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MatchListener implements Listener {
    private final MatchManager matchManager;
    private final com.pvparena.manager.PkManager pkManager;
    private final PluginSettings settings;
    private final Map<UUID, List<ItemStack>> pendingDeathDrops = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingEliminatedRespawn = new ConcurrentHashMap<>();

    public MatchListener(MatchManager matchManager, com.pvparena.manager.PkManager pkManager,
                         PluginSettings settings) {
        this.matchManager = matchManager;
        this.pkManager = pkManager;
        this.settings = settings;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingEliminatedRespawn.remove(event.getPlayer().getUniqueId());
        matchManager.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSelfKillCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!matchManager.isInMatch(player)) {
            return;
        }
        String cmd = event.getMessage() == null ? "" : event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (!(cmd.equals("/kill") || cmd.equals("/minecraft:kill") || cmd.equals("/suicide"))) {
            return;
        }
        event.setCancelled(true);
        matchManager.handleDeath(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        MatchSession session = matchManager.getMatch(player);
        if (!matchManager.isInMatch(player)) {
            return;
        }

        boolean dropInventoryMode = session != null && session.isDropInventoryMode();
        boolean dropInventoryThisDeath = shouldDropInventoryThisDeath(session, player.getUniqueId());

        // Avoid item loss/duplication; actual restore happens on PlayerRespawnEvent.
        if (!dropInventoryMode || !dropInventoryThisDeath) {
            try {
                event.setKeepInventory(true);
                event.setKeepLevel(true);
            } catch (Throwable ignored) {
            }
            event.getDrops().clear();
            event.setDroppedExp(0);
            pendingDeathDrops.remove(player.getUniqueId());
        } else {
            // Force vanilla drop behavior even when world gamerule keepInventory=true.
            try {
                event.setKeepInventory(false);
                event.setKeepLevel(false);
            } catch (Throwable ignored) {
            }
            ensureDropFallbackFromCache(event, player.getUniqueId());
            debugCombat("drop death player=" + player.getName() + " dropsBefore=" + event.getDrops().size());
        }

        org.bukkit.plugin.java.JavaPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
        long resolveDelayTicks = getRoundResolveDelayTicks();
        boolean alreadyResolving = session != null && session.isRoundResolving();
        if (!alreadyResolving) {
            if (resolveDelayTicks > 0L) {
                SchedulerUtil.runOnPlayerLater(plugin, player, resolveDelayTicks, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    matchManager.handleDeath(player);
                });
            } else {
                matchManager.handleDeath(player);
            }
        }
        pendingEliminatedRespawn.put(player.getUniqueId(), System.currentTimeMillis() + 60_000L);

        // Auto-respawn with retries so the player doesn't need to click the respawn button.
        long respawnDelayTicks = Math.max(1L, resolveDelayTicks);
        scheduleAutoRespawnRetries(plugin, player, respawnDelayTicks);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        boolean forceEliminatedState = consumePendingEliminatedRespawn(player.getUniqueId());
        MatchSession liveSession = matchManager.getMatch(player);
        if (liveSession != null && liveSession.isRoundResolving()) {
            UUID winnerId = liveSession.getOpponent(player.getUniqueId());
            if (winnerId != null) {
                matchManager.enterRoundEliminatedSpectator(player.getUniqueId(), winnerId);
            }
            org.bukkit.plugin.java.JavaPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
            scheduleEliminatedStateReapply(plugin, player, true);
        } else if (forceEliminatedState) {
            org.bukkit.plugin.java.JavaPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
            scheduleEliminatedStateReapply(plugin, player, false);
        }
        var snapshot = matchManager.peekPendingRestore(player);
        if (snapshot == null) {
            debugRestore("onRespawn no-pending " + player.getUniqueId());
            return;
        }
        debugRestore("onRespawn pending " + player.getUniqueId() + " " + snapshot.debugFingerprint());
        matchManager.blockExternalSync(player.getUniqueId());
        if (snapshot.getLocation() != null && snapshot.getLocation().getWorld() != null) {
            event.setRespawnLocation(snapshot.getLocation());
        }
        org.bukkit.plugin.java.JavaPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
        long[] delays = isPluginEnabled(plugin, "HuskSync")
                ? new long[]{2L, 20L, 60L}
                : new long[]{1L};

        for (long delay : delays) {
            com.pvparena.util.SchedulerUtil.runOnPlayerLater(plugin, player, delay, () -> {
                debugRestore("respawn delayed check player=" + player.getUniqueId() + " delay=" + delay);
                if (!player.isOnline() || player.isDead()) {
                    debugRestore("respawn skip offline/dead " + player.getUniqueId());
                    return;
                }
                // If the player already joined another match, don't restore old snapshot.
                if (matchManager.isInMatch(player)) {
                    debugRestore("respawn skip in-match " + player.getUniqueId());
                    return;
                }
                if (!matchManager.hasPendingRestore(player.getUniqueId())) {
                    debugRestore("respawn skip no-pending-now " + player.getUniqueId());
                    return;
                }
                matchManager.blockExternalSync(player.getUniqueId());
                debugRestore("respawn apply restore " + player.getUniqueId() + " " + snapshot.debugFingerprint());
                snapshot.restore(player);
                HuskSyncWriteBack.writeBackCurrentState(plugin, player);
                if (snapshot.getLocation() != null && snapshot.getLocation().getWorld() != null) {
                    player.teleportAsync(snapshot.getLocation()).whenComplete((success, throwable) -> {
                        debugRestore("respawn teleport result player=" + player.getUniqueId()
                                + " success=" + success + " throwable=" + (throwable != null));
                        if (throwable == null && Boolean.TRUE.equals(success)) {
                            matchManager.consumePendingRestore(player);
                        }
                    });
                }
            });
        }
    }

    private void debugRestore(String msg) {
        try {
            if (settings.isRestoreDebug()) {
                org.bukkit.Bukkit.getLogger().info("[RestoreDebug][MatchListener] " + msg);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isPluginEnabled(org.bukkit.plugin.java.JavaPlugin plugin, String name) {
        try {
            var other = plugin.getServer().getPluginManager().getPlugin(name);
            return other != null && other.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void scheduleAutoRespawnRetries(org.bukkit.plugin.java.JavaPlugin plugin, Player player, long baseDelayTicks) {
        long base = Math.max(1L, baseDelayTicks);
        long[] attempts = new long[]{1L, 2L, 4L, 8L, base, base + 2L, base + 6L, base + 12L};
        for (long delay : attempts) {
            SchedulerUtil.runOnPlayerLater(plugin, player, delay, () -> {
                if (!player.isOnline() || !player.isDead()) {
                    return;
                }
                try {
                    player.spigot().respawn();
                    return;
                } catch (Throwable ignored) {
                }
                try {
                    player.getClass().getMethod("respawn").invoke(player);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private void scheduleEliminatedStateReapply(org.bukkit.plugin.java.JavaPlugin plugin, Player player, boolean requireRoundResolving) {
        long[] delays = new long[]{1L, 4L, 10L, 20L, 40L, 80L, 120L, 200L};
        for (long delay : delays) {
            SchedulerUtil.runOnPlayerLater(plugin, player, delay, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (requireRoundResolving) {
                    MatchSession current = matchManager.getMatch(player);
                    if (current == null || !current.isRoundResolving()) {
                        return;
                    }
                }
                applyEliminatedState(player);
            });
        }
    }

    private boolean consumePendingEliminatedRespawn(UUID playerId) {
        Long until = pendingEliminatedRespawn.remove(playerId);
        return until != null && System.currentTimeMillis() <= until;
    }

    private void applyEliminatedState(Player player) {
        try {
            player.setInvisible(true);
        } catch (Throwable ignored) {
        }
        try {
            player.setInvulnerable(true);
        } catch (Throwable ignored) {
        }
        try {
            player.setCollidable(false);
        } catch (Throwable ignored) {
        }
        try {
            player.setCanPickupItems(false);
        } catch (Throwable ignored) {
        }
        try {
            player.setAllowFlight(true);
            player.setFlying(true);
        } catch (Throwable ignored) {
        }
        try {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 20 * 15, 0, false, false, false));
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethalDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        // Handle non-PVP lethal causes (FALL, VOID, LAVA, etc.) while in a match.
        // Otherwise Minecraft will perform a real death -> respawn screen -> inventory wipe,
        // and our later snapshot/restore can end up restoring the wrong (wiped) state.
        MatchSession session = matchManager.getMatch(player);
        if (session == null || !session.isFighting()) {
            return;
        }
        if (isLethalAfterAbsorption(player, event.getFinalDamage())) {
            if (hasTotem(player)) {
                // Let vanilla consume totem + play animation; match continues.
                return;
            }
            if (!session.beginRoundResolution()) {
                event.setCancelled(true);
                return;
            }
            boolean vanillaDeathFlow = shouldUseVanillaDeathFlow(session);
            if (vanillaDeathFlow) {
                cachePendingDeathDrops(player);
                event.setCancelled(false);
                org.bukkit.plugin.java.JavaPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
                long resolveDelayTicks = Math.max(1L, session.getRoundResolveDelayTicks());
                SchedulerUtil.runOnPlayerLater(plugin, player, resolveDelayTicks + 2L, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    MatchSession current = matchManager.getMatch(player);
                    if (current != session || !current.isRoundResolving()) {
                        return;
                    }
                    matchManager.handleDeath(player);
                });
                return;
            }
            event.setCancelled(true);
            applyEnvironmentalFinisherFeedback(player);

            org.bukkit.plugin.java.JavaPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
            long resolveDelayTicks = session.getRoundResolveDelayTicks();
            SchedulerUtil.runOnPlayerLater(plugin, player, resolveDelayTicks, () -> {
                if (!player.isOnline()) {
                    return;
                }
                MatchSession current = matchManager.getMatch(player);
                if (current != session || !current.isRoundResolving()) {
                    return;
                }
                matchManager.handleDeath(player);
            });
        }
    }

    private void applyEnvironmentalFinisherFeedback(Player player) {
        if (player == null) {
            return;
        }
        try {
            player.setFireTicks(0);
            player.setNoDamageTicks(0);
            player.playEffect(EntityEffect.DEATH);
        } catch (Throwable ignored) {
        }
        playRoundDeathLightning(player);
    }

    private boolean hasTotem(Player player) {
        if (player == null) {
            return false;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() == Material.TOTEM_OF_UNDYING) {
            return true;
        }
        ItemStack mainhand = player.getInventory().getItemInMainHand();
        return mainhand != null && mainhand.getType() == Material.TOTEM_OF_UNDYING;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Entity victim = event.getEntity();

        if (victim instanceof Player victimPlayer) {
            MatchSession victimSession = matchManager.getMatch(victimPlayer);
            boolean playerSourcedDamage = damager instanceof Player
                    || (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player);
            if (victimSession != null && victimSession.isFighting()
                    && isLethalAfterAbsorption(victimPlayer, event.getFinalDamage())
                    && !playerSourcedDamage) {
                if (hasTotem(victimPlayer)) {
                    event.setCancelled(false);
                    return;
                }
                if (!victimSession.beginRoundResolution()) {
                    event.setCancelled(true);
                    return;
                }
                if (shouldUseVanillaDeathFlow(victimSession)) {
                    cachePendingDeathDrops(victimPlayer);
                    event.setCancelled(false);
                    org.bukkit.plugin.java.JavaPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
                    long resolveDelayTicks = Math.max(1L, victimSession.getRoundResolveDelayTicks());
                    SchedulerUtil.runOnPlayerLater(plugin, victimPlayer, resolveDelayTicks + 2L, () -> {
                        if (!victimPlayer.isOnline()) {
                            return;
                        }
                        MatchSession current = matchManager.getMatch(victimPlayer);
                        if (current != victimSession || !current.isRoundResolving()) {
                            return;
                        }
                        matchManager.handleDeath(victimPlayer);
                    });
                    return;
                }
                event.setCancelled(true);
                matchManager.handleDeath(victimPlayer);
                return;
            }
        }

        // Bot -> player lethal hit should also end match at the lethal-hit moment.
        if (victim instanceof Player victimPlayer
                && damager instanceof org.bukkit.entity.LivingEntity livingDamager) {
            com.pvparena.model.BotMatchSession botSession = matchManager.getBotMatch(victimPlayer);
            com.pvparena.model.BotMatchSession damagerSession = matchManager.getBotMatchByEntity(livingDamager);
            if (botSession != null && botSession.isFighting() && damagerSession == botSession) {
                if (isLethalAfterAbsorption(victimPlayer, event.getFinalDamage())) {
                    if (hasTotem(victimPlayer)) {
                        // Let vanilla consume totem; bot match continues.
                        event.setCancelled(false);
                        return;
                    }
                    event.setCancelled(true);
                    matchManager.handleDeath(victimPlayer);
                    return;
                }
                event.setCancelled(false);
                return;
            }
        }
        Player attacker = null;
        if (damager instanceof Player player) {
            attacker = player;
        } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null || !(victim instanceof Player defender)) {
            return;
        }
        MatchSession attackerMatch = matchManager.getMatch(attacker);
        MatchSession defenderMatch = matchManager.getMatch(defender);
        debugCombat("onDamage pre attacker=" + attacker.getName()
                + " victim=" + defender.getName()
                + " cause=" + event.getCause()
                + " cancelled=" + event.isCancelled()
                + " aMatch=" + (attackerMatch != null)
                + " bMatch=" + (defenderMatch != null)
                + " same=" + (attackerMatch != null && attackerMatch == defenderMatch)
                + " aFighting=" + (attackerMatch != null && attackerMatch.isFighting())
                + " aResolving=" + (attackerMatch != null && attackerMatch.isRoundResolving())
                + " dmg=" + event.getFinalDamage());
            traceHit("pre", attacker, defender, event, attackerMatch, defenderMatch);
        if (attackerMatch == null || defenderMatch == null || attackerMatch != defenderMatch) {
            debugCombat("skip non-match attacker=" + attacker.getName() + " victim=" + defender.getName());
            return;
        }
        if (attackerMatch.isRoundResolving()) {
            event.setCancelled(true);
            debugCombat("cancel resolving attacker=" + attacker.getName() + " victim=" + defender.getName());
            return;
        }
        if (!attackerMatch.isFighting()) {
            event.setCancelled(true);
            debugCombat("cancel not-fighting attacker=" + attacker.getName() + " victim=" + defender.getName());
            return;
        }
        // Only end match on the opponent's lethal hit.
        if (isLethalAfterAbsorption(defender, event.getFinalDamage())) {
            if (hasTotem(defender)) {
                // Let vanilla consume totem + play animation; match continues.
                event.setCancelled(false);
                return;
            }

            // Fully take over lethal hits to avoid vanilla death screen and keep one restore flow.
            if (!attackerMatch.beginRoundResolution()) {
                event.setCancelled(true);
                debugCombat("cancel lethal beginRoundResolution=false attacker=" + attacker.getName() + " victim=" + defender.getName());
                return;
            }
            double effectiveHealth = defender.getHealth() + Math.max(0.0, defender.getAbsorptionAmount());
            double lethalDamage = Math.max(0.0, event.getFinalDamage());
            attackerMatch.recordDamage(attacker.getUniqueId(), defender.getUniqueId(), Math.min(lethalDamage, effectiveHealth));
            playRoundDeathLightning(defender);

            if (shouldUseVanillaDeathFlow(attackerMatch)) {
                cachePendingDeathDrops(defender);
                // Let vanilla deliver the lethal hit so players see real death feedback,
                // while round resolution is already locked and auto-respawn flow still applies.
                event.setCancelled(false);
                org.bukkit.plugin.java.JavaPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
                long resolveDelayTicks = Math.max(1L, attackerMatch.getRoundResolveDelayTicks());
                SchedulerUtil.runOnPlayerLater(plugin, defender, resolveDelayTicks + 2L, () -> {
                    if (!defender.isOnline()) {
                        return;
                    }
                    MatchSession current = matchManager.getMatch(defender);
                    if (current != attackerMatch || !current.isRoundResolving()) {
                        return;
                    }
                    // Fallback: if vanilla death didn't fire (or was altered by other plugins),
                    // force round defeat resolution to avoid match getting stuck.
                    matchManager.handleDeath(defender);
                });
                return;
            }

            event.setCancelled(true);
            applyFinisherFeedback(attacker, defender, lethalDamage);
            org.bukkit.plugin.java.JavaPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
            long resolveDelayTicks = attackerMatch.getRoundResolveDelayTicks();
            SchedulerUtil.runOnPlayerLater(plugin, defender, resolveDelayTicks, () -> {
                if (!defender.isOnline()) {
                    return;
                }
                matchManager.handleDeath(defender);
            });
            return;
        }
        event.setCancelled(false);
        debugCombat("allow normal-hit attacker=" + attacker.getName() + " victim=" + defender.getName() + " dmg=" + event.getFinalDamage());
        traceHit("allow", attacker, defender, event, attackerMatch, defenderMatch);
        attackerMatch.recordDamage(attacker.getUniqueId(), defender.getUniqueId(), event.getFinalDamage());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        MatchSession session = getFightingSessionAt(event.getLocation());
        if (session == null) {
            return;
        }
        if (!session.allowsBlockEdit()) {
            event.setCancelled(true);
            event.blockList().clear();
            return;
        }
        event.setCancelled(false);
        Iterator<org.bukkit.block.Block> iterator = event.blockList().iterator();
        while (iterator.hasNext()) {
            org.bukkit.block.Block block = iterator.next();
            if (!session.isInArenaRollbackArea(block.getLocation())) {
                iterator.remove();
                continue;
            }
            session.captureBlockBeforeBreak(block);
        }
        event.setYield(0.0f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        MatchSession session = getFightingSessionAt(event.getBlock().getLocation());
        if (session == null) {
            return;
        }
        if (!session.allowsBlockEdit()) {
            event.setCancelled(true);
            event.blockList().clear();
            return;
        }
        event.setCancelled(false);
        Iterator<org.bukkit.block.Block> iterator = event.blockList().iterator();
        while (iterator.hasNext()) {
            org.bukkit.block.Block block = iterator.next();
            if (!session.isInArenaRollbackArea(block.getLocation())) {
                iterator.remove();
                continue;
            }
            session.captureBlockBeforeBreak(block);
        }
        event.setYield(0.0f);
    }

    private boolean isLethalAfterAbsorption(Player player, double finalDamage) {
        if (player == null) {
            return false;
        }
        double effectiveHealth = player.getHealth() + Math.max(0.0, player.getAbsorptionAmount());
        return effectiveHealth - finalDamage <= 0.0;
    }

    private void applyFinisherFeedback(Player attacker, Player defender, double finalDamage) {
        if (attacker == null || defender == null) {
            return;
        }
        try {
            defender.setNoDamageTicks(0);
            defender.setAbsorptionAmount(0.0);
            double remaining = Math.max(0.1, defender.getHealth() - Math.max(0.0, finalDamage));
            defender.setHealth(Math.min(defender.getMaxHealth(), remaining));
        } catch (Throwable ignored) {
        }

        try {
            Vector knockback = defender.getLocation().toVector().subtract(attacker.getLocation().toVector());
            knockback.setY(0.0);
            if (knockback.lengthSquared() < 1.0E-6) {
                knockback = attacker.getLocation().getDirection().setY(0.0);
            }
            if (knockback.lengthSquared() > 1.0E-6) {
                knockback.normalize().multiply(0.33).setY(0.18);
                defender.setVelocity(knockback);
            }
        } catch (Throwable ignored) {
        }

        try {
            defender.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                    defender.getLocation().add(0.0, 1.0, 0.0),
                    20, 0.35, 0.45, 0.35, 0.05);
        } catch (Throwable ignored) {
        }

        try {
            defender.playEffect(EntityEffect.DEATH);
        } catch (Throwable ignored) {
        }

        try {
            attacker.playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.05f);
            defender.playSound(defender.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, 0.85f);
        } catch (Throwable ignored) {
        }
    }

    private void playRoundDeathLightning(Player target) {
        if (target == null) {
            return;
        }
        Location location = target.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        try {
            location.getWorld().strikeLightningEffect(location);
        } catch (Throwable ignored) {
            try {
                location.getWorld().playSound(location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.2f, 1.0f);
            } catch (Throwable ignoredAgain) {
            }
        }
    }

    private boolean useVanillaRoundDeathAnimation() {
        org.bukkit.plugin.java.JavaPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
        return plugin.getConfig().getBoolean("match.round-end-use-vanilla-death-animation", true);
    }

    private boolean shouldUseVanillaDeathFlow(MatchSession session) {
        return (session != null && session.isDropInventoryMode()) || useVanillaRoundDeathAnimation();
    }

    private boolean shouldDropInventoryThisDeath(MatchSession session, UUID loserId) {
        if (session == null || !session.isDropInventoryMode()) {
            return false;
        }
        if (!session.getMode().getSettings().isDropOnFinalRoundOnly()) {
            return true;
        }
        if (loserId == null) {
            return true;
        }
        if (!loserId.equals(session.getPlayerA()) && !loserId.equals(session.getPlayerB())) {
            return true;
        }
        return session.willDefeatEndMatch(loserId);
    }

    private void cachePendingDeathDrops(Player player) {
        if (player == null) {
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
                continue;
            }
            items.add(item.clone());
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() != Material.AIR && offhand.getAmount() > 0) {
            items.add(offhand.clone());
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
                continue;
            }
            items.add(item.clone());
        }
        pendingDeathDrops.put(player.getUniqueId(), items);
    }

    private void ensureDropFallbackFromCache(PlayerDeathEvent event, UUID playerId) {
        if (event == null || playerId == null) {
            return;
        }
        List<ItemStack> cached = pendingDeathDrops.remove(playerId);
        if (cached == null || cached.isEmpty()) {
            return;
        }
        if (!event.getDrops().isEmpty()) {
            return;
        }
        for (ItemStack item : cached) {
            if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
                continue;
            }
            event.getDrops().add(item.clone());
        }
    }

    private long getRoundResolveDelayTicks() {
        org.bukkit.plugin.java.JavaPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
        return Math.max(0L, Math.min(100L, plugin.getConfig().getLong("match.round-end-showcase-ticks", 12L)));
    }

    private MatchSession getFightingSessionAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        for (MatchSession session : matchManager.getActiveMatchSessions()) {
            if (session == null || !session.isFighting()) {
                continue;
            }
            if (!session.getMode().getSettings().isBuildEnabled()) {
                continue;
            }
            if (!session.isInArenaRollbackArea(location)) {
                continue;
            }
            return session;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        MatchSession session = matchManager.getMatch(player);
        if (session == null || !session.isFighting()) {
            return;
        }
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }
        session.recordDamage(session.getOpponent(player.getUniqueId()), player.getUniqueId(), event.getFinalDamage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onLegacyHitTrace(EntityDamageByEntityEvent event) {
        if (!isCombatDebugEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = extractAttacker(event.getDamager());
        if (attacker == null) {
            return;
        }
        MatchSession attackerMatch = matchManager.getMatch(attacker);
        MatchSession defenderMatch = matchManager.getMatch(victim);
        if (attackerMatch == null || attackerMatch != defenderMatch) {
            return;
        }
        traceHit("monitor", attacker, victim, event, attackerMatch, defenderMatch);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFinalDamageUncancel(EntityDamageByEntityEvent event) {
        if (!event.isCancelled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = null;
        Entity damager = event.getDamager();
        if (damager instanceof Player p) {
            attacker = p;
        } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null) {
            return;
        }
        MatchSession a = matchManager.getMatch(attacker);
        MatchSession b = matchManager.getMatch(victim);
        if (a == null || a != b || !a.isFighting() || a.isRoundResolving()) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        if (isLethalAfterAbsorption(victim, event.getFinalDamage())) {
            return;
        }
        event.setCancelled(false);
        debugCombat("uncancel in-match non-lethal attacker=" + attacker.getName()
                + " victim=" + victim.getName()
                + " cause=" + event.getCause()
                + " dmg=" + event.getFinalDamage());
    }

    private void debugCombat(String msg) {
        try {
            org.bukkit.plugin.java.JavaPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
            if (plugin.getConfig().getBoolean("debug.combat", false)) {
                plugin.getLogger().info("[CombatDebug] " + msg);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isCombatDebugEnabled() {
        try {
            org.bukkit.plugin.java.JavaPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
            return plugin.getConfig().getBoolean("debug.combat", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Player extractAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    private void traceHit(String phase,
                          Player attacker,
                          Player defender,
                          EntityDamageByEntityEvent event,
                          MatchSession attackerMatch,
                          MatchSession defenderMatch) {
        if (!isCombatDebugEnabled() || attacker == null || defender == null || event == null) {
            return;
        }
        double distance = -1.0;
        try {
            if (attacker.getWorld().equals(defender.getWorld())) {
                distance = attacker.getLocation().distance(defender.getLocation());
            }
        } catch (Throwable ignored) {
        }
        String distText = distance < 0 ? "na" : String.format(Locale.ROOT, "%.2f", distance);
        String damageText = String.format(Locale.ROOT, "%.3f", event.getDamage());
        String finalDamageText = String.format(Locale.ROOT, "%.3f", event.getFinalDamage());
        String attackerCooldown = String.format(Locale.ROOT, "%.3f", attacker.getAttackCooldown());
        debugCombat("trace phase=" + phase
                + " attacker=" + attacker.getName()
                + " victim=" + defender.getName()
                + " cause=" + event.getCause()
                + " cancelled=" + event.isCancelled()
                + " dmg=" + damageText
                + " final=" + finalDamageText
                + " dist=" + distText
                + " aSprint=" + attacker.isSprinting()
                + " aGround=" + attacker.isOnGround()
                + " aCooldown=" + attackerCooldown
                + " vNoDmg=" + defender.getNoDamageTicks() + "/" + defender.getMaximumNoDamageTicks()
                + " vHealth=" + String.format(Locale.ROOT, "%.2f", defender.getHealth())
                + " vAbs=" + String.format(Locale.ROOT, "%.2f", defender.getAbsorptionAmount())
                + " sameMatch=" + (attackerMatch != null && attackerMatch == defenderMatch)
                + " legacyMode=" + (attackerMatch != null && attackerMatch.getMode().getSettings().isLegacyPvp()));
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        MatchSession session = matchManager.getMatch(player);
        if (session == null) {
            return;
        }
        if (session.isMovementLocked() && settings.isLockMovementDuringCountdown()) {
            if (event.getFrom().getX() != event.getTo().getX()
                    || event.getFrom().getY() != event.getTo().getY()
                    || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setTo(event.getFrom());
            }
            return;
        }
        if (settings.isBorderEnabled() && !session.getArena().isInsideBounds(event.getTo())) {
            if (session.isFighting() && settings.isBorderLoseOnExit()) {
                String reason = com.pvparena.util.MessageUtil.getPlain(settings.getBorderLoseReasonKey());
                if (reason == null || reason.isEmpty()) {
                    reason = "Out of bounds";
                }
                matchManager.handleDefeat(player, reason);
            } else {
                event.setTo(event.getFrom());
                if (settings.getBorderWarnKey() != null && !settings.getBorderWarnKey().isEmpty()) {
                    com.pvparena.util.MessageUtil.send(player, settings.getBorderWarnKey());
                }
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        MatchSession session = matchManager.getMatch(player);
        if (session != null) {
            if (!session.allowsBlockEdit()) {
                event.setCancelled(true);
                return;
            }
            if (!session.isInArenaRollbackArea(event.getBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
            if (session.getMode().getSettings().isBreakPlacedBlocksOnly() && !session.canBreakPlacedBlock(event.getBlock())) {
                event.setCancelled(true);
                return;
            }
            session.captureBlockBeforeBreak(event.getBlock());
            session.unmarkPlacedBlock(event.getBlock());
            event.setDropItems(false);
            event.setExpToDrop(0);
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFluidFlow(BlockFromToEvent event) {
        if (event.getBlock() == null || !event.getBlock().isLiquid()) {
            return;
        }
        org.bukkit.block.Block from = event.getBlock();
        org.bukkit.block.Block to = event.getToBlock();
        if (to == null || to.getWorld() == null) {
            return;
        }

        MatchSession matched = null;
        boolean toInside = false;
        boolean fromInside = false;
        for (MatchSession session : matchManager.getActiveMatchSessions()) {
            if (session == null) {
                continue;
            }
            boolean insideTo = session.isInArenaRollbackArea(to.getLocation());
            boolean insideFrom = session.isInArenaRollbackArea(from.getLocation());
            if (!insideTo && !insideFrom) {
                continue;
            }
            matched = session;
            toInside = insideTo;
            fromInside = insideFrom;
            break;
        }
        if (matched == null) {
            return;
        }

        // Outside of active fighting, always freeze fluid updates inside arena bounds
        // so rollback/countdown/preparing phases won't get re-contaminated.
        boolean canFlowInThisPhase = matched.isFighting() && matched.getMode().getSettings().isBuildEnabled();
        if (!canFlowInThisPhase) {
            event.setCancelled(true);
            return;
        }

        // Non-build modes never allow fluid map changes.
        // (kept by canFlowInThisPhase guard above)

        // Prevent external sources from permanently flowing into arena bounds.
        if (toInside && !fromInside) {
            event.setCancelled(true);
            return;
        }

        // Capture every fluid spread target so rollback can restore original block state.
        if (toInside) {
            matched.captureBlockBeforePlace(to.getState());
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        MatchSession session = matchManager.getMatch(player);
        if (session != null) {
            if (!session.allowsBlockEdit()) {
                event.setCancelled(true);
                return;
            }
            if (!session.isInArenaRollbackArea(event.getBlockPlaced().getLocation())) {
                event.setCancelled(true);
                return;
            }
            session.captureBlockBeforePlace(event.getBlockReplacedState());
            session.markPlacedBlock(event.getBlockPlaced());
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        MatchSession session = matchManager.getMatch(player);
        if (session == null) {
            return;
        }

        org.bukkit.block.Block target = event.getBlockClicked() == null
                ? null
                : event.getBlockClicked().getRelative(event.getBlockFace());
        if (target == null) {
            event.setCancelled(true);
            return;
        }
        if (!session.allowsBlockEdit()) {
            event.setCancelled(true);
            return;
        }
        if (!session.isInArenaRollbackArea(target.getLocation())) {
            event.setCancelled(true);
            return;
        }

        session.captureBlockBeforePlace(target.getState());
        session.markPlacedBlock(target);
        event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Player player = event.getPlayer();
        MatchSession session = matchManager.getMatch(player);
        if (session == null) {
            return;
        }

        org.bukkit.block.Block source = event.getBlockClicked();
        if (source == null) {
            event.setCancelled(true);
            return;
        }
        if (!session.allowsBlockEdit()) {
            event.setCancelled(true);
            return;
        }
        if (!session.isInArenaRollbackArea(source.getLocation())) {
            event.setCancelled(true);
            return;
        }

        session.captureBlockBeforeBreak(source);
        session.unmarkPlacedBlock(source);
        event.setCancelled(false);
    }
}
