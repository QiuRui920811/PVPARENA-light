package com.pvparena.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ItemUtil {
    public static List<ItemStack> parseItemList(List<Map<?, ?>> list) {
        List<ItemStack> items = new ArrayList<>();
        if (list == null) {
            return items;
        }
        for (Map<?, ?> map : list) {
            ItemStack stack = parseItem(map);
            if (stack != null) {
                items.add(stack);
            }
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    public static ItemStack parseItem(Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        Object typeObj = map.get("type");
        if (typeObj == null) {
            return null;
        }
        Material type = Material.matchMaterial(typeObj.toString());
        if (type == null) {
            return null;
        }
        int amount = 1;
        Object amountObj = map.get("amount");
        if (amountObj != null) {
            amount = Integer.parseInt(amountObj.toString());
        }
        ItemStack stack = new ItemStack(type, amount);
        ItemMeta meta = stack.getItemMeta();
        Object enchantsObj = map.get("enchants");
        if (enchantsObj instanceof Map<?, ?> enchants) {
            for (Map.Entry<?, ?> entry : enchants.entrySet()) {
                Enchantment enchantment = getEnchantment(entry.getKey().toString());
                if (enchantment != null) {
                    int level = Integer.parseInt(entry.getValue().toString());
                    meta.addEnchant(enchantment, level, true);
                }
            }
        }
        Object potionObj = map.get("potion");
        if (potionObj instanceof Map<?, ?> potionMap && meta instanceof PotionMeta potionMeta) {
            Object potionTypeObj = potionMap.get("type");
            if (potionTypeObj != null) {
                PotionType potionType = PotionType.valueOf(potionTypeObj.toString());
                potionMeta.setBasePotionType(potionType);
            }
            meta = potionMeta;
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public static Enchantment getEnchantment(String name) {
        Enchantment enchantment = Enchantment.getByName(name.toUpperCase());
        if (enchantment != null) {
            return enchantment;
        }
        return Enchantment.getByKey(NamespacedKey.minecraft(name.toLowerCase()));
    }
}
