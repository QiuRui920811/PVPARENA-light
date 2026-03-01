package com.pvparena.gui;

import com.pvparena.manager.MatchManager;
import com.pvparena.model.MatchSession;
import com.pvparena.util.GuiTextUtil;
import com.pvparena.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SpectatorBrowseMenu {
    public static final String TITLE_KEY = "spectator_browser_title";

    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private final MatchManager matchManager;
    private final NamespacedKey actionKey;
    private final NamespacedKey targetKey;

    public SpectatorBrowseMenu(JavaPlugin plugin, MatchManager matchManager) {
        this.matchManager = matchManager;
        this.actionKey = new NamespacedKey(plugin, "spectator_browser_action");
        this.targetKey = new NamespacedKey(plugin, "spectator_browser_target");
    }

    public void open(Player viewer, int page) {
        Inventory inventory = Bukkit.createInventory(null, 54, getPlainTitle());
        List<TargetEntry> entries = buildEntries(viewer);
        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) CONTENT_SLOTS.length));
        int currentPage = Math.max(0, Math.min(page, totalPages - 1));
        int start = currentPage * CONTENT_SLOTS.length;

        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int idx = start + i;
            if (idx >= entries.size()) {
                break;
            }
            inventory.setItem(CONTENT_SLOTS[i], createTargetItem(entries.get(idx)));
        }

        if (entries.isEmpty()) {
            ItemStack empty = new ItemStack(Material.BARRIER);
            ItemMeta meta = empty.getItemMeta();
            meta.displayName(GuiTextUtil.noItalic(MessageUtil.message("spectator_no_matches")));
            empty.setItemMeta(meta);
            inventory.setItem(22, empty);
        }

        if (currentPage > 0) {
            inventory.setItem(45, createNavItem(Material.ARROW, "prev", currentPage - 1));
        }
        if (currentPage + 1 < totalPages) {
            inventory.setItem(53, createNavItem(Material.ARROW, "next", currentPage + 1));
        }

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.displayName(GuiTextUtil.noItalic(MessageUtil.message("spectator_browser_close")));
        closeMeta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "close");
        close.setItemMeta(closeMeta);
        inventory.setItem(49, close);

        viewer.openInventory(inventory);
    }

    public String getPlainTitle() {
        return MessageUtil.getPlain("ui." + TITLE_KEY);
    }

    public String readAction(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    public UUID readTarget(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private ItemStack createTargetItem(TargetEntry entry) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.targetId()));
        meta.displayName(GuiTextUtil.noItalic(MessageUtil.message("spectator_browser_item_title",
                Placeholder.unparsed("target", entry.targetName()))));

        List<Component> lore = new ArrayList<>();
        lore.add(MessageUtil.message("spectator_browser_item_mode", Placeholder.unparsed("mode", entry.modeName())));
        lore.add(MessageUtil.message("spectator_browser_item_opponent", Placeholder.unparsed("opponent", entry.opponentName())));
        lore.add(MessageUtil.message("spectator_browser_item_arena", Placeholder.unparsed("arena", entry.arenaId())));
        lore.add(MessageUtil.message("spectator_browser_item_state", Placeholder.unparsed("state", entry.state())));
        lore.add(MessageUtil.message("spectator_browser_click"));
        meta.lore(GuiTextUtil.noItalic(lore));

        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "spectate");
        meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, entry.targetId().toString());
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack createNavItem(Material icon, String action, int page) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        if ("prev".equals(action)) {
            meta.displayName(GuiTextUtil.noItalic(MessageUtil.message("spectator_browser_prev")));
        } else {
            meta.displayName(GuiTextUtil.noItalic(MessageUtil.message("spectator_browser_next")));
        }
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action + ":" + page);
        item.setItemMeta(meta);
        return item;
    }

    private List<TargetEntry> buildEntries(Player viewer) {
        List<TargetEntry> entries = new ArrayList<>();
        UUID viewerId = viewer.getUniqueId();
        for (MatchSession session : matchManager.getActiveMatchSessions()) {
            Player a = viewer.getServer().getPlayer(session.getPlayerA());
            Player b = viewer.getServer().getPlayer(session.getPlayerB());
            if (a == null || b == null || !a.isOnline() || !b.isOnline()) {
                continue;
            }
            if (!a.getUniqueId().equals(viewerId)) {
                entries.add(new TargetEntry(
                        a.getUniqueId(),
                        a.getName(),
                        b.getName(),
                        session.getMode().getDisplayName(),
                        session.getArena().getId(),
                        session.getState().name()
                ));
            }
            if (!b.getUniqueId().equals(viewerId)) {
                entries.add(new TargetEntry(
                        b.getUniqueId(),
                        b.getName(),
                        a.getName(),
                        session.getMode().getDisplayName(),
                        session.getArena().getId(),
                        session.getState().name()
                ));
            }
        }
        return entries;
    }

    private record TargetEntry(UUID targetId, String targetName, String opponentName,
                               String modeName, String arenaId, String state) {
    }
}
