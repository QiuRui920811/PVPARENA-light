package com.pvparena.manager;

import com.pvparena.util.MessageUtil;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PkManager {
    private final Set<UUID> enabled = new HashSet<>();
    private final Map<UUID, Boolean> forcedPrev = new HashMap<>();

    public boolean isEnabled(UUID playerId) {
        return enabled.contains(playerId);
    }

    public void toggle(Player player) {
        UUID id = player.getUniqueId();
        if (enabled.contains(id)) {
            enabled.remove(id);
            MessageUtil.send(player, "pk_off");
        } else {
            enabled.add(id);
            MessageUtil.send(player, "pk_on");
        }
    }

    public void forceEnable(UUID playerId) {
        if (!forcedPrev.containsKey(playerId)) {
            forcedPrev.put(playerId, enabled.contains(playerId));
        }
        enabled.add(playerId);
    }

    public void restore(UUID playerId) {
        if (!forcedPrev.containsKey(playerId)) {
            return;
        }
        boolean prev = forcedPrev.remove(playerId);
        if (prev) {
            enabled.add(playerId);
        } else {
            enabled.remove(playerId);
        }
    }
}
