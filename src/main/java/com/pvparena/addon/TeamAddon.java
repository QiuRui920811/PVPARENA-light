package com.pvparena.addon;

import java.util.Set;
import java.util.UUID;

/**
 * Reserved extension point for external team systems.
 */
public interface TeamAddon {
    String getProviderId();

    boolean isInTeam(UUID playerId);

    String getTeamId(UUID playerId);

    Set<UUID> getMembers(String teamId);
}
