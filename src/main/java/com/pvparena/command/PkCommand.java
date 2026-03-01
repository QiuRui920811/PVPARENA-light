package com.pvparena.command;

import com.pvparena.manager.PkManager;
import com.pvparena.config.PluginSettings;
import com.pvparena.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PkCommand implements CommandExecutor {
    private final PkManager pkManager;
    private final PluginSettings settings;

    public PkCommand(PkManager pkManager, PluginSettings settings) {
        this.pkManager = pkManager;
        this.settings = settings;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageUtil.getPlain("pk_only_player"));
            return true;
        }
        if (!settings.isPkEnabled()) {
            MessageUtil.send(player, "pk_disabled");
            return true;
        }
        pkManager.toggle(player);
        return true;
    }
}
