package com.pvparena.manager;

import com.pvparena.model.Arena;
import com.pvparena.model.ArenaStatus;
import com.pvparena.model.Mode;
import com.pvparena.config.PluginSettings;
import com.pvparena.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DuelManager {
    private final JavaPlugin plugin;
    private final ArenaManager arenaManager;
    private final MatchManager matchManager;
    private final ModeManager modeManager;
    private final QueueManager queueManager;
    private final PluginSettings settings;
    private final Map<UUID, DuelRequest> pending = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> outgoing = new ConcurrentHashMap<>();

    public DuelManager(JavaPlugin plugin, ArenaManager arenaManager, MatchManager matchManager,
                       ModeManager modeManager, QueueManager queueManager, PluginSettings settings) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.matchManager = matchManager;
        this.modeManager = modeManager;
        this.queueManager = queueManager;
        this.settings = settings;
    }

    private long getExpireMillis() {
        int seconds = settings != null ? settings.getDuelRequestTimeoutSeconds() : 60;
        return Duration.ofSeconds(Math.max(5, seconds)).toMillis();
    }

    public void request(Player sender, Player target) {
        String modeId = getDefaultModeId();
        if (modeId == null) {
            MessageUtil.send(sender, "mode_not_found");
            return;
        }
        request(sender, target, modeId);
    }

    public void request(Player sender, Player target, String modeId) {
        request(sender, target, modeId, null, 300);
    }

    public void request(Player sender, Player target, String modeId, String arenaId, int timeLimitSeconds) {
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            MessageUtil.send(sender, "duel_not_found");
            return;
        }
        if (matchManager.isInMatch(sender) || matchManager.isInMatch(target)) {
            MessageUtil.send(sender, "duel_in_match");
            return;
        }
        if (queueManager.isQueued(sender) || queueManager.isQueued(target)) {
            MessageUtil.send(sender, "duel_in_queue");
            return;
        }
        if (modeManager.getMode(modeId) == null) {
            MessageUtil.send(sender, "mode_not_found");
            return;
        }
        UUID previousTarget = outgoing.remove(sender.getUniqueId());
        if (previousTarget != null) {
            pending.remove(previousTarget);
        }
        int normalizedTimeLimit = Math.max(60, Math.min(3600, timeLimitSeconds));
        pending.put(target.getUniqueId(), new DuelRequest(sender.getUniqueId(), modeId, arenaId, normalizedTimeLimit, System.currentTimeMillis()));
        outgoing.put(sender.getUniqueId(), target.getUniqueId());
        MessageUtil.send(sender, "duel_sent", Placeholder.unparsed("target", target.getName()));
        Component accept = MessageUtil.message("duel_accept_button")
                .clickEvent(ClickEvent.runCommand("/duel accept " + sender.getName()));
        Component message = MessageUtil.message("duel_received", Placeholder.unparsed("sender", sender.getName()));
        MessageUtil.send(target, message);
        MessageUtil.send(target, accept);
        scheduleExpire(target.getUniqueId(), sender.getUniqueId());
    }

    public void cancel(Player player, String otherName) {
        if (player == null) {
            return;
        }
        boolean changed = false;
        if (otherName != null && !otherName.isBlank()) {
            Player other = plugin.getServer().getPlayerExact(otherName);
            UUID otherId = other != null ? other.getUniqueId() : null;
            changed = cancelOutgoingTo(player.getUniqueId(), otherId) || cancelIncomingFrom(player.getUniqueId(), otherId);
        } else {
            changed = cancelOutgoingAny(player.getUniqueId()) || cancelIncomingAny(player.getUniqueId());
        }
        if (!changed) {
            MessageUtil.send(player, "duel_cancel_none");
        }
    }

    private boolean cancelOutgoingAny(UUID senderId) {
        UUID targetId = outgoing.remove(senderId);
        if (targetId == null) {
            return false;
        }
        DuelRequest req = pending.get(targetId);
        if (req != null && req.challengerId.equals(senderId)) {
            pending.remove(targetId);
        }
        Player sender = plugin.getServer().getPlayer(senderId);
        Player target = plugin.getServer().getPlayer(targetId);
        if (sender != null) {
            MessageUtil.send(sender, "duel_cancelled_sender");
        }
        if (target != null) {
            MessageUtil.send(target, "duel_cancelled_by_sender",
                    Placeholder.unparsed("sender", sender != null ? sender.getName() : ""));
        }
        return true;
    }

    private boolean cancelIncomingAny(UUID targetId) {
        DuelRequest req = pending.remove(targetId);
        if (req == null) {
            return false;
        }
        outgoing.remove(req.challengerId);
        Player target = plugin.getServer().getPlayer(targetId);
        Player challenger = plugin.getServer().getPlayer(req.challengerId);
        if (target != null) {
            MessageUtil.send(target, "duel_cancelled_receiver");
        }
        if (challenger != null) {
            MessageUtil.send(challenger, "duel_cancelled_by_target",
                    Placeholder.unparsed("target", target != null ? target.getName() : ""));
        }
        return true;
    }

    private boolean cancelOutgoingTo(UUID senderId, UUID targetId) {
        if (targetId == null) {
            return false;
        }
        UUID current = outgoing.get(senderId);
        if (current == null || !current.equals(targetId)) {
            return false;
        }
        outgoing.remove(senderId);
        DuelRequest req = pending.get(targetId);
        if (req != null && req.challengerId.equals(senderId)) {
            pending.remove(targetId);
        }
        Player sender = plugin.getServer().getPlayer(senderId);
        Player target = plugin.getServer().getPlayer(targetId);
        if (sender != null) {
            MessageUtil.send(sender, "duel_cancelled_sender");
        }
        if (target != null) {
            MessageUtil.send(target, "duel_cancelled_by_sender",
                    Placeholder.unparsed("sender", sender != null ? sender.getName() : ""));
        }
        return true;
    }

    private boolean cancelIncomingFrom(UUID targetId, UUID challengerId) {
        if (challengerId == null) {
            return false;
        }
        DuelRequest req = pending.get(targetId);
        if (req == null || !req.challengerId.equals(challengerId)) {
            return false;
        }
        pending.remove(targetId);
        outgoing.remove(challengerId);
        Player target = plugin.getServer().getPlayer(targetId);
        Player challenger = plugin.getServer().getPlayer(challengerId);
        if (target != null) {
            MessageUtil.send(target, "duel_cancelled_receiver");
        }
        if (challenger != null) {
            MessageUtil.send(challenger, "duel_cancelled_by_target",
                    Placeholder.unparsed("target", target != null ? target.getName() : ""));
        }
        return true;
    }

    public void accept(Player target, String challengerName) {
        DuelRequest request = pending.get(target.getUniqueId());
        if (request == null) {
            MessageUtil.send(target, "duel_accept_none");
            return;
        }
        if (System.currentTimeMillis() - request.createdAt > getExpireMillis()) {
            pending.remove(target.getUniqueId());
            outgoing.remove(request.challengerId);
            MessageUtil.send(target, "duel_expired");
            Player challenger = plugin.getServer().getPlayer(request.challengerId);
            if (challenger != null) {
                MessageUtil.send(challenger, "duel_expired_sender");
            }
            return;
        }
        Player challenger = plugin.getServer().getPlayer(request.challengerId);
        if (challenger == null || !challenger.getName().equalsIgnoreCase(challengerName)) {
            MessageUtil.send(target, "duel_not_found");
            return;
        }
        if (matchManager.isInMatch(target) || matchManager.isInMatch(challenger)) {
            pending.remove(target.getUniqueId());
            outgoing.remove(request.challengerId);
            MessageUtil.send(target, "duel_in_match");
            if (challenger != null) {
                MessageUtil.send(challenger, "duel_cancelled_busy_sender",
                        Placeholder.unparsed("target", target.getName()));
            }
            return;
        }
        if (queueManager.isQueued(target) || queueManager.isQueued(challenger)) {
            pending.remove(target.getUniqueId());
            outgoing.remove(request.challengerId);
            MessageUtil.send(target, "duel_in_queue");
            if (challenger != null) {
                MessageUtil.send(challenger, "duel_cancelled_busy_sender",
                        Placeholder.unparsed("target", target.getName()));
            }
            return;
        }
        Mode selectedMode = modeManager.getMode(request.modeId);
        Arena arena = resolveArenaForRequest(selectedMode, request.arenaId);
        if (arena == null) {
            MessageUtil.send(target, "duel_no_arena");
            return;
        }
        pending.remove(target.getUniqueId());
        outgoing.remove(request.challengerId);
        arena.setStatus(ArenaStatus.IN_GAME);
        matchManager.startMatch(challenger.getUniqueId(), target.getUniqueId(), request.modeId, arena, request.timeLimitSeconds);
    }

    private Arena resolveArenaForRequest(Mode selectedMode, String requestedArenaId) {
        if (selectedMode == null) {
            return null;
        }
        if (requestedArenaId == null || requestedArenaId.isBlank()) {
            return arenaManager.getFreeArena(selectedMode);
        }
        Arena requested = arenaManager.getArena(requestedArenaId);
        if (requested == null || requested.getStatus() != ArenaStatus.FREE || !requested.isReady()) {
            return null;
        }
        if (selectedMode.hasArenaRestriction()
                && !selectedMode.getPreferredArenaIds().contains(requested.getId().toLowerCase())) {
            return null;
        }
        return requested;
    }

    private void scheduleExpire(UUID targetId, UUID challengerId) {
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
            DuelRequest request = pending.get(targetId);
            if (request == null || !request.challengerId.equals(challengerId)) {
                return;
            }
            if (System.currentTimeMillis() - request.createdAt <= getExpireMillis()) {
                return;
            }
            pending.remove(targetId);
            outgoing.remove(challengerId);
            Player target = plugin.getServer().getPlayer(targetId);
            if (target != null) {
                MessageUtil.send(target, "duel_expired");
            }
            Player challenger = plugin.getServer().getPlayer(challengerId);
            if (challenger != null) {
                MessageUtil.send(challenger, "duel_expired_sender");
            }
        }, Math.max(20L, (settings != null ? settings.getDuelRequestTimeoutSeconds() : 60) * 20L + 20L));
    }

    private String getDefaultModeId() {
        Mode mode = modeManager.getModes().values().stream().findFirst().orElse(null);
        return mode != null ? mode.getId() : null;
    }

    private static class DuelRequest {
        private final UUID challengerId;
        private final String modeId;
        private final String arenaId;
        private final int timeLimitSeconds;
        private final long createdAt;

        private DuelRequest(UUID challengerId, String modeId, String arenaId, int timeLimitSeconds, long createdAt) {
            this.challengerId = challengerId;
            this.modeId = modeId;
            this.arenaId = arenaId;
            this.timeLimitSeconds = timeLimitSeconds;
            this.createdAt = createdAt;
        }
    }
}
