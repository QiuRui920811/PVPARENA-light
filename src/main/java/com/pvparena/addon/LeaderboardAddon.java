package com.pvparena.addon;

import java.util.Map;
import java.util.UUID;

/**
 * Reserved extension point for external leaderboard systems.
 */
public interface LeaderboardAddon {
    String getProviderId();

    long getScore(UUID playerId);

    Map<UUID, Long> getTopScores(int limit);
}
