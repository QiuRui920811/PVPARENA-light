package com.pvparena.listener;

import com.pvparena.gui.DuelMenu;
import com.pvparena.manager.DuelManager;
import com.pvparena.util.MessageUtil;
import com.pvparena.util.SchedulerUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public class DuelMenuListener implements Listener {
    private final DuelMenu duelMenu;
    private final DuelManager duelManager;

    public DuelMenuListener(DuelMenu duelMenu, DuelManager duelManager) {
        this.duelMenu = duelMenu;
        this.duelManager = duelManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.equals(duelMenu.getPlainTitle())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getInventory().getHolder() instanceof DuelMenu.DuelMenuHolder holder)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }

        int step = event.getClick() == ClickType.RIGHT ? -1 : 1;
        int slot = event.getRawSlot();
        if (slot == duelMenu.getModeSlot()) {
            playButtonSound(player);
            duelMenu.cycleMode(holder, step);
            duelMenu.render(event.getView().getTopInventory(), holder);
            return;
        }
        if (slot == duelMenu.getMapSlot()) {
            playButtonSound(player);
            if (duelMenu.isMapLocked(holder)) {
                MessageUtil.send(player, "duel_map_locked",
                        Placeholder.unparsed("mode", duelMenu.getSelectedModeDisplayName(holder)));
                duelMenu.showMapLockedFeedback(holder);
                duelMenu.render(event.getView().getTopInventory(), holder);
                JavaPlugin plugin = JavaPlugin.getProvidingPlugin(getClass());
                SchedulerUtil.runOnPlayerLater(plugin, player, duelMenu.getMapLockedFeedbackTicks(), () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof DuelMenu.DuelMenuHolder openHolder)) {
                        return;
                    }
                    if (openHolder != holder) {
                        return;
                    }
                    duelMenu.clearMapLockedFeedback(holder);
                    duelMenu.render(player.getOpenInventory().getTopInventory(), holder);
                });
                return;
            }
            duelMenu.cycleArena(holder, step);
            duelMenu.render(event.getView().getTopInventory(), holder);
            return;
        }
        if (slot == duelMenu.getTimeSlot()) {
            playButtonSound(player);
            duelMenu.cycleTime(holder, step);
            duelMenu.render(event.getView().getTopInventory(), holder);
            return;
        }
        if (slot == duelMenu.getCancelSlot()) {
            playButtonSound(player);
            player.closeInventory();
            return;
        }
        if (slot != duelMenu.getSubmitSlot()) {
            return;
        }

        UUID targetId = holder.getTargetId();
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            MessageUtil.send(player, "duel_not_found");
            player.closeInventory();
            return;
        }
        String modeId = holder.getSelectedModeId();
        String arenaId = holder.getSelectedArenaId();
        int timeLimitSeconds = holder.getSelectedTimeSeconds();
        if (modeId == null || modeId.isBlank()) {
            MessageUtil.send(player, "mode_not_found");
            return;
        }
        playButtonSound(player);
        duelManager.request(player, target, modeId, arenaId, timeLimitSeconds);
        player.closeInventory();
    }

    private void playButtonSound(Player player) {
        if (player == null || !player.isOnline() || !duelMenu.isButtonSoundEnabled()) {
            return;
        }
        String sound = duelMenu.getButtonSound();
        if (sound == null || sound.isBlank()) {
            return;
        }
        try {
            player.playSound(player, sound, SoundCategory.MASTER,
                    duelMenu.getButtonSoundVolume(), duelMenu.getButtonSoundPitch());
        } catch (Throwable ignored) {
        }
    }
}
