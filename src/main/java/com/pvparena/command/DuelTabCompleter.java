package com.pvparena.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public class DuelTabCompleter implements TabCompleter {
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            addIfMatch(result, args[0], "accept");
            addIfMatch(result, args[0], "cancel");
            addIfMatch(result, args[0], "dleave");
            addIfMatch(result, args[0], "result");
            Bukkit.getOnlinePlayers().forEach(p -> addIfMatch(result, args[0], p.getName()));
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
