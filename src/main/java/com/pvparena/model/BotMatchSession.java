package com.pvparena.model;

import com.pvparena.hook.husksync.HuskSyncWriteBack;
import com.pvparena.manager.MatchManager;
import com.pvparena.model.BotDifficulty;
import com.pvparena.integration.CitizensBridge;
import com.pvparena.util.MessageUtil;
import com.pvparena.util.PlayerStateUtil;
import com.pvparena.util.SchedulerUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class BotMatchSession {
    private static final boolean BOT_DEBUG = true;
    private static final String BOT_TAG = "pvparena_bot";
    private final JavaPlugin plugin;
    private final MatchManager matchManager;
    private final UUID playerId;
    private final Mode mode;
    private final Arena arena;
    private final BotDifficulty difficulty;
    private final CitizensBridge citizensBridge;
    private CitizensBridge.CitizensNpc citizensNpc;
    private PlayerSnapshot snapshot;
    private LivingEntity bot;
    private volatile MatchState state = MatchState.PREPARING;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask countdownTask;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask aiTask;
    private final Random random = new Random();
    // Track bot combo cadence (unused after legacy removal)
    private int botCombo = 0;
    private long botLastHit = 0L;
    private long botLastAttack = 0L;
    // Track player combo cadence (unused after legacy removal)
    private int playerCombo = 0;
    private long playerLastHit = 0L;
    private ItemStack botPotionTemplate;
    private int botPotionCount = 0;
    private long botLastPotionUse = 0L;
    private double botMaxHealth = 20.0;
    private double botHealth = 20.0;
    private int botSpawnRetries = 0;
    private boolean botSpawnInProgress = false;
    private boolean fightPending = false;

    private void debug(String message) {
        if (BOT_DEBUG && plugin != null) {
            plugin.getLogger().warning("[BotDebug] " + message);
        }
    }

    public BotMatchSession(JavaPlugin plugin, MatchManager matchManager,
                           UUID playerId, Mode mode, Arena arena, BotDifficulty difficulty) {
        this.plugin = plugin;
        this.matchManager = matchManager;
        this.playerId = playerId;
        this.mode = mode;
        this.arena = arena;
        this.difficulty = difficulty != null ? difficulty : BotDifficulty.NORMAL;
        this.citizensBridge = new CitizensBridge(plugin);
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public Mode getMode() {
        return mode;
    }

    public Arena getArena() {
        return arena;
    }

    public BotDifficulty getDifficulty() {
        return difficulty;
    }

    public LivingEntity getBot() {
        return bot;
    }

    public MatchState getState() {
        return state;
    }

    public boolean isFighting() {
        return state == MatchState.FIGHTING;
    }

    public void start() {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            matchManager.endBotMatch(this, null, MatchManager.REASON_PLAYER_OFFLINE);
            return;
        }
        if (arena.getSpawn1() == null || arena.getSpawn2() == null) {
            matchManager.endBotMatch(this, null, MatchManager.REASON_ARENA_SPAWN_UNSET);
            return;
        }
        state = MatchState.PREPARING;
        Location spawn1 = arena.getSpawn1();
        Location spawn2 = arena.getSpawn2();
        CompletableFuture<Void> prep = prepareAndTeleport(player, spawn1);
        prep.whenComplete((v, ex) -> {
            if (ex != null) {
                matchManager.endBotMatch(this, null, MatchManager.REASON_TELEPORT_FAILED);
                return;
            }
            spawnBot(spawn2, player);
            startCountdown(player);
        });
    }

    private CompletableFuture<Void> prepareAndTeleport(Player player, Location spawn) {
        CompletableFuture<Void> prep = SchedulerUtil.runOnPlayerFuture(plugin, player, () -> {
            snapshot = new PlayerSnapshot(player);
            matchManager.addOrReplacePendingSnapshot(player.getUniqueId(), snapshot);
            if (player.isInsideVehicle()) {
                player.leaveVehicle();
            }
            if (player.getVehicle() != null) {
                player.getVehicle().eject();
            }
            if (!player.getPassengers().isEmpty()) {
                player.eject();
            }
            player.setItemOnCursor(null);
            PlayerStateUtil.reset(player);
        });
        return prep.thenCompose(v -> SchedulerUtil.teleport(plugin, player, spawn));
    }

    private void spawnBot(Location spawn, Player player) {
        if (spawn == null || spawn.getWorld() == null) {
            return;
        }
        if (bot != null && bot.isValid()) {
            debug("spawnBot skipped: existing bot=" + bot.getUniqueId());
            return;
        }
        if (botSpawnInProgress) {
            debug("spawnBot skipped: spawn in progress");
            return;
        }
        botSpawnInProgress = true;
        debug("spawnBot start: player=" + (player != null ? player.getName() : "null")
            + " citizens=" + citizensBridge.isAvailable());
        plugin.getServer().getRegionScheduler().run(plugin, spawn, task -> {
            LivingEntity entity = null;
            String botName = MessageUtil.getRaw("bot_name");
            if (citizensBridge.isAvailable()) {
                CitizensBridge.CitizensNpc npc = citizensBridge.spawnNpc(spawn, botName);
                if (npc != null) {
                    this.citizensNpc = npc;
                    entity = npc.getEntity();
                    debug("Citizens NPC spawned: " + (entity != null ? entity.getUniqueId() : "null"));
                }
            }
            if (entity == null) {
                Zombie zombie = spawn.getWorld().spawn(spawn, Zombie.class, mob -> {
                    mob.setCustomNameVisible(true);
                    mob.customName(Component.text(botName));
                    mob.setBaby(false);
                    mob.setPersistent(true);
                    mob.setRemoveWhenFarAway(false);
                    mob.setSilent(false);
                    mob.setAI(true);
                    mob.setAware(true);
                    mob.addScoreboardTag(BOT_TAG);
                });
                if (player != null) {
                    zombie.setTarget(player);
                }
                entity = zombie;
                debug("Zombie bot spawned: " + zombie.getUniqueId());
            }
            if (entity != null) {
                this.bot = entity;
                matchManager.registerBotEntity(this, entity);
                equipBot(entity);
                if (player != null && citizensNpc != null) {
                    citizensNpc.setSpeed(difficulty.getMoveSpeed());
                    citizensNpc.setTarget(player);
                }
                debug("Bot registered: " + entity.getUniqueId());
            }
            botSpawnInProgress = false;
        });
    }

    private void startCountdown(Player player) {
        state = MatchState.COUNTDOWN;
        AtomicInteger seconds = new AtomicInteger(getCountdownSeconds());
        MessageUtil.send(player, "match_vs",
            Placeholder.unparsed("opponent", MessageUtil.getRaw("bot_name")),
            Placeholder.unparsed("mode", mode.getDisplayName()));
        countdownTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!player.isOnline()) {
                task.cancel();
                matchManager.endBotMatch(this, null, MatchManager.REASON_PLAYER_OFFLINE);
                return;
            }
            int current = seconds.getAndDecrement();
            if (current <= 0) {
                task.cancel();
                startFight(player);
                return;
            }
            showCountdown(player, current);
        }, 1L, 20L);
    }

    private int getCountdownSeconds() {
        return Math.max(1, plugin.getConfig().getInt("match.countdown-seconds", 5));
    }

    private Duration getCountdownFadeIn() {
        return Duration.ofMillis(Math.max(0L, plugin.getConfig().getLong("match.countdown-title.fade-in-ms", 0L)));
    }

    private Duration getCountdownStay() {
        return Duration.ofMillis(Math.max(100L, plugin.getConfig().getLong("match.countdown-title.stay-ms", 800L)));
    }

    private Duration getCountdownFadeOut() {
        return Duration.ofMillis(Math.max(0L, plugin.getConfig().getLong("match.countdown-title.fade-out-ms", 200L)));
    }

    private void showCountdown(Player player, int seconds) {
        SchedulerUtil.runOnPlayer(plugin, player, () -> {
            String plainTitle = MessageUtil.getPlainMessage("countdown_title",
                Placeholder.unparsed("seconds", String.valueOf(seconds)));
            Component titleText;
            if (plainTitle == null || plainTitle.isBlank() || plainTitle.equals("countdown_title")) {
                titleText = Component.text(String.valueOf(seconds));
            } else {
                titleText = MessageUtil.message("countdown_title",
                    Placeholder.unparsed("seconds", String.valueOf(seconds)));
            }
            Title title = Title.title(
                titleText,
                MessageUtil.message("countdown_subtitle"),
                Title.Times.times(getCountdownFadeIn(), getCountdownStay(), getCountdownFadeOut()));
            player.showTitle(title);
            float pitch = seconds <= 1 ? 1.6f : 1.2f;
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, pitch);
            MessageUtil.send(player, "countdown_chat", Placeholder.unparsed("seconds", String.valueOf(seconds)));
        });
    }

    private void startFight(Player player) {
        state = MatchState.FIGHTING;
        if (bot == null || !bot.isValid()) {
            if (botSpawnInProgress) {
                if (!fightPending) {
                    fightPending = true;
                    debug("startFight waiting for bot spawn");
                    SchedulerUtil.runOnPlayerLater(plugin, player, 20L, () -> {
                        fightPending = false;
                        startFight(player);
                    });
                }
                return;
            }
            if (!fightPending) {
                fightPending = true;
                debug("startFight triggering bot spawn");
                spawnBot(arena.getSpawn2(), player);
                SchedulerUtil.runOnPlayerLater(plugin, player, 20L, () -> {
                    fightPending = false;
                    startFight(player);
                });
                return;
            }
            debug("startFight failed: bot missing");
            matchManager.endBotMatch(this, null, MatchManager.REASON_BOT_SPAWN_FAILED);
            return;
        }
        debug("startFight: bot=" + bot.getUniqueId());
        equipPlayer(player);
        if (bot != null && bot.isValid()) {
            equipBot(bot);
            if (bot instanceof Mob mob) {
                mob.getScheduler().run(plugin, task -> mob.setTarget(player), null);
            }
            if (citizensNpc != null) {
                citizensNpc.setSpeed(difficulty.getMoveSpeed());
                citizensNpc.setTarget(player);
            }
        }
        MessageUtil.send(player, "match_start");
        matchManager.onArenaFightStarted(arena);
        startBotAI();
    }

    private void equipPlayer(Player player) {
        SchedulerUtil.runOnPlayer(plugin, player, () -> {
            PlayerStateUtil.reset(player);
            mode.getKit().getItems().forEach(item -> player.getInventory().addItem(item.clone()));
            List<ItemStack> armor = mode.getKit().getArmor();
            if (!armor.isEmpty()) {
                ItemStack[] armorContents = new ItemStack[4];
                for (int i = 0; i < Math.min(4, armor.size()); i++) {
                    armorContents[i] = armor.get(i).clone();
                }
                player.getInventory().setArmorContents(armorContents);
            }
            for (PotionEffect effect : mode.getKit().getPotionEffects()) {
                player.addPotionEffect(effect);
            }
            if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(mode.getSettings().getMaxHealth());
            }
            player.setHealth(mode.getSettings().getMaxHealth());
            player.setFoodLevel(mode.getSettings().getHunger());
            player.setSaturation(mode.getSettings().getSaturation());
            player.setNoDamageTicks(mode.getSettings().getNoDamageTicks());
        });
    }

    private void equipBot(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        entity.getScheduler().run(plugin, task -> {
            if (entity.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(mode.getSettings().getMaxHealth());
            }
            entity.setHealth(mode.getSettings().getMaxHealth());
            entity.setInvulnerable(true);
            botMaxHealth = mode.getSettings().getMaxHealth();
            botHealth = botMaxHealth;
            for (PotionEffect effect : mode.getKit().getPotionEffects()) {
                entity.addPotionEffect(effect);
            }
            List<ItemStack> armor = mode.getKit().getArmor();
            ItemStack weapon = findWeapon(mode.getKit().getItems());
            if (entity instanceof Player npcPlayer) {
                npcPlayer.getInventory().clear();
                if (weapon != null) {
                    npcPlayer.getInventory().setItemInMainHand(weapon.clone());
                }
                applyArmorToPlayer(npcPlayer, armor);
                npcPlayer.updateInventory();
                if (citizensNpc != null) {
                    citizensNpc.applyEquipment(weapon, armor);
                }
            } else {
                EntityEquipment equipment = entity.getEquipment();
                if (equipment != null) {
                    if (weapon != null) {
                        equipment.setItemInMainHand(weapon.clone());
                    }
                    if (!applyArmorByType(equipment, armor)) {
                        if (armor.size() > 0) equipment.setHelmet(armor.get(0).clone());
                        if (armor.size() > 1) equipment.setChestplate(armor.get(1).clone());
                        if (armor.size() > 2) equipment.setLeggings(armor.get(2).clone());
                        if (armor.size() > 3) equipment.setBoots(armor.get(3).clone());
                    }
                }
            }
            initPotionStock();
        }, null);
    }

    public void handleBotDamaged(double damage) {
        if (damage <= 0) {
            return;
        }
        botHealth = Math.max(0.0, botHealth - damage);
        if (botHealth <= 0.0) {
            matchManager.endBotMatch(this, playerId, MatchManager.REASON_BOT_DOWN);
        }
    }

    private void applyArmorToPlayer(Player player, List<ItemStack> armor) {
        if (player == null || armor == null || armor.isEmpty()) {
            return;
        }
        ItemStack[] armorContents = new ItemStack[4];
        for (ItemStack item : armor) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            String type = item.getType().name();
            if (type.endsWith("_HELMET")) {
                armorContents[3] = item.clone();
            } else if (type.endsWith("_CHESTPLATE")) {
                armorContents[2] = item.clone();
            } else if (type.endsWith("_LEGGINGS")) {
                armorContents[1] = item.clone();
            } else if (type.endsWith("_BOOTS")) {
                armorContents[0] = item.clone();
            }
        }
        player.getInventory().setArmorContents(armorContents);
    }

    private void initPotionStock() {
        botPotionTemplate = null;
        botPotionCount = 0;
        List<ItemStack> items = mode.getKit().getItems();
        if (items == null) {
            return;
        }
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (!item.getType().name().equalsIgnoreCase("SPLASH_POTION")) {
                continue;
            }
            PotionMeta meta = item.getItemMeta() instanceof PotionMeta pMeta ? pMeta : null;
            PotionType type = meta != null ? meta.getBasePotionType() : null;
            if (type == null) {
                continue;
            }
                if (type == PotionType.HEALING || type == PotionType.REGENERATION) {
                botPotionCount += Math.max(1, item.getAmount());
                if (botPotionTemplate == null) {
                    ItemStack clone = item.clone();
                    clone.setAmount(1);
                    botPotionTemplate = clone;
                }
            }
        }
    }

    private boolean applyArmorByType(EntityEquipment equipment, List<ItemStack> armor) {
        if (equipment == null || armor == null || armor.isEmpty()) {
            return false;
        }
        boolean applied = false;
        for (ItemStack item : armor) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            String type = item.getType().name();
            if (type.endsWith("_HELMET")) {
                equipment.setHelmet(item.clone());
                applied = true;
            } else if (type.endsWith("_CHESTPLATE")) {
                equipment.setChestplate(item.clone());
                applied = true;
            } else if (type.endsWith("_LEGGINGS")) {
                equipment.setLeggings(item.clone());
                applied = true;
            } else if (type.endsWith("_BOOTS")) {
                equipment.setBoots(item.clone());
                applied = true;
            }
        }
        return applied;
    }

    private ItemStack findWeapon(List<ItemStack> items) {
        if (items == null) {
            return null;
        }
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            return item;
        }
        return null;
    }

    private void startBotAI() {
        if (aiTask != null) {
            aiTask.cancel();
        }
        aiTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tickBot(), 1L, 2L);
    }

    private void tickBot() {
        Player player = plugin.getServer().getPlayer(playerId);
        LivingEntity entity = bot;
        if (player == null || !player.isOnline()) {
            matchManager.endBotMatch(this, null, MatchManager.REASON_PLAYER_OFFLINE);
            return;
        }
        if (entity == null || entity.isDead() || !entity.isValid()) {
            return;
        }
        if (!isFighting()) {
            return;
        }
        CompletableFuture<Location> locFuture = new CompletableFuture<>();
        player.getScheduler().run(plugin, task -> locFuture.complete(player.getLocation()), null);
        locFuture.thenAccept(playerLoc -> {
            LivingEntity currentBot = bot;
            if (currentBot == null || !currentBot.isValid()) {
                return;
            }
            currentBot.getScheduler().run(plugin, task -> {
                if (!isFighting()) {
                    return;
                }
                if (playerLoc.getWorld() == null || currentBot.getWorld() == null
                        || !playerLoc.getWorld().equals(currentBot.getWorld())) {
                    return;
                }
                Location botLoc = currentBot.getLocation();
                double distance = botLoc.distance(playerLoc);
                Vector toPlayer = playerLoc.toVector().subtract(botLoc.toVector());
                toPlayer.setY(0);
                if (toPlayer.lengthSquared() > 0) {
                    toPlayer.normalize();
                }

                if (toPlayer.lengthSquared() > 0) {
                    Location look = botLoc.clone();
                    look.setDirection(toPlayer);
                    currentBot.teleportAsync(look);
                }

                double baseSpeed = distance > 3.2 ? difficulty.getMoveSpeed() : difficulty.getMoveSpeed() * 0.75;
                Vector move = toPlayer.clone().multiply(baseSpeed);
                if (distance <= 3.2) {
                    Vector side = new Vector(-toPlayer.getZ(), 0, toPlayer.getX());
                    if (side.lengthSquared() > 0) {
                        side.normalize();
                    }
                    double sideSpeed = random.nextBoolean() ? difficulty.getStrafeSpeed() : -difficulty.getStrafeSpeed();
                    move.add(side.multiply(sideSpeed));
                }
                if (distance > 6.0) {
                    move.multiply(1.15);
                }
                move.setY(currentBot.getVelocity().getY());
                currentBot.setVelocity(move);

                if (distance < 4.0 && currentBot.isOnGround() && random.nextDouble() < difficulty.getJumpChance()) {
                    Vector jump = currentBot.getVelocity();
                    jump.setY(0.42);
                    currentBot.setVelocity(jump);
                }

                if (citizensNpc != null) {
                    citizensNpc.setSpeed(difficulty.getMoveSpeed());
                    citizensNpc.setTarget(player);
                }

                long now = System.currentTimeMillis();
                if (distance <= 3.1 && now - botLastAttack >= difficulty.getAttackCooldownMs()) {
                    botLastAttack = now;
                    currentBot.swingMainHand();
                    SchedulerUtil.runOnPlayer(plugin, player, () -> {
                        player.damage(difficulty.getBaseDamage(), currentBot);
                        applyBotHitKnockback(player, currentBot);
                    });
                }
                tryUsePotion(currentBot);
            }, null);
        });
    }

    private void tryUsePotion(LivingEntity currentBot) {
        if (botPotionTemplate == null || botPotionCount <= 0) {
            return;
        }
        double maxHealth = currentBot.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null
            ? currentBot.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue()
            : currentBot.getMaxHealth();
        if (maxHealth <= 0) {
            return;
        }
        double ratio = currentBot.getHealth() / maxHealth;
        long now = System.currentTimeMillis();
        if (ratio > 0.6) {
            return;
        }
        if (now - botLastPotionUse < 4500L) {
            return;
        }
        Location loc = currentBot.getLocation().clone();
        ThrownPotion potion = currentBot.getWorld().spawn(loc, ThrownPotion.class, thrown -> {
            thrown.setItem(botPotionTemplate.clone());
            thrown.setShooter(currentBot);
        });
        if (potion != null) {
            botPotionCount -= 1;
            botLastPotionUse = now;
        }
    }

    public void applyPlayerHitKnockback(Player attacker, LivingEntity victim) {
        if (attacker == null || victim == null) {
            return;
        }
        victim.getScheduler().run(plugin, task -> {
            Vector direction = victim.getLocation().toVector().subtract(attacker.getLocation().toVector());
            direction.setY(0);
            if (direction.lengthSquared() == 0) {
                return;
            }
            direction.normalize();
            double sprintBoost = attacker.isSprinting() ? 0.2 : 0.0;
            Vector knockback = direction.multiply(0.4 + sprintBoost);
            knockback.setY(0.35);
            victim.setVelocity(knockback);
            victim.setNoDamageTicks(10);
        }, null);
    }

    private void applyBotHitKnockback(Player victim, LivingEntity attacker) {
        if (victim == null || attacker == null) {
            return;
        }
        SchedulerUtil.runOnPlayer(plugin, victim, () -> {
            Vector direction = victim.getLocation().toVector().subtract(attacker.getLocation().toVector());
            direction.setY(0);
            if (direction.lengthSquared() == 0) {
                return;
            }
            direction.normalize();
            Vector knockback = direction.multiply(0.35);
            knockback.setY(0.32);
            victim.setVelocity(knockback);
        });
    }

    public void end(UUID winner, String reason) {
        state = MatchState.ENDING;
        if (countdownTask != null) {
            countdownTask.cancel();
        }
        if (aiTask != null) {
            aiTask.cancel();
        }
        LivingEntity entity = bot;
        bot = null;
        CitizensBridge.CitizensNpc npc = citizensNpc;
        citizensNpc = null;
        if (npc != null) {
            LivingEntity npcEntity = npc.getEntity();
            if (npcEntity != null && npcEntity.isValid()) {
                npcEntity.getScheduler().run(plugin, task -> npc.destroy(), null);
            } else {
                npc.destroy();
            }
        } else if (entity != null && entity.isValid()) {
            entity.getScheduler().run(plugin, task -> entity.remove(), null);
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) {
            handleEndForPlayer(player, winner, reason);
        }
    }

    private void handleEndForPlayer(Player player, UUID winner, String reason) {
        SchedulerUtil.runOnPlayer(plugin, player, () -> {
            if (player.isDead()) {
                return;
            }
            if (winner != null && player.getUniqueId().equals(winner)) {
                Title title = Title.title(MessageUtil.message("win_title"),
                    MessageUtil.message("win_subtitle"));
                player.showTitle(title);
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            } else if (winner != null) {
                Title title = Title.title(MessageUtil.message("lose_title"),
                    MessageUtil.message("lose_subtitle"));
                player.showTitle(title);
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.9f);
            } else {
                Title title = Title.title(MessageUtil.message("end_title"), Component.text(reason));
                player.showTitle(title);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
            if (snapshot != null) {
                snapshot.restore(player);
                    HuskSyncWriteBack.writeBackCurrentState(plugin, player);
                Location back = snapshot.getLocation();
                if (back != null && back.getWorld() != null) {
                    player.teleportAsync(back);
                } else {
                    player.teleportAsync(player.getWorld().getSpawnLocation());
                }
            } else {
                PlayerStateUtil.reset(player);
                player.teleportAsync(player.getWorld().getSpawnLocation());
            }
        });
    }
}
