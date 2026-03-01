package com.pvparena.manager;

import com.pvparena.config.MessagesConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class MessageManager {
    private final MessagesConfig config;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public MessageManager(MessagesConfig config) {
        this.config = config;
    }

    public Component get(String key, TagResolver... resolvers) {
        String raw = config.getConfig().getString(key, "");
        if (raw == null) {
            raw = "";
        }
        // Allow simple {placeholder} -> <placeholder> conversion for MiniMessage
        if (raw.indexOf('{') >= 0) {
            raw = raw.replaceAll("\\{([a-zA-Z0-9_]+)\\}", "<$1>");
        }
        // Support legacy & / § color codes when no MiniMessage tags are used.
        if (raw.indexOf('<') < 0 && (raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0)) {
            return LegacyComponentSerializer.legacySection().deserialize(raw.replace('&', '§'));
        }
        return miniMessage.deserialize(raw, resolvers);
    }

    public Component getMessage(String key, TagResolver... resolvers) {
        return get("messages." + key, resolvers);
    }

    public Component getUi(String key, TagResolver... resolvers) {
        // Disable italics for GUI titles by default.
        return get("ui." + key, resolvers).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public String getPlain(String key) {
        return PlainTextComponentSerializer.plainText().serialize(get(key));
    }

    public Component getPrefix() {
        return get("prefix");
    }
}
