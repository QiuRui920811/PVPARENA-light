package com.pvparena.gui;

import com.pvparena.util.GuiTextUtil;
import com.pvparena.util.MessageUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class SpectatorAdminMenu {
    public static final String TITLE_KEY = "spectator_admin_title";

    private final JavaPlugin plugin;
    private final NamespacedKey settingKey;

    public SpectatorAdminMenu(JavaPlugin plugin) {
        this.plugin = plugin;
        this.settingKey = new NamespacedKey(plugin, "spectator_setting");
    }

    public void open(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, getPlainTitle());
        FileConfiguration config = plugin.getConfig();

        inventory.setItem(10, createToggleItem("enabled", config.getBoolean("spectator.enabled", true), Material.ENDER_EYE));
        inventory.setItem(12, createToggleItem("vanish", config.getBoolean("spectator.vanish", true), Material.GLASS));
        inventory.setItem(14, createToggleItem("hide-from-players", config.getBoolean("spectator.hide-from-players", true), Material.PLAYER_HEAD));
        inventory.setItem(16, createToggleItem("collidable", config.getBoolean("spectator.collidable", false), Material.SLIME_BLOCK));
        inventory.setItem(22, createToggleItem("can-be-targeted", config.getBoolean("spectator.can-be-targeted", false), Material.TARGET));

        player.openInventory(inventory);
    }

    public String getPlainTitle() {
        return MessageUtil.getPlain("ui." + TITLE_KEY);
    }

    public String readSettingKey(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(settingKey, PersistentDataType.STRING);
    }

    public String toConfigPath(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return "spectator." + key;
    }

    private ItemStack createToggleItem(String key, boolean enabled, Material icon) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(GuiTextUtil.noItalic(MessageUtil.message(
            "spectator_setting_label_" + key.replace('-', '_'))));

        String state = MessageUtil.getRaw(enabled ? "spectator_state_on" : "spectator_state_off");
        meta.lore(GuiTextUtil.noItalic(List.of(
                MessageUtil.message("spectator_setting_value", Placeholder.unparsed("value", state)),
                MessageUtil.message("spectator_click_toggle")
        )));

        meta.getPersistentDataContainer().set(settingKey, PersistentDataType.STRING, key);
        item.setItemMeta(meta);
        return item;
    }
}
