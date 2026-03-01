package com.pvparena.command;

import com.pvparena.gui.DuelMenu;
import com.pvparena.gui.MainMenu;
import com.pvparena.gui.ResultMenu;
import com.pvparena.manager.DuelManager;
import com.pvparena.manager.MatchManager;
import com.pvparena.util.SchedulerUtil;
import com.pvparena.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class DuelCommand implements CommandExecutor {
    private final MainMenu mainMenu;
    private final ResultMenu resultMenu;
    private final DuelManager duelManager;
    private final MatchManager matchManager;
    private final DuelMenu duelMenu;

    public DuelCommand(MainMenu mainMenu, ResultMenu resultMenu, DuelManager duelManager, MatchManager matchManager, DuelMenu duelMenu) {
        this.mainMenu = mainMenu;
        this.resultMenu = resultMenu;
        this.duelManager = duelManager;
        this.matchManager = matchManager;
        this.duelMenu = duelMenu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "only_player");
            return true;
        }
        JavaPlugin plugin = JavaPlugin.getProvidingPlugin(getClass());
        if (args.length == 0) {
            SchedulerUtil.runOnPlayer(plugin, player, () -> mainMenu.open(player));
            return true;
        }
        if (args[0].equalsIgnoreCase("accept") && args.length >= 2) {
            duelManager.accept(player, args[1]);
            return true;
        }
        if (args[0].equalsIgnoreCase("cancel")) {
            duelManager.cancel(player, args.length >= 2 ? args[1] : null);
            return true;
        }
        if (args[0].equalsIgnoreCase("leave")) {
            if (matchManager.leaveWinnerLootPhase(player)) {
                MessageUtil.send(player, "duel_leave_success");
            } else {
                MessageUtil.send(player, "duel_leave_unavailable");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("result")) {
            SchedulerUtil.runOnPlayer(plugin, player, () -> resultMenu.open(player));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            MessageUtil.send(player, "duel_not_found");
            return true;
        }
        SchedulerUtil.runOnPlayer(plugin, player, () -> duelMenu.open(player, target.getUniqueId()));
        return true;
    }
}
