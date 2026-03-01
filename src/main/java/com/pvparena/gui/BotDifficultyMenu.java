package com.pvparena.gui;

import com.pvparena.manager.MessageManager;
import com.pvparena.model.BotDifficulty;
import com.pvparena.util.GuiTextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BotDifficultyMenu {
    public static final String TITLE_KEY = "bot_menu_title";
    private final MessageManager messageManager;
    private final NamespacedKey difficultyKey;
    private final Map<UUID, String> pendingMode = new ConcurrentHashMap<>();

    public BotDifficultyMenu(JavaPlugin plugin, MessageManager messageManager) {
        this.messageManager = messageManager;
        this.difficultyKey = new NamespacedKey(plugin, "bot_difficulty");
    }

    public void open(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, messageManager.getUi(TITLE_KEY));
        inventory.setItem(11, createItem(Material.LIME_WOOL, "bot_difficulty_easy", BotDifficulty.EASY));
        inventory.setItem(13, createItem(Material.YELLOW_WOOL, "bot_difficulty_normal", BotDifficulty.NORMAL));
        inventory.setItem(15, createItem(Material.RED_WOOL, "bot_difficulty_hard", BotDifficulty.HARD));
        player.openInventory(inventory);
    }

    public void setPendingMode(UUID playerId, String modeId) {
        if (playerId == null || modeId == null || modeId.isEmpty()) {
            return;
        }
        pendingMode.put(playerId, modeId);
    }

    public String consumePendingMode(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        return pendingMode.remove(playerId);
    }

    private ItemStack createItem(Material material, String uiKey, BotDifficulty difficulty) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        Component name = messageManager.getUi(uiKey);
        meta.displayName(GuiTextUtil.noItalic(name != null ? name : Component.text(difficulty.name(), NamedTextColor.AQUA)));
        meta.getPersistentDataContainer().set(difficultyKey, PersistentDataType.STRING, difficulty.getId());
        item.setItemMeta(meta);
        return item;
    }

    public String getPlainTitle() {
        return messageManager.getPlain("ui." + TITLE_KEY);
    }

    public NamespacedKey getDifficultyKey() {
        return difficultyKey;
    }
}
