package com.pvparena.hook.placeholder;

import com.pvparena.manager.MatchManager;
import com.pvparena.model.MatchSession;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class PvPArenaPlaceholderExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final MatchManager matchManager;

    public PvPArenaPlaceholderExpansion(JavaPlugin plugin, MatchManager matchManager) {
        this.plugin = plugin;
        this.matchManager = matchManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "pvparena";
    }

    @Override
    public @NotNull String getAuthor() {
        if (plugin.getDescription().getAuthors().isEmpty()) {
            return "unknown";
        }
        return String.join(",", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        MatchSession session = matchManager.getMatch(player);
        String key = params.toLowerCase();

        if ("in_match".equals(key)) {
            return session != null ? "1" : "0";
        }
        if (session == null) {
            return "-";
        }

        UUID playerId = player.getUniqueId();
        UUID opponentId = session.getOpponent(playerId);
        int myScore = session.getRoundsWon(playerId);
        int oppScore = opponentId != null ? session.getRoundsWon(opponentId) : 0;

        switch (key) {
            case "mode":
                return session.getMode().getDisplayName();
            case "round":
                return String.valueOf(session.getCurrentRound());
            case "rounds_to_win":
                return String.valueOf(session.getMode().getSettings().getRoundsToWin());
            case "score":
                return String.valueOf(myScore);
            case "opponent_score":
                return String.valueOf(oppScore);
            case "score_line":
                return myScore + "-" + oppScore;
            case "opponent":
                if (opponentId == null) {
                    return "-";
                }
                OfflinePlayer offline = Bukkit.getOfflinePlayer(opponentId);
                return offline.getName() == null ? "-" : offline.getName();
            default:
                return null;
        }
    }
}
