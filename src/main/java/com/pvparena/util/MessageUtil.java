package com.pvparena.util;

import com.pvparena.manager.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MessageUtil {
    private static MessageManager messageManager;

    public static void init(MessageManager manager) {
        messageManager = manager;
    }

    public static void send(Player player, String messageKey, TagResolver... resolvers) {
        if (messageManager == null) {
            player.sendMessage(messageKey);
            return;
        }
        Component prefix = messageManager.getPrefix();
        Component msg = messageManager.getMessage(messageKey, resolvers);
        String plain = PlainTextComponentSerializer.plainText().serialize(msg);
        if (plain == null || plain.trim().isEmpty()) {
            return;
        }
        player.sendMessage(prefix.append(msg));
    }

    public static void send(CommandSender sender, String messageKey, TagResolver... resolvers) {
        if (sender instanceof Player player) {
            send(player, messageKey, resolvers);
        } else {
            sender.sendMessage(getRaw(messageKey));
        }
    }

    public static Component message(String messageKey, TagResolver... resolvers) {
        if (messageManager == null) {
            return Component.text(messageKey);
        }
        return messageManager.getMessage(messageKey, resolvers);
    }

    public static void send(Player player, Component component) {
        if (messageManager == null) {
            player.sendMessage(component);
            return;
        }
        player.sendMessage(messageManager.getPrefix().append(component));
    }

    public static String getPlain(String key) {
        if (messageManager == null) {
            return key;
        }
        return messageManager.getPlain("messages." + key);
    }

    public static String getRaw(String key) {
        return getPlain(key);
    }

    public static String getPlainMessage(String messageKey, TagResolver... resolvers) {
        if (messageManager == null) {
            return messageKey;
        }
        return PlainTextComponentSerializer.plainText().serialize(messageManager.getMessage(messageKey, resolvers));
    }
}
