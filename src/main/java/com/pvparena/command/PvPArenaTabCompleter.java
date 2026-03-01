package com.pvparena.command;

import com.pvparena.PvPArenaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public class PvPArenaTabCompleter implements TabCompleter {
    private final PvPArenaPlugin plugin;

    public PvPArenaTabCompleter(PvPArenaPlugin plugin) {
        this.plugin = plugin;
    }
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!sender.hasPermission("pvparena.admin")) {
            return result;
        }
        if (args.length == 1) {
            add(result, args[0], "kit");
            add(result, args[0], "addkit");
            add(result, args[0], "delkit");
            add(result, args[0], "arena");
            add(result, args[0], "create");
            add(result, args[0], "delarena");
            add(result, args[0], "setspawn");
            add(result, args[0], "door");
            add(result, args[0], "tool");
            add(result, args[0], "specgui");
            add(result, args[0], "help");
            add(result, args[0], "reload");
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("kit")) {
            add(result, args[1], "list");
            add(result, args[1], "open");
            return result;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("kit") && args[1].equalsIgnoreCase("open")) {
            plugin.getKitManager().getKitIds().forEach(id -> add(result, args[2], id));
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delkit")) {
            plugin.getKitManager().getKitIds().forEach(id -> add(result, args[1], id));
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("arena")) {
            add(result, args[1], "list");
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("door")) {
            add(result, args[1], "list");
            add(result, args[1], "create");
            add(result, args[1], "delete");
            add(result, args[1], "setdelay");
            add(result, args[1], "setevict");
            add(result, args[1], "setanim");
            add(result, args[1], "setdistance");
            add(result, args[1], "open");
            add(result, args[1], "close");
            return result;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("door")) {
            plugin.getArenaManager().getArenas().forEach(arena -> add(result, args[2], arena.getId()));
            return result;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("door")) {
            String sub = args[1].toLowerCase();
            String arenaId = args[2];
            com.pvparena.model.Arena arena = plugin.getArenaManager().getArena(arenaId);
            if (arena == null) {
                return result;
            }
            if (sub.equals("open") || sub.equals("close")) {
                add(result, args[3], "all");
            }
            arena.getDoors().forEach(door -> add(result, args[3], door.getId()));
            return result;
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("door") && args[1].equalsIgnoreCase("setevict")) {
            add(result, args[4], "true");
            add(result, args[4], "false");
            return result;
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("door") && args[1].equalsIgnoreCase("setanim")) {
            add(result, args[4], "instant");
            add(result, args[4], "lift");
            add(result, args[4], "swing");
            add(result, args[4], "bigdoor");
            return result;
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("door") && args[1].equalsIgnoreCase("setanim")) {
            add(result, args[5], "auto");
            add(result, args[5], "inward");
            add(result, args[5], "outward");
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delarena")) {
            plugin.getArenaManager().getArenas().forEach(arena -> add(result, args[1], arena.getId()));
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setspawn")) {
            plugin.getArenaManager().getArenas().forEach(arena -> add(result, args[1], arena.getId()));
            return result;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setspawn")) {
            add(result, args[2], "1");
            add(result, args[2], "2");
            return result;
        }
        return result;
    }

    private void add(List<String> list, String input, String value) {
        if (value.toLowerCase().startsWith(input.toLowerCase())) {
            list.add(value);
        }
    }
}
