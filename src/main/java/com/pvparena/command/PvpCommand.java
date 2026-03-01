package com.pvparena.command;

import com.pvparena.gui.MainMenu;
import com.pvparena.gui.SpectatorBrowseMenu;
import com.pvparena.manager.MatchManager;
import com.pvparena.manager.SpectatorManager;
import com.pvparena.util.MessageUtil;
import com.pvparena.util.SchedulerUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class PvpCommand implements CommandExecutor {
    private final MainMenu mainMenu;
    private final MatchManager matchManager;
    private final SpectatorManager spectatorManager;
    private final SpectatorBrowseMenu spectatorBrowseMenu;

    public PvpCommand(MainMenu mainMenu, MatchManager matchManager,
                      SpectatorManager spectatorManager, SpectatorBrowseMenu spectatorBrowseMenu) {
        this.mainMenu = mainMenu;
        this.matchManager = matchManager;
        this.spectatorManager = spectatorManager;
        this.spectatorBrowseMenu = spectatorBrowseMenu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "only_player");
            return true;
        }

        if (args.length >= 1 && (args[0].equalsIgnoreCase("spec") || args[0].equalsIgnoreCase("spectate"))) {
            if (!player.hasPermission("pvparena.spectate") && !player.hasPermission("pvparena.admin")) {
                MessageUtil.send(player, "no_permission");
                return true;
            }
            if (args.length == 1) {
                spectatorBrowseMenu.open(player, 0);
                return true;
            }
            if (args[1].equalsIgnoreCase("leave")) {
                if (!spectatorManager.leaveSpectator(player)) {
                    MessageUtil.send(player, "spectator_not_active");
                }
                return true;
            }
            Player target = player.getServer().getPlayerExact(args[1]);
            if (target == null) {
                MessageUtil.send(player, "duel_not_found");
                return true;
            }
            if (matchManager.getMatch(target) == null) {
                MessageUtil.send(player, "spectator_target_not_in_match",
                        Placeholder.unparsed("target", target.getName()));
                return true;
            }
            spectatorManager.enterSpectator(player, target);
            return true;
        }

        JavaPlugin plugin = JavaPlugin.getProvidingPlugin(getClass());
        SchedulerUtil.runOnPlayer(plugin, player, () -> mainMenu.open(player));
        return true;
    }
}
