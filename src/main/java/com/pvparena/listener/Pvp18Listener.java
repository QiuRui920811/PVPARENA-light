package com.pvparena.listener;

import com.pvparena.manager.MatchManager;
import com.pvparena.manager.Pvp18Manager;
import com.pvparena.model.MatchSession;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.TippedArrow;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.entity.FishHook;
import org.bukkit.Material;

public class Pvp18Listener implements Listener {
    private final MatchManager matchManager;
    private final Pvp18Manager pvp18Manager;
    private final java.util.Map<java.util.UUID, Boolean> lastOnGround = new java.util.HashMap<>();

    public Pvp18Listener(MatchManager matchManager, Pvp18Manager pvp18Manager) {
        this.matchManager = matchManager;
        this.pvp18Manager = pvp18Manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!matchManager.isInMatch(player) && pvp18Manager.isTagged(player)) {
            pvp18Manager.disable18Pvp(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (pvp18Manager.isTagged(player)) {
            pvp18Manager.disable18Pvp(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSweep(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        Player attacker = null;
        Entity damager = event.getDamager();
        if (damager instanceof Player) {
            attacker = (Player) damager;
        } else if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Player) {
            attacker = (Player) ((Projectile) damager).getShooter();
        }
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player victim = (Player) event.getEntity();
        if (attacker == null) {
            return;
        }
        if (pvp18Manager.isTagged(attacker) && pvp18Manager.isTagged(victim)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (pvp18Manager.isTagged(player)) {
            pvp18Manager.disable18Pvp(player);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        MatchSession session = matchManager.getMatch(player);
        if (session != null && session.getMode().getSettings().isLegacyPvp()) {
            pvp18Manager.enable18Pvp(player);
        } else if (pvp18Manager.isTagged(player)) {
            pvp18Manager.disable18Pvp(player);
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY) {
            return;
        }
        if (!(event.getCaught() instanceof Player)) {
            return;
        }
        Player victim = (Player) event.getCaught();
        Player attacker = event.getPlayer();
        MatchSession sessionA = matchManager.getMatch(attacker);
        MatchSession sessionB = matchManager.getMatch(victim);
        if (sessionA == null || sessionB == null || sessionA != sessionB) {
            return;
        }
        if (!sessionA.getMode().getSettings().isLegacyPvp()) {
            return;
        }
        if (!pvp18Manager.isTagged(attacker) || !pvp18Manager.isTagged(victim)) {
            return;
        }
        pvp18Manager.handleFishingPull(attacker, victim, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player shooter = (Player) event.getEntity();
        MatchSession session = matchManager.getMatch(shooter);
        if (session == null || !session.getMode().getSettings().isLegacyPvp()) {
            return;
        }
        if (!pvp18Manager.isTagged(shooter)) {
            return;
        }
        if (event.getProjectile() instanceof Projectile) {
            Projectile projectile = (Projectile) event.getProjectile();
            projectile.setVelocity(projectile.getVelocity().multiply(pvp18Manager.getSettings().getBowVelocityMultiplier()));
        }
        if (event.getProjectile() instanceof AbstractArrow) {
            AbstractArrow arrow = (AbstractArrow) event.getProjectile();
            if (pvp18Manager.getSettings().isBowDisablePunch()) {
                arrow.setKnockbackStrength(0);
            }
            if (pvp18Manager.getSettings().isBowStripTipped() && arrow instanceof TippedArrow) {
                TippedArrow tipped = (TippedArrow) arrow;
                tipped.setBasePotionData(new org.bukkit.potion.PotionData(org.bukkit.potion.PotionType.WATER, false, false));
                tipped.clearCustomEffects();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        MatchSession session = matchManager.getMatch(player);
        if (session == null || !session.getMode().getSettings().isLegacyPvp()) {
            return;
        }
        if (!pvp18Manager.isTagged(player)) {
            return;
        }
        if (event.getItem() == null || event.getItem().getType() == null) {
            return;
        }
        Material type = event.getItem().getType();
        if (type == Material.GOLDEN_APPLE) {
            pvp18Manager.applyGoldenApple(player, false);
        } else if (type == Material.ENCHANTED_GOLDEN_APPLE) {
            pvp18Manager.applyGoldenApple(player, true);
        }
    }

    @EventHandler
    public void onFishHookHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof FishHook)) {
            return;
        }
        FishHook hook = (FishHook) event.getEntity();
        if (!(hook.getShooter() instanceof Player)) {
            return;
        }
        Player attacker = (Player) hook.getShooter();
        if (!(event.getHitEntity() instanceof Player)) {
            return;
        }
        Player victim = (Player) event.getHitEntity();
        MatchSession sessionA = matchManager.getMatch(attacker);
        MatchSession sessionB = matchManager.getMatch(victim);
        if (sessionA == null || sessionB == null || sessionA != sessionB) {
            return;
        }
        if (!sessionA.getMode().getSettings().isLegacyPvp()) {
            return;
        }
        if (!pvp18Manager.isTagged(attacker) || !pvp18Manager.isTagged(victim)) {
            return;
        }
        pvp18Manager.handleFishingPull(attacker, victim, true);
        hook.remove();
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        MatchSession session = matchManager.getMatch(player);
        if (session == null || !session.getMode().getSettings().isLegacyPvp()) {
            return;
        }
        if (pvp18Manager.isOffhandDisabled()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onOffhandClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        MatchSession session = matchManager.getMatch(player);
        if (session == null || !session.getMode().getSettings().isLegacyPvp()) {
            return;
        }
        if (!pvp18Manager.isOffhandDisabled()) {
            return;
        }
        if (event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND) {
            event.setCancelled(true);
            return;
        }
        if (event.getSlot() == 40 && event.getClickedInventory() != null
                && event.getClickedInventory().getType() == InventoryType.PLAYER) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onOffhandDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        MatchSession session = matchManager.getMatch(player);
        if (session == null || !session.getMode().getSettings().isLegacyPvp()) {
            return;
        }
        if (!pvp18Manager.isOffhandDisabled()) {
            return;
        }
        if (event.getRawSlots().contains(40)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        MatchSession session = matchManager.getMatch(player);
        if (session == null || !session.getMode().getSettings().isLegacyPvp()) {
            return;
        }
        if (event.getTo() == null) {
            return;
        }
        boolean onGround = player.isOnGround();
        Boolean last = lastOnGround.put(player.getUniqueId(), onGround);
        if (last != null && !last && onGround) {
            pvp18Manager.resetCombo(player);
        }
        if (event.getTo().getY() > event.getFrom().getY()) {
            pvp18Manager.recordJump(player);
        }
    }
}