package com.pvparena.model;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CombatSnapshot {
    private final ItemStack[] contents;
    private final ItemStack[] armor;
    private final List<PotionEffect> effects;
    private final double maxHealth;

    public CombatSnapshot(Player player) {
        ItemStack[] raw = player.getInventory().getContents();
        ItemStack[] filtered = new ItemStack[raw.length];
        for (int i = 0; i < raw.length; i++) {
            ItemStack item = raw[i];
            if (item == null) {
                continue;
            }
            String name = item.getType().name();
            if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                    || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")) {
                continue;
            }
            filtered[i] = item;
        }
        this.contents = filtered;
        this.armor = player.getInventory().getArmorContents();
        this.effects = new ArrayList<>(player.getActivePotionEffects());
        this.maxHealth = player.getMaxHealth();
    }

    public ItemStack[] getContents() {
        return contents;
    }

    public ItemStack[] getArmor() {
        return armor;
    }

    public List<PotionEffect> getEffects() {
        return effects;
    }

    public double getMaxHealth() {
        return maxHealth;
    }
}
