package com.pvparena.model;

import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.List;

public class ModeKit {
    private final List<ItemStack> items;
    private final List<ItemStack> armor;
    private final List<PotionEffect> potionEffects;

    public ModeKit(List<ItemStack> items, List<ItemStack> armor, List<PotionEffect> potionEffects) {
        this.items = items;
        this.armor = armor;
        this.potionEffects = potionEffects;
    }

    public List<ItemStack> getItems() {
        return items;
    }

    public List<ItemStack> getArmor() {
        return armor;
    }

    public List<PotionEffect> getPotionEffects() {
        return potionEffects;
    }
}
