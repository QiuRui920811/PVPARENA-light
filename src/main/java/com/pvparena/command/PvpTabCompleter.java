package com.pvparena.command;

import com.pvparena.manager.MatchManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class PvpTabCompleter implements TabCompleter {
    private final MatchManager matchManager;

    public PvpTabCompleter(MatchManager matchManager) {
        this.matchManager = matchManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!(sender instanceof Player player)) {
            return result;
        }
        if (!player.hasPermission("pvparena.spectate") && !player.hasPermission("pvparena.admin")) {
            return result;
        }

        if (args.length == 1) {
            add(result, args[0], "spec");
            add(result, args[0], "spectate");
            return result;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("spec") || args[0].equalsIgnoreCase("spectate"))) {
            add(result, args[1], "leave");
            for (Player online : player.getServer().getOnlinePlayers()) {
                if (online == null || !online.isOnline()) {
                    continue;
                }
                if (online.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }
                if (matchManager.getMatch(online) == null) {
                    continue;
                }
                add(result, args[1], online.getName());
            }
        }

        return result;
    }

    private void add(List<String> list, String input, String value) {
        if (value.toLowerCase().startsWith(input.toLowerCase())) {
            list.add(value);
        }
    }
}
