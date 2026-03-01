package com.pvparena.command;

import com.pvparena.manager.ArenaManager;
import com.pvparena.manager.BigDoorManager;
import com.pvparena.manager.KitManager;
import com.pvparena.manager.ModeManager;
import com.pvparena.manager.SelectionManager;
import com.pvparena.config.PluginSettings;
import com.pvparena.model.Arena;
import com.pvparena.model.ArenaDoor;
import com.pvparena.model.ModeKit;
import com.pvparena.gui.KitEditorMenu;
import com.pvparena.gui.SpectatorAdminMenu;
import com.pvparena.util.MessageUtil;
import com.pvparena.util.SelectionToolUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import com.pvparena.util.SchedulerUtil;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class PvPArenaCommand implements CommandExecutor {
    private static final TextColor TITLE_COLOR = TextColor.fromHexString("#6ec1ff");
    private static final TextColor CMD_COLOR = TextColor.fromHexString("#9ad8ff");
    private static final TextColor DESC_COLOR = TextColor.fromHexString("#d8d8d8");

    private final KitManager kitManager;
    private final ModeManager modeManager;
    private final ArenaManager arenaManager;
    private final SelectionManager selectionManager;
    private final KitEditorMenu kitEditorMenu;
    private final SpectatorAdminMenu spectatorAdminMenu;
    private final PluginSettings settings;
    private final JavaPlugin plugin;

    public PvPArenaCommand(JavaPlugin plugin, KitManager kitManager, ModeManager modeManager, ArenaManager arenaManager,
                           SelectionManager selectionManager, KitEditorMenu kitEditorMenu,
                           SpectatorAdminMenu spectatorAdminMenu, PluginSettings settings) {
        this.plugin = plugin;
        this.kitManager = kitManager;
        this.modeManager = modeManager;
        this.arenaManager = arenaManager;
        this.selectionManager = selectionManager;
        this.kitEditorMenu = kitEditorMenu;
        this.spectatorAdminMenu = spectatorAdminMenu;
        this.settings = settings;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }
        if (args[0].equalsIgnoreCase("kit")) {
            if (args.length < 2) {
                MessageUtil.send(sender, "pvparena_kit_usage");
                return true;
            }
            String sub = args[1].toLowerCase();
            if (sub.equals("list")) {
                MessageUtil.send(sender, "kit_list_header");
                for (String id : kitManager.getKitIds()) {
                    ModeKit kit = kitManager.getKit(id);
                    int itemCount = kit != null ? kit.getItems().size() : 0;
                    int armorCount = kit != null ? kit.getArmor().size() : 0;
                    MessageUtil.send(sender, "kit_list_entry",
                        Placeholder.unparsed("kit", id),
                        Placeholder.unparsed("items", String.valueOf(itemCount)),
                        Placeholder.unparsed("armor", String.valueOf(armorCount)));
                }
                return true;
            }
            if (sub.equals("open")) {
                if (!(sender instanceof Player player)) {
                    MessageUtil.send(sender, "only_player");
                    return true;
                }
                if (!player.hasPermission("pvparena.admin")) {
                    MessageUtil.send(player, "no_permission");
                    return true;
                }
                if (args.length < 3) {
                    MessageUtil.send(sender, "kit_open_usage");
                    return true;
                }
                String id = args[2];
                boolean exists = kitManager.getKit(id) != null;
                if (!exists && kitManager.getKitIds().size() >= 3) {
                    MessageUtil.send(sender, "kit_full");
                    return true;
                }
                kitEditorMenu.open(player, id);
                return true;
            }
            MessageUtil.send(sender, "kit_unknown_sub");
            return true;
        }
        if (args[0].equalsIgnoreCase("addkit")) {
            if (!(sender instanceof Player player)) {
                MessageUtil.send(sender, "only_player");
                return true;
            }
            if (!player.hasPermission("pvparena.admin")) {
                MessageUtil.send(player, "no_permission");
                return true;
            }
            if (args.length < 2) {
                MessageUtil.send(sender, "kit_add_usage");
                return true;
            }
            String id = args[1];
            boolean exists = kitManager.getKit(id) != null;
            if (!exists && kitManager.getKitIds().size() >= 3) {
                MessageUtil.send(sender, "kit_full");
                return true;
            }
            JavaPlugin plugin = JavaPlugin.getProvidingPlugin(getClass());
            SchedulerUtil.runOnPlayer(plugin, player, () -> {
                ItemStack[] contents = player.getInventory().getStorageContents();
                ItemStack[] armor = player.getInventory().getArmorContents();
                List<ItemStack> items = new ArrayList<>();
                List<ItemStack> armorList = new ArrayList<>();
                for (ItemStack item : contents) {
                    if (item != null && !isArmor(item)) {
                        items.add(item.clone());
                    }
                }
                ItemStack offhand = player.getInventory().getItemInOffHand();
                if (offhand != null && !isArmor(offhand)) {
                    items.add(offhand.clone());
                }
                for (ItemStack item : armor) {
                    if (item != null) {
                        armorList.add(item.clone());
                    }
                }
                List<PotionEffect> effects = new ArrayList<>(player.getActivePotionEffects());
                kitManager.saveKit(id, new ModeKit(items, armorList, effects));
                modeManager.load();
                MessageUtil.send(player, "kit_saved", Placeholder.unparsed("kit", id));
            });
            return true;
        }
        if (args[0].equalsIgnoreCase("arena")) {
            if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
                MessageUtil.send(sender, "arena_list_header");
                for (Arena arena : arenaManager.getArenas()) {
                    String world = arena.getWorldName() == null ? "?" : arena.getWorldName();
                    Location s1 = arena.getSpawn1();
                    Location s2 = arena.getSpawn2();
                    String s1Text = s1 == null ? MessageUtil.getRaw("arena_spawn_unset") : formatLocation(s1);
                    String s2Text = s2 == null ? MessageUtil.getRaw("arena_spawn_unset") : formatLocation(s2);
                    MessageUtil.send(sender, "arena_list_entry",
                        Placeholder.unparsed("arena", arena.getId()),
                        Placeholder.unparsed("world", world),
                        Placeholder.unparsed("spawn1", s1Text),
                        Placeholder.unparsed("spawn2", s2Text));
                }
                return true;
            }
        }

        if (args[0].equalsIgnoreCase("door")) {
            if (!sender.hasPermission("pvparena.admin")) {
                MessageUtil.send(sender, "no_permission");
                return true;
            }
            if (args.length < 2) {
                MessageUtil.send(sender, "door_usage");
                return true;
            }

            BigDoorManager doorManager = plugin instanceof com.pvparena.PvPArenaPlugin arenaPlugin
                ? arenaPlugin.getBigDoorManager()
                : null;

            String sub = args[1].toLowerCase();
            if (sub.equals("list")) {
                if (args.length < 3) {
                    MessageUtil.send(sender, "door_list_usage");
                    return true;
                }
                Arena arena = arenaManager.getArena(args[2]);
                if (arena == null) {
                    MessageUtil.send(sender, "arena_not_found");
                    return true;
                }
                MessageUtil.send(sender, "door_list_header", Placeholder.unparsed("arena", arena.getId()));
                if (arena.getDoors().isEmpty()) {
                    MessageUtil.send(sender, "door_list_empty");
                    return true;
                }
                for (ArenaDoor door : arena.getDoors()) {
                    MessageUtil.send(sender, "door_list_entry",
                        Placeholder.unparsed("door", door.getId()),
                        Placeholder.unparsed("delay", String.valueOf(door.getCloseDelaySeconds())),
                        Placeholder.unparsed("evict", String.valueOf(door.isEvictOnClose())),
                        Placeholder.unparsed("anim", door.getAnimationType()),
                        Placeholder.unparsed("distance", String.valueOf(door.getAnimationDistance())));
                }
                return true;
            }

            if (sub.equals("create")) {
                if (!(sender instanceof Player player)) {
                    MessageUtil.send(sender, "only_player");
                    return true;
                }
                if (args.length < 4) {
                    MessageUtil.send(sender, "door_create_usage");
                    return true;
                }
                Arena arena = arenaManager.getArena(args[2]);
                if (arena == null) {
                    MessageUtil.send(player, "arena_not_found");
                    return true;
                }
                Location p1 = selectionManager.getPos1(player.getUniqueId());
                Location p2 = selectionManager.getPos2(player.getUniqueId());
                if (p1 == null || p2 == null) {
                    MessageUtil.send(player, "selection_required");
                    return true;
                }
                if (!p1.getWorld().getName().equalsIgnoreCase(p2.getWorld().getName())) {
                    MessageUtil.send(player, "selection_world_mismatch");
                    return true;
                }
                if (arena.getWorldName() == null || !arena.getWorldName().equalsIgnoreCase(p1.getWorld().getName())) {
                    MessageUtil.send(player, "arena_world_mismatch");
                    return true;
                }
                String doorId = args[3].toLowerCase();
                ArenaDoor door = new ArenaDoor(doorId);
                door.setMinBound(new Location(
                    p1.getWorld(),
                    Math.min(p1.getX(), p2.getX()),
                    Math.min(p1.getY(), p2.getY()),
                    Math.min(p1.getZ(), p2.getZ())));
                door.setMaxBound(new Location(
                    p1.getWorld(),
                    Math.max(p1.getX(), p2.getX()),
                    Math.max(p1.getY(), p2.getY()),
                    Math.max(p1.getZ(), p2.getZ())));
                if (args.length >= 5) {
                    try {
                        door.setCloseDelaySeconds(Integer.parseInt(args[4]));
                    } catch (NumberFormatException ex) {
                        MessageUtil.send(player, "door_number_invalid");
                        return true;
                    }
                }
                arena.upsertDoor(door);
                if (doorManager != null) {
                    doorManager.invalidateDoorSnapshot(arena.getId(), door.getId());
                }
                arenaManager.saveArena(arena);
                MessageUtil.send(player, "door_created",
                    Placeholder.unparsed("arena", arena.getId()),
                    Placeholder.unparsed("door", door.getId()));
                return true;
            }

            if (sub.equals("delete")) {
                if (args.length < 4) {
                    MessageUtil.send(sender, "door_delete_usage");
                    return true;
                }
                Arena arena = arenaManager.getArena(args[2]);
                if (arena == null) {
                    MessageUtil.send(sender, "arena_not_found");
                    return true;
                }
                String removedDoorId = args[3].toLowerCase();
                if (!arena.removeDoor(removedDoorId)) {
                    MessageUtil.send(sender, "door_not_found");
                    return true;
                }
                if (doorManager != null) {
                    doorManager.invalidateDoorSnapshot(arena.getId(), removedDoorId);
                }
                arenaManager.saveArena(arena);
                MessageUtil.send(sender, "door_deleted",
                    Placeholder.unparsed("arena", arena.getId()),
                    Placeholder.unparsed("door", removedDoorId));
                return true;
            }

            if (sub.equals("setdelay")) {
                if (args.length < 5) {
                    MessageUtil.send(sender, "door_setdelay_usage");
                    return true;
                }
                Arena arena = arenaManager.getArena(args[2]);
                if (arena == null) {
                    MessageUtil.send(sender, "arena_not_found");
                    return true;
                }
                ArenaDoor door = arena.getDoor(args[3]);
                if (door == null) {
                    MessageUtil.send(sender, "door_not_found");
                    return true;
                }
                try {
                    door.setCloseDelaySeconds(Integer.parseInt(args[4]));
                } catch (NumberFormatException ex) {
                    MessageUtil.send(sender, "door_number_invalid");
                    return true;
                }
                arenaManager.saveArena(arena);
                MessageUtil.send(sender, "door_delay_updated",
                    Placeholder.unparsed("door", door.getId()),
                    Placeholder.unparsed("delay", String.valueOf(door.getCloseDelaySeconds())));
                return true;
            }

            if (sub.equals("setevict")) {
                if (args.length < 5) {
                    MessageUtil.send(sender, "door_setevict_usage");
                    return true;
                }
                Arena arena = arenaManager.getArena(args[2]);
                if (arena == null) {
                    MessageUtil.send(sender, "arena_not_found");
                    return true;
                }
                ArenaDoor door = arena.getDoor(args[3]);
                if (door == null) {
                    MessageUtil.send(sender, "door_not_found");
                    return true;
                }
                boolean enabled = Boolean.parseBoolean(args[4]);
                door.setEvictOnClose(enabled);
                arenaManager.saveArena(arena);
                MessageUtil.send(sender, "door_evict_updated",
                    Placeholder.unparsed("door", door.getId()),
                    Placeholder.unparsed("value", String.valueOf(enabled)));
                return true;
            }

            if (sub.equals("setanim")) {
                if (args.length < 5) {
                    MessageUtil.send(sender, "door_setanim_usage");
                    return true;
                }
                Arena arena = arenaManager.getArena(args[2]);
                if (arena == null) {
                    MessageUtil.send(sender, "arena_not_found");
                    return true;
                }
                ArenaDoor door = arena.getDoor(args[3]);
                if (door == null) {
                    MessageUtil.send(sender, "door_not_found");
                    return true;
                }
                String anim = args[4].toLowerCase();
                if (!anim.equals("instant") && !anim.equals("lift") && !anim.equals("swing") && !anim.equals("bigdoor")) {
                    MessageUtil.send(sender, "door_anim_invalid");
                    return true;
                }
                door.setAnimationType(anim);
                if (args.length >= 6) {
                    if (!door.setSwingDirection(args[5])) {
                        MessageUtil.send(sender, "door_anim_invalid");
                        return true;
                    }
                } else if (anim.equals("swing") || anim.equals("bigdoor")) {
                    door.setSwingDirection("auto");
                }
                arenaManager.saveArena(arena);
                MessageUtil.send(sender, "door_anim_updated",
                    Placeholder.unparsed("door", door.getId()),
                    Placeholder.unparsed("anim", door.getAnimationType() + ":" + door.getSwingDirection()));
                return true;
            }

            if (sub.equals("setdistance")) {
                if (args.length < 5) {
                    MessageUtil.send(sender, "door_setdistance_usage");
                    return true;
                }
                Arena arena = arenaManager.getArena(args[2]);
                if (arena == null) {
                    MessageUtil.send(sender, "arena_not_found");
                    return true;
                }
                ArenaDoor door = arena.getDoor(args[3]);
                if (door == null) {
                    MessageUtil.send(sender, "door_not_found");
                    return true;
                }
                try {
                    door.setAnimationDistance(Integer.parseInt(args[4]));
                } catch (NumberFormatException ex) {
                    MessageUtil.send(sender, "door_number_invalid");
                    return true;
                }
                arenaManager.saveArena(arena);
                MessageUtil.send(sender, "door_distance_updated",
                    Placeholder.unparsed("door", door.getId()),
                    Placeholder.unparsed("distance", String.valueOf(door.getAnimationDistance())));
                return true;
            }

            if (sub.equals("open") || sub.equals("close")) {
                if (args.length < 4) {
                    MessageUtil.send(sender, sub.equals("open") ? "door_open_usage" : "door_close_usage");
                    return true;
                }
                Arena arena = arenaManager.getArena(args[2]);
                if (arena == null) {
                    MessageUtil.send(sender, "arena_not_found");
                    return true;
                }
                if (doorManager == null) {
                    MessageUtil.send(sender, "door_unavailable");
                    return true;
                }
                String targetDoor = args[3].toLowerCase();
                if (targetDoor.equals("all")) {
                    for (ArenaDoor door : arena.getDoors()) {
                        if (sub.equals("open")) {
                            doorManager.openDoor(arena, door);
                        } else {
                            doorManager.closeDoor(arena, door);
                        }
                    }
                    MessageUtil.send(sender, sub.equals("open") ? "door_opened_all" : "door_closed_all",
                        Placeholder.unparsed("arena", arena.getId()));
                    return true;
                }
                ArenaDoor door = arena.getDoor(targetDoor);
                if (door == null) {
                    MessageUtil.send(sender, "door_not_found");
                    return true;
                }
                if (sub.equals("open")) {
                    doorManager.openDoor(arena, door);
                    MessageUtil.send(sender, "door_opened", Placeholder.unparsed("door", door.getId()));
                } else {
                    doorManager.closeDoor(arena, door);
                    MessageUtil.send(sender, "door_closed", Placeholder.unparsed("door", door.getId()));
                }
                return true;
            }

            MessageUtil.send(sender, "door_usage");
            return true;
        }

        if (args[0].equalsIgnoreCase("create")) {
            if (!(sender instanceof Player player)) {
                MessageUtil.send(sender, "only_player");
                return true;
            }
            if (!player.hasPermission("pvparena.admin")) {
                MessageUtil.send(player, "no_permission");
                return true;
            }
            if (args.length < 2) {
                MessageUtil.send(sender, "arena_create_usage");
                return true;
            }
            if (selectionManager.getPos1(player.getUniqueId()) == null
                    || selectionManager.getPos2(player.getUniqueId()) == null) {
                MessageUtil.send(sender, "selection_required");
                return true;
            }
            Location p1 = selectionManager.getPos1(player.getUniqueId());
            Location p2 = selectionManager.getPos2(player.getUniqueId());
            if (!p1.getWorld().getName().equalsIgnoreCase(p2.getWorld().getName())) {
                MessageUtil.send(sender, "selection_world_mismatch");
                return true;
            }
            String id = args[1];
            boolean exists = arenaManager.getArena(id) != null;
            if (!exists && arenaManager.getArenas().size() >= 3) {
                MessageUtil.send(sender, "arena_full");
                return true;
            }
            JavaPlugin plugin = JavaPlugin.getProvidingPlugin(getClass());
            SchedulerUtil.runOnPlayer(plugin, player, () -> {
                World world = p1.getWorld();
                Arena arena = arenaManager.createArena(id, world, player.getLocation());
                double minx = Math.min(p1.getX(), p2.getX());
                double miny = Math.min(p1.getY(), p2.getY());
                double minz = Math.min(p1.getZ(), p2.getZ());
                double maxx = Math.max(p1.getX(), p2.getX());
                double maxy = Math.max(p1.getY(), p2.getY());
                double maxz = Math.max(p1.getZ(), p2.getZ());
                arena.setMinBound(new Location(world, minx, miny, minz));
                arena.setMaxBound(new Location(world, maxx, maxy, maxz));
                arenaManager.saveArena(arena);
                MessageUtil.send(player, "arena_created", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("arena", arena.getId()));
            });
            return true;
        }
        if (args[0].equalsIgnoreCase("setspawn")) {
            if (!(sender instanceof Player player)) {
                MessageUtil.send(sender, "only_player");
                return true;
            }
            if (!player.hasPermission("pvparena.admin")) {
                MessageUtil.send(player, "no_permission");
                return true;
            }
            if (args.length < 3) {
                MessageUtil.send(sender, "pvparena_setspawn_usage");
                return true;
            }
            String id = args[1];
            int index = Integer.parseInt(args[2]);
            Arena arena = arenaManager.getArena(id);
            if (arena == null) {
                MessageUtil.send(player, "arena_not_found");
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
                MessageUtil.send(player, "arena_spawn_set", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("index", String.valueOf(index)));
            });
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("pvparena.admin")) {
                MessageUtil.send(sender, "no_permission");
                return true;
            }
            JavaPlugin plugin = JavaPlugin.getProvidingPlugin(getClass());
            if (plugin instanceof com.pvparena.PvPArenaPlugin arenaPlugin) {
                arenaPlugin.reloadAll();
                MessageUtil.send(sender, "reloaded");
                return true;
            }
        }

        if (args[0].equalsIgnoreCase("tool")) {
            if (!(sender instanceof Player player)) {
                MessageUtil.send(sender, "only_player");
                return true;
            }
            if (!player.hasPermission("pvparena.admin")) {
                MessageUtil.send(player, "no_permission");
                return true;
            }

            org.bukkit.inventory.ItemStack tool = SelectionToolUtil.createTool(plugin, settings);
            java.util.Map<Integer, org.bukkit.inventory.ItemStack> leftover = player.getInventory().addItem(tool);
            if (!leftover.isEmpty()) {
                leftover.values().forEach(it -> player.getWorld().dropItemNaturally(player.getLocation(), it));
            }

            MessageUtil.send(player, "selection_tool_given",
                    Placeholder.unparsed("tool", settings.getSelectionTool().name()));
            return true;
        }

        if (args[0].equalsIgnoreCase("specgui") || args[0].equalsIgnoreCase("spectatorgui")) {
            if (!(sender instanceof Player player)) {
                MessageUtil.send(sender, "only_player");
                return true;
            }
            if (!player.hasPermission("pvparena.admin")) {
                MessageUtil.send(sender, "no_permission");
                return true;
            }
            SchedulerUtil.runOnPlayer(plugin, player, () -> spectatorAdminMenu.open(player));
            return true;
        }

        if (args[0].equalsIgnoreCase("delkit")) {
            if (!sender.hasPermission("pvparena.admin")) {
                MessageUtil.send(sender, "no_permission");
                return true;
            }
            if (args.length < 2) {
                MessageUtil.send(sender, "kit_del_usage");
                return true;
            }
            String id = args[1];
            if (kitManager.deleteKit(id)) {
                MessageUtil.send(sender, "kit_deleted", Placeholder.unparsed("kit", id));
            } else {
                MessageUtil.send(sender, "kit_not_found");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("delarena")) {
            if (!sender.hasPermission("pvparena.admin")) {
                MessageUtil.send(sender, "no_permission");
                return true;
            }
            if (args.length < 2) {
                MessageUtil.send(sender, "arena_del_usage");
                return true;
            }
            String id = args[1];
            if (arenaManager.deleteArena(id)) {
                MessageUtil.send(sender, "arena_deleted", Placeholder.unparsed("arena", id));
            } else {
                MessageUtil.send(sender, "arena_not_found");
            }
            return true;
        }
        MessageUtil.send(sender, "unknown_subcommand");
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        if (!sender.hasPermission("pvparena.admin")) {
            MessageUtil.send(sender, "no_permission");
            return;
        }
        sender.sendMessage(Component.text(MessageUtil.getRaw("admin_help_title"), TITLE_COLOR));
        sender.sendMessage(helpLine(label, "help", MessageUtil.getRaw("help_show")));
        sender.sendMessage(helpLine(label, "kit list", MessageUtil.getRaw("help_kit_list")));
        sender.sendMessage(helpLine(label, "kit open <id>", MessageUtil.getRaw("help_kit_open")));
        sender.sendMessage(helpLine(label, "addkit <id>", MessageUtil.getRaw("help_addkit")));
        sender.sendMessage(helpLine(label, "delkit <id>", MessageUtil.getRaw("help_delkit")));
        sender.sendMessage(helpLine(label, "arena list", MessageUtil.getRaw("help_arena_list")));
        sender.sendMessage(helpLine(label, "create <id>", MessageUtil.getRaw("help_create")));
        sender.sendMessage(helpLine(label, "delarena <id>", MessageUtil.getRaw("help_delarena")));
        sender.sendMessage(helpLine(label, "setspawn <id> <1|2>", MessageUtil.getRaw("help_setspawn")));
        sender.sendMessage(helpLine(label, "door <list|create|delete|setdelay|setevict|setanim|setdistance|open|close> ...", MessageUtil.getRaw("help_door")));
        sender.sendMessage(helpLine(label, "tool", MessageUtil.getRaw("help_tool")));
        sender.sendMessage(helpLine(label, "specgui", MessageUtil.getRaw("help_specgui")));
        sender.sendMessage(helpLine(label, "reload", MessageUtil.getRaw("help_reload")));
    }

    private Component helpLine(String label, String usage, String description) {
        return Component.text('/' + label + ' ' + usage, CMD_COLOR)
                .append(Component.text("  - " + description, DESC_COLOR));
    }

    private boolean isArmor(ItemStack item) {
        if (item == null) {
            return false;
        }
        String name = item.getType().name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
    }

    private String formatLocation(Location loc) {
        return String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ());
    }
}
