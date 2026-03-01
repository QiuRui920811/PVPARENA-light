package com.pvparena.command;

import com.pvparena.manager.ArenaManager;
import com.pvparena.manager.SelectionManager;
import com.pvparena.model.Arena;
import com.pvparena.util.MessageUtil;
import com.pvparena.util.SchedulerUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class ArenaCommand implements CommandExecutor {
    private final ArenaManager arenaManager;
    private final SelectionManager selectionManager;

    public ArenaCommand(ArenaManager arenaManager, SelectionManager selectionManager) {
        this.arenaManager = arenaManager;
        this.selectionManager = selectionManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("pvparena.admin")) {
            MessageUtil.send(sender, "no_permission");
            return true;
        }
        if (args.length < 1) {
            MessageUtil.send(sender, "arena_cmd_usage");
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("create")) {
            if (!(sender instanceof Player player)) {
                MessageUtil.send(sender, "only_player");
                return true;
            }
            if (args.length < 2) {
                MessageUtil.send(sender, "arena_create_cmd_usage");
                return true;
            }
            String id = args[1];
            World world = args.length >= 3 ? Bukkit.getWorld(args[2]) : player.getWorld();
            if (world == null) {
                MessageUtil.send(sender, "world_not_found");
                return true;
            }
            boolean exists = arenaManager.getArena(id) != null;
            if (!exists && arenaManager.getArenas().size() >= 3) {
                MessageUtil.send(sender, "arena_full");
                return true;
            }
            JavaPlugin plugin = JavaPlugin.getProvidingPlugin(getClass());
            SchedulerUtil.runOnPlayer(plugin, player, () -> {
                Location base = player.getLocation();
                Arena arena = arenaManager.createArena(id, world, base);
                Location p1 = selectionManager.getPos1(player.getUniqueId());
                Location p2 = selectionManager.getPos2(player.getUniqueId());
                if (p1 != null && p2 != null
                        && p1.getWorld() != null && p2.getWorld() != null
                        && p1.getWorld().getName().equalsIgnoreCase(p2.getWorld().getName())
                        && p1.getWorld().getName().equalsIgnoreCase(world.getName())) {
                    double minx = Math.min(p1.getX(), p2.getX());
                    double miny = Math.min(p1.getY(), p2.getY());
                    double minz = Math.min(p1.getZ(), p2.getZ());
                    double maxx = Math.max(p1.getX(), p2.getX());
                    double maxy = Math.max(p1.getY(), p2.getY());
                    double maxz = Math.max(p1.getZ(), p2.getZ());
                    arena.setMinBound(new Location(world, minx, miny, minz));
                    arena.setMaxBound(new Location(world, maxx, maxy, maxz));
                    arenaManager.saveArena(arena);
                    MessageUtil.send(player, "arena_bounds_updated_from_wand");
                }
                MessageUtil.send(player, "arena_created", Placeholder.unparsed("arena", arena.getId()));
            });
            return true;
        }
        if (sub.equals("setspawn")) {
            if (!(sender instanceof Player player)) {
                MessageUtil.send(sender, "only_player");
                return true;
            }
            if (args.length < 3) {
                MessageUtil.send(sender, "arena_setspawn_cmd_usage");
                return true;
            }
            String id = args[1];
            int index = Integer.parseInt(args[2]);
            Arena arena = arenaManager.getArena(id);
            if (arena == null) {
                MessageUtil.send(sender, "arena_not_found");
                return true;
            }
            JavaPlugin plugin = JavaPlugin.getProvidingPlugin(getClass());
            SchedulerUtil.runOnPlayer(plugin, player, () -> {
                Location loc = player.getLocation();
                if (index == 1) {
                    arena.setSpawn1(loc);
                } else {
                    arena.setSpawn2(loc);
                }
                arenaManager.saveArena(arena);
                MessageUtil.send(player, "arena_spawn_set", Placeholder.unparsed("index", String.valueOf(index)));
            });
            return true;
        }
        if (sub.equals("setbounds")) {
            MessageUtil.send(sender, "unknown_subcommand");
            return true;
        }
        MessageUtil.send(sender, "unknown_subcommand");
        return true;
    }
}
