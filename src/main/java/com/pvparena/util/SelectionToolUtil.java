package com.pvparena.util;

import com.pvparena.config.PluginSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class SelectionToolUtil {
    private static final String TOOL_PDC_KEY = "selection_tool";

    private SelectionToolUtil() {
    }

    public static ItemStack createTool(JavaPlugin plugin, PluginSettings settings) {
        Material material = settings.getSelectionTool();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        String name = settings.getSelectionToolDisplayName();
        if (name != null && !name.isBlank()) {
            meta.displayName(parseLegacy(name));
        }

        List<String> loreLines = settings.getSelectionToolLore();
        if (loreLines != null && !loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(parseLegacy(line));
            }
            meta.lore(lore);
        }

        if (settings.isSelectionToolGlow()) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        NamespacedKey key = new NamespacedKey(plugin, TOOL_PDC_KEY);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isSelectionTool(JavaPlugin plugin, PluginSettings settings, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (item.getType() != settings.getSelectionTool()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        NamespacedKey key = new NamespacedKey(plugin, TOOL_PDC_KEY);
        Byte marker = meta.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private static Component parseLegacy(String input) {
        return LegacyComponentSerializer.legacySection().deserialize(input.replace('&', '§'));
    }
}
