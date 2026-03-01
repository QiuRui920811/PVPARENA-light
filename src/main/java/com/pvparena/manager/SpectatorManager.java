package com.pvparena.manager;

import com.pvparena.config.PluginSettings;
import com.pvparena.model.MatchSession;
import com.pvparena.model.PlayerSnapshot;
import com.pvparena.util.GuiTextUtil;
import com.pvparena.util.MessageUtil;
import com.pvparena.util.PlayerStateUtil;
import com.pvparena.util.SchedulerUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class SpectatorManager {
    private final JavaPlugin plugin;
    private final MatchManager matchManager;
    private final PluginSettings settings;
    private final ConcurrentHashMap<UUID, SpectatorSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>> spectatorsByTarget = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, io.papermc.paper.threadedregions.scheduler.ScheduledTask> hideEnforcementTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> cmiVanishApplied = new ConcurrentHashMap<>();
    private final NamespacedKey spectatorToolKey;
    private Consumer<Player> browserOpener = player -> {};
    private static final String SPECTATOR_HIDDEN_TEAM = "pvpa_spec_hide";

    private static final String TOOL_LEAVE = "leave";
    private static final String TOOL_NEXT = "next";
    private static final String TOOL_BROWSER = "browser";

    public SpectatorManager(JavaPlugin plugin, MatchManager matchManager, PluginSettings settings) {
        this.plugin = plugin;
        this.matchManager = matchManager;
        this.settings = settings;
        this.spectatorToolKey = new NamespacedKey(plugin, "spectator_tool_action");
    }

    public void setBrowserOpener(Consumer<Player> browserOpener) {
        this.browserOpener = browserOpener != null ? browserOpener : player -> {};
    }

    public boolean isSpectating(Player player) {
        return player != null && sessions.containsKey(player.getUniqueId());
    }

    public boolean enterSpectator(Player spectator, Player target) {
        return enterSpectatorInternal(spectator, target, false, true);
    }

    public boolean enterEliminatedSpectator(Player spectator, Player target) {
        return enterSpectatorInternal(spectator, target, true, false);
    }

    private boolean enterSpectatorInternal(Player spectator, Player target, boolean allowInMatchSpectator, boolean giveTools) {
        if (spectator == null || target == null) {
            return false;
        }
        if (!settings.isSpectatorEnabled()) {
            MessageUtil.send(spectator, "spectator_disabled");
            return false;
        }
        if (matchManager.getMatch(target) == null) {
            MessageUtil.send(spectator, "spectator_target_not_in_match");
            return false;
        }
        if (!allowInMatchSpectator && matchManager.isInMatch(spectator)) {
            MessageUtil.send(spectator, "spectator_in_match_denied");
            return false;
        }
        if (spectator.isDead()) {
            return false;
        }

        SpectatorSession existing = sessions.get(spectator.getUniqueId());
        if (existing != null) {
            switchTarget(existing, spectator, target, giveTools);
            return true;
        }

        SpectatorSession session = new SpectatorSession(new PlayerSnapshot(spectator), target.getUniqueId(),
                spectator.isCollidable(), spectator.isInvulnerable(), spectator.getCanPickupItems(), spectator.isSilent(), giveTools);
        sessions.put(spectator.getUniqueId(), session);
        spectatorsByTarget.computeIfAbsent(target.getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(spectator.getUniqueId());

        SchedulerUtil.runOnPlayer(plugin, spectator, () -> {
            PlayerStateUtil.reset(spectator);
            boolean strictIsolation = settings.isSpectatorIgnoreRealEvents();
            spectator.setGameMode(GameMode.ADVENTURE);
            spectator.setAllowFlight(true);
            spectator.setFlying(true);
            spectator.setFlySpeed(0.08f);
            spectator.setInvisible(settings.isSpectatorVanish());
            spectator.setCollidable(strictIsolation ? false : settings.isSpectatorCollidable());
            spectator.setInvulnerable(strictIsolation || !settings.isSpectatorCanBeTargeted());
            spectator.setCanPickupItems(false);
            spectator.setSilent(true);
            if (giveTools) {
                giveSpectatorTools(spectator);
            } else {
                spectator.getInventory().clear();
            }
            if (settings.isSpectatorVanish() && settings.isSpectatorHideFromPlayers()) {
                applyCmiVanish(spectator);
                hideFromOthers(spectator);
                applyHiddenNametagTeam(spectator);
                startHideEnforcement(spectator);
            }
            spectator.teleportAsync(target.getLocation());
            MessageUtil.send(spectator, "spectator_enter", Placeholder.unparsed("target", target.getName()));
        });
        return true;
    }

    public boolean switchToNextTarget(Player spectator) {
        if (spectator == null) {
            return false;
        }
        SpectatorSession session = sessions.get(spectator.getUniqueId());
        if (session == null) {
            return false;
        }

        List<Player> targets = getOnlineSpectatableTargets(spectator.getUniqueId());
        if (targets.isEmpty()) {
            MessageUtil.send(spectator, "spectator_no_matches");
            return false;
        }

        int currentIndex = -1;
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).getUniqueId().equals(session.targetId())) {
                currentIndex = i;
                break;
            }
        }
        int next = (currentIndex + 1 + targets.size()) % targets.size();
        return enterSpectator(spectator, targets.get(next));
    }

    public boolean handleToolAction(Player spectator, String action) {
        if (!isSpectating(spectator) || action == null || action.isBlank()) {
            return false;
        }
        return switch (action) {
            case TOOL_LEAVE -> leaveSpectator(spectator);
            case TOOL_NEXT -> switchToNextTarget(spectator);
            case TOOL_BROWSER -> {
                browserOpener.accept(spectator);
                yield true;
            }
            default -> false;
        };
    }

    public String readToolAction(ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return null;
        }
        return itemStack.getItemMeta().getPersistentDataContainer().get(spectatorToolKey, PersistentDataType.STRING);
    }

    public boolean leaveSpectator(Player spectator) {
        if (spectator == null) {
            return false;
        }
        SpectatorSession session = sessions.remove(spectator.getUniqueId());
        if (session == null) {
            return false;
        }

        Set<UUID> watchers = spectatorsByTarget.get(session.targetId());
        if (watchers != null) {
            watchers.remove(spectator.getUniqueId());
            if (watchers.isEmpty()) {
                spectatorsByTarget.remove(session.targetId());
            }
        }

        SchedulerUtil.runOnPlayer(plugin, spectator, () -> {
            if (settings.isSpectatorVanish() && settings.isSpectatorHideFromPlayers()) {
                stopHideEnforcement(spectator.getUniqueId());
                removeHiddenNametagTeam(spectator);
                showToOthers(spectator);
                clearCmiVanish(spectator);
            }
            spectator.setInvisible(false);
            if (session.toolsEnabled()) {
                session.snapshot().restore(spectator);
                spectator.setCollidable(session.collidable());
                spectator.setInvulnerable(session.invulnerable());
                spectator.setCanPickupItems(session.canPickupItems());
                spectator.setSilent(session.silent());
                Location restore = session.snapshot().getLocation();
                if (restore != null && restore.getWorld() != null) {
                    spectator.teleportAsync(restore);
                } else {
                    World fallbackWorld = plugin.getServer().getWorld("world");
                    if (fallbackWorld == null && !plugin.getServer().getWorlds().isEmpty()) {
                        fallbackWorld = plugin.getServer().getWorlds().get(0);
                    }
                    if (fallbackWorld != null) {
                        spectator.teleportAsync(fallbackWorld.getSpawnLocation());
                    }
                }
            } else {
                // Eliminated-round temporary spectator: do not restore stale snapshot/location,
                // let match round preparation restore normal combat state.
                spectator.setCollidable(true);
                spectator.setInvulnerable(false);
                spectator.setCanPickupItems(true);
                spectator.setSilent(false);
            }
            MessageUtil.send(spectator, "spectator_leave");
        });
        return true;
    }

    public void handleMatchEnded(MatchSession session) {
        if (session == null) {
            return;
        }
        leaveAllTargetSpectators(session.getPlayerA());
        leaveAllTargetSpectators(session.getPlayerB());
    }

    public void handleQuit(Player player) {
        leaveSpectator(player);
    }

    public void restoreAllOnline() {
        for (UUID spectatorId : hideEnforcementTasks.keySet()) {
            stopHideEnforcement(spectatorId);
        }
        for (UUID spectatorId : cmiVanishApplied.keySet()) {
            Player player = plugin.getServer().getPlayer(spectatorId);
            if (player != null && player.isOnline()) {
                clearCmiVanish(player);
            }
        }
        for (UUID spectatorId : sessions.keySet()) {
            Player player = plugin.getServer().getPlayer(spectatorId);
            if (player != null && player.isOnline()) {
                leaveSpectator(player);
            }
        }
    }

    private void leaveAllTargetSpectators(UUID targetId) {
        Set<UUID> watchers = spectatorsByTarget.remove(targetId);
        if (watchers == null || watchers.isEmpty()) {
            return;
        }
        for (UUID watcherId : watchers) {
            Player watcher = plugin.getServer().getPlayer(watcherId);
            if (watcher != null && watcher.isOnline()) {
                leaveSpectator(watcher);
            } else {
                sessions.remove(watcherId);
            }
        }
    }

    private void switchTarget(SpectatorSession existing, Player spectator, Player target, boolean giveTools) {
        UUID oldTarget = existing.targetId();
        if (!oldTarget.equals(target.getUniqueId())) {
            Set<UUID> watchers = spectatorsByTarget.get(oldTarget);
            if (watchers != null) {
                watchers.remove(spectator.getUniqueId());
                if (watchers.isEmpty()) {
                    spectatorsByTarget.remove(oldTarget);
                }
            }
            spectatorsByTarget.computeIfAbsent(target.getUniqueId(), k -> ConcurrentHashMap.newKeySet())
                    .add(spectator.getUniqueId());
            sessions.put(spectator.getUniqueId(), existing.withTarget(target.getUniqueId(), giveTools));
        }
        SchedulerUtil.runOnPlayer(plugin, spectator, () -> {
            spectator.teleportAsync(target.getLocation());
            if (giveTools) {
                giveSpectatorTools(spectator);
            } else {
                spectator.getInventory().clear();
            }
            MessageUtil.send(spectator, "spectator_enter", Placeholder.unparsed("target", target.getName()));
        });
    }

    private List<Player> getOnlineSpectatableTargets(UUID spectatorId) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (MatchSession session : matchManager.getActiveMatchSessions()) {
            ids.add(session.getPlayerA());
            ids.add(session.getPlayerB());
        }
        if (spectatorId != null) {
            ids.remove(spectatorId);
        }
        List<Player> players = new ArrayList<>();
        for (UUID id : ids) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    private void giveSpectatorTools(Player player) {
        if (player == null) {
            return;
        }
        player.getInventory().clear();
        player.getInventory().setItem(0, createTool(Material.COMPASS, "spectator_toolbar_browser", TOOL_BROWSER));
        player.getInventory().setItem(4, createTool(Material.ENDER_PEARL, "spectator_toolbar_next", TOOL_NEXT));
        player.getInventory().setItem(8, createTool(Material.RED_BED, "spectator_toolbar_leave", TOOL_LEAVE));
        player.getInventory().setHeldItemSlot(0);
        try {
            player.updateInventory();
        } catch (Throwable ignored) {
        }
    }

    private ItemStack createTool(Material material, String messageKey, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(GuiTextUtil.noItalic(MessageUtil.message(messageKey)));
        meta.getPersistentDataContainer().set(spectatorToolKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private void hideFromOthers(Player spectator) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!online.getUniqueId().equals(spectator.getUniqueId())) {
                online.hidePlayer(plugin, spectator);
            }
        }
    }

    private void showToOthers(Player spectator) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!online.getUniqueId().equals(spectator.getUniqueId())) {
                online.showPlayer(plugin, spectator);
            }
        }
    }

    private void startHideEnforcement(Player spectator) {
        stopHideEnforcement(spectator.getUniqueId());
        io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
                    if (!spectator.isOnline() || !isSpectating(spectator)) {
                        t.cancel();
                        hideEnforcementTasks.remove(spectator.getUniqueId());
                        return;
                    }
                    hideFromOthers(spectator);
                    applyHiddenNametagTeam(spectator);
                }, 1L, 20L);
        hideEnforcementTasks.put(spectator.getUniqueId(), task);
    }

    private void stopHideEnforcement(UUID spectatorId) {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask task = hideEnforcementTasks.remove(spectatorId);
        if (task != null) {
            task.cancel();
        }
    }

    private void applyHiddenNametagTeam(Player spectator) {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            Scoreboard scoreboard = viewer.getScoreboard();
            Team team = getOrCreateHiddenTeam(scoreboard);
            if (team != null) {
                try {
                    team.addEntry(spectator.getName());
                } catch (UnsupportedOperationException ignored) {
                }
            }
        }
        Scoreboard main = plugin.getServer().getScoreboardManager() != null
                ? plugin.getServer().getScoreboardManager().getMainScoreboard()
                : null;
        Team mainTeam = getOrCreateHiddenTeam(main);
        if (mainTeam != null) {
            try {
                mainTeam.addEntry(spectator.getName());
            } catch (UnsupportedOperationException ignored) {
            }
        }
    }

    private void removeHiddenNametagTeam(Player spectator) {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            Scoreboard scoreboard = viewer.getScoreboard();
            Team team = scoreboard != null ? scoreboard.getTeam(SPECTATOR_HIDDEN_TEAM) : null;
            if (team != null) {
                try {
                    team.removeEntry(spectator.getName());
                } catch (UnsupportedOperationException ignored) {
                }
            }
        }
        Scoreboard main = plugin.getServer().getScoreboardManager() != null
                ? plugin.getServer().getScoreboardManager().getMainScoreboard()
                : null;
        Team mainTeam = main != null ? main.getTeam(SPECTATOR_HIDDEN_TEAM) : null;
        if (mainTeam != null) {
            try {
                mainTeam.removeEntry(spectator.getName());
            } catch (UnsupportedOperationException ignored) {
            }
        }
    }

    private Team getOrCreateHiddenTeam(Scoreboard scoreboard) {
        if (scoreboard == null) {
            return null;
        }
        Team team = scoreboard.getTeam(SPECTATOR_HIDDEN_TEAM);
        if (team != null) {
            return team;
        }
        try {
            team = scoreboard.registerNewTeam(SPECTATOR_HIDDEN_TEAM);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            return team;
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            return scoreboard.getTeam(SPECTATOR_HIDDEN_TEAM);
        }
    }

    public void cleanupLingeringCmiVanish(Player player) {
        if (player == null) {
            return;
        }
        if (!Boolean.TRUE.equals(cmiVanishApplied.get(player.getUniqueId()))) {
            return;
        }
        clearCmiVanish(player);
    }

    private void applyCmiVanish(Player player) {
        if (player == null || !isCmiAvailable()) {
            return;
        }
        if (appearsAlreadyVanished(player)) {
            cmiVanishApplied.put(player.getUniqueId(), false);
            return;
        }
        if (executeCmiVanishCommand(player, true)) {
            cmiVanishApplied.put(player.getUniqueId(), true);
        }
    }

    private void clearCmiVanish(Player player) {
        if (player == null || !isCmiAvailable()) {
            cmiVanishApplied.remove(player.getUniqueId());
            return;
        }
        Boolean applied = cmiVanishApplied.remove(player.getUniqueId());
        if (Boolean.TRUE.equals(applied)) {
            executeCmiVanishCommand(player, false);
        }
    }

    private boolean isCmiAvailable() {
        org.bukkit.plugin.Plugin cmi = plugin.getServer().getPluginManager().getPlugin("CMI");
        return cmi != null && cmi.isEnabled();
    }

    private boolean executeCmiVanishCommand(Player player, boolean enable) {
        try {
            String state = enable ? "on" : "off";
            return plugin.getServer().dispatchCommand(
                    plugin.getServer().getConsoleSender(),
                    "cmi vanish " + player.getName() + " " + state
            );
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean appearsAlreadyVanished(Player player) {
        if (player == null) {
            return false;
        }
        try {
            if (player.hasMetadata("vanished") || player.hasMetadata("cmivanish") || player.hasMetadata("CMI-vanished")) {
                return true;
            }
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (online.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }
                if (!online.canSee(player)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private record SpectatorSession(PlayerSnapshot snapshot, UUID targetId,
                                    boolean collidable, boolean invulnerable,
                                    boolean canPickupItems, boolean silent,
                                    boolean toolsEnabled) {
        private SpectatorSession withTarget(UUID newTargetId, boolean newToolsEnabled) {
            return new SpectatorSession(snapshot, newTargetId, collidable, invulnerable, canPickupItems, silent, newToolsEnabled);
        }
    }
}
