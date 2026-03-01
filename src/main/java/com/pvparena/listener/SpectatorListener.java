package com.pvparena.listener;

import com.pvparena.manager.SpectatorManager;
import com.pvparena.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Locale;

public class SpectatorListener implements Listener {
    private final SpectatorManager spectatorManager;

    public SpectatorListener(SpectatorManager spectatorManager) {
        this.spectatorManager = spectatorManager;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        spectatorManager.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        spectatorManager.cleanupLingeringCmiVanish(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!spectatorManager.isSpectating(event.getPlayer())) {
            return;
        }
        if (event.getHand() == EquipmentSlot.HAND) {
            String action = spectatorManager.readToolAction(event.getItem());
            if (action != null && spectatorManager.handleToolAction(event.getPlayer(), action)) {
                event.setCancelled(true);
                return;
            }
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (spectatorManager.isSpectating(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (spectatorManager.isSpectating(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (spectatorManager.isSpectating(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (spectatorManager.isSpectating(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (spectatorManager.isSpectating(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && spectatorManager.isSpectating(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && spectatorManager.isSpectating(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player damager && spectatorManager.isSpectating(damager)) {
            event.setCancelled(true);
            return;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter
                && spectatorManager.isSpectating(shooter)) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof Player victim && spectatorManager.isSpectating(victim)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getHitEntity() instanceof Player player && spectatorManager.isSpectating(player)) {
            event.getEntity().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!spectatorManager.isSpectating(event.getPlayer())) {
            return;
        }
        if (isDuelLeaveCommand(event.getMessage())) {
            event.setCancelled(false);
            return;
        }
        event.setCancelled(true);
        MessageUtil.send(event.getPlayer(), "spectator_command_blocked");
    }

    private boolean isDuelLeaveCommand(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isBlank()) {
            return false;
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 2) {
            return false;
        }
        String root = parts[0].toLowerCase(Locale.ROOT);
        int namespaced = root.lastIndexOf(':');
        if (namespaced >= 0 && namespaced + 1 < root.length()) {
            root = root.substring(namespaced + 1);
        }
        String sub = parts[1].toLowerCase(Locale.ROOT);
        return "duel".equals(root) && "leave".equals(sub);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && spectatorManager.isSpectating(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && spectatorManager.isSpectating(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (spectatorManager.isSpectating(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
