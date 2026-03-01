package com.pvparena.command;

import com.pvparena.manager.ArenaManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public class ArenaTabCompleter implements TabCompleter {
    private final ArenaManager arenaManager;

    public ArenaTabCompleter(ArenaManager arenaManager) {
        this.arenaManager = arenaManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!sender.hasPermission("pvparena.admin")) {
            return result;
        }
        if (args.length == 1) {
            addIfMatch(result, args[0], "create");
            addIfMatch(result, args[0], "setspawn");
            return result;
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("create")) {
            arenaManager.getArenas().forEach(arena -> addIfMatch(result, args[1], arena.getId()));
            return result;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setspawn")) {
            addIfMatch(result, args[2], "1");
            addIfMatch(result, args[2], "2");
            return result;
        }
        return result;
    }

    private void addIfMatch(List<String> list, String input, String value) {
        if (value.toLowerCase().startsWith(input.toLowerCase())) {
            list.add(value);
        }
    }
}
