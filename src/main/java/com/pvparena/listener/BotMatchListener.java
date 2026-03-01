package com.pvparena.listener;

import com.pvparena.manager.MatchManager;
import com.pvparena.manager.Pvp18Manager;
import com.pvparena.model.BotMatchSession;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.EntityEffect;

public class BotMatchListener implements Listener {
    private final MatchManager matchManager;
    private final Pvp18Manager pvp18Manager;

    public BotMatchListener(MatchManager matchManager, Pvp18Manager pvp18Manager) {
        this.matchManager = matchManager;
        this.pvp18Manager = pvp18Manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity victimEntity = event.getEntity();
        Entity damagerEntity = event.getDamager();
        BotMatchSession session = null;
        LivingEntity bot = null;
        if (victimEntity instanceof LivingEntity livingVictim) {
            session = matchManager.getBotMatchByEntity(livingVictim);
            bot = livingVictim;
        }
        if (session == null && damagerEntity instanceof LivingEntity livingDamager) {
            session = matchManager.getBotMatchByEntity(livingDamager);
            bot = livingDamager;
        }
        if (session == null) {
            return;
        }
        if (!session.isFighting()) {
            event.setCancelled(true);
            return;
        }
        if (damagerEntity instanceof Player) {
            Player attacker = (Player) damagerEntity;
            if (bot != null && bot.getUniqueId().equals(victimEntity.getUniqueId())) {
                event.setCancelled(true);
                pvp18Manager.applyNoCritical(attacker, event);
                session.applyPlayerHitKnockback(attacker, bot);
                session.handleBotDamaged(event.getFinalDamage());
                bot.playEffect(EntityEffect.HURT);
                bot.playHurtAnimation(0.6f);
                attacker.playSound(attacker.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_HURT, 0.6f, 1.4f);
                return;
            }
        }
        if (damagerEntity == bot && victimEntity instanceof Player) {
            return;
        }
        if (!(damagerEntity instanceof Player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBotDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        LivingEntity entity = (LivingEntity) event.getEntity();
        BotMatchSession session = matchManager.getBotMatchByEntity(entity);
        if (session == null) {
            return;
        }
        event.getDrops().clear();
        matchManager.endBotMatch(session, session.getPlayerId(), com.pvparena.manager.MatchManager.REASON_BOT_DOWN);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBotDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        LivingEntity entity = (LivingEntity) event.getEntity();
        BotMatchSession session = matchManager.getBotMatchByEntity(entity);
        if (session == null) {
            return;
        }
        if (!(event instanceof EntityDamageByEntityEvent)) {
            event.setCancelled(true);
        }
    }
}
