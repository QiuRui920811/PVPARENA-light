package com.pvparena.listener;

import com.pvparena.gui.ResultMenu;
import com.pvparena.manager.MessageManager;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public class ResultMenuListener implements Listener {
    private final ResultMenu resultMenu;

    public ResultMenuListener(ResultMenu resultMenu) {
        this.resultMenu = resultMenu;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.equals(resultMenu.getPlainTitle())) {
            return;
        }
        event.setCancelled(true);
    }
}
