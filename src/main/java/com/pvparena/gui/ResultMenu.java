package com.pvparena.gui;

import com.pvparena.manager.MatchManager;
import com.pvparena.manager.MessageManager;
import com.pvparena.model.CombatSnapshot;
import com.pvparena.model.MatchResult;
import com.pvparena.util.GuiTextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

public class ResultMenu {
    public static final String TITLE_KEY = "result_title";

    private final MatchManager matchManager;
    private final MessageManager messageManager;

    public ResultMenu(MessageManager messageManager, MatchManager matchManager) {
        this.messageManager = messageManager;
        this.matchManager = matchManager;
    }

    public void open(Player player) {
        MatchResult result = matchManager.getLastResult(player);
        if (result == null) {
            com.pvparena.util.MessageUtil.send(player, "result_none");
            return;
        }
        Inventory inventory = Bukkit.createInventory(null, 54, messageManager.getUi(TITLE_KEY));

        ItemStack stats = new ItemStack(Material.BOOK);
        ItemMeta meta = stats.getItemMeta();
        meta.displayName(GuiTextUtil.noItalic(messageManager.getUi("result_stats_title")));
        List<Component> lore = new ArrayList<>();
        lore.add(messageManager.getUi("result_stats_opponent", Placeholder.unparsed("opponent", result.getOpponentName())));
        lore.add(messageManager.getUi("result_stats_mode", Placeholder.unparsed("mode", result.getModeName())));
        lore.add(messageManager.getUi("result_stats_dealt", Placeholder.unparsed("damage", String.format("%.1f", result.getDamageDealt()))));
        lore.add(messageManager.getUi("result_stats_taken", Placeholder.unparsed("damage", String.format("%.1f", result.getDamageTaken()))));
        lore.add(messageManager.getUi("result_stats_opponent_health",
                Placeholder.unparsed("health", String.format("%.1f", result.getOpponentHealth())),
                Placeholder.unparsed("max", String.format("%.1f", result.getOpponentMaxHealth()))));
        meta.lore(GuiTextUtil.noItalic(lore));
        stats.setItemMeta(meta);
        inventory.setItem(13, stats);

        CombatSnapshot snapshot = result.getOpponentSnapshot();
        if (snapshot != null) {
            ItemStack[] armor = snapshot.getArmor();
            if (armor != null) {
                if (armor.length > 0 && armor[3] != null) inventory.setItem(28, armor[3]);
                if (armor.length > 1 && armor[2] != null) inventory.setItem(29, armor[2]);
                if (armor.length > 2 && armor[1] != null) inventory.setItem(30, armor[1]);
                if (armor.length > 3 && armor[0] != null) inventory.setItem(31, armor[0]);
            }

            ItemStack[] contents = snapshot.getContents();
            int slot = 37;
            if (contents != null) {
                for (ItemStack item : contents) {
                    if (item == null || slot >= 45) {
                        continue;
                    }
                    inventory.setItem(slot, item.clone());
                    slot++;
                }
            }

            ItemStack effectsItem = new ItemStack(Material.POTION);
            ItemMeta effectsMeta = effectsItem.getItemMeta();
            effectsMeta.displayName(GuiTextUtil.noItalic(messageManager.getUi("result_effects_title")));
            List<Component> effectLore = new ArrayList<>();
            for (PotionEffect effect : snapshot.getEffects()) {
                effectLore.add(GuiTextUtil.noItalic(Component.text(effect.getType().getName() + " Lv." + (effect.getAmplifier() + 1))));
            }
            if (effectLore.isEmpty()) {
                effectLore.add(messageManager.getUi("result_effects_none"));
            }
            effectsMeta.lore(GuiTextUtil.noItalic(effectLore));
            effectsItem.setItemMeta(effectsMeta);
            inventory.setItem(16, effectsItem);
        }

        player.openInventory(inventory);
    }

    public String getPlainTitle() {
        return messageManager.getPlain("ui." + TITLE_KEY);
    }
}
