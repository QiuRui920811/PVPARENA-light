package com.pvparena.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;

public final class GuiTextUtil {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private GuiTextUtil() {
    }

    public static Component deserialize(String input) {
        if (input == null) {
            return Component.empty();
        }
        String value = input;
        if (looksLikeMiniMessage(value)) {
            try {
                return MINI_MESSAGE.deserialize(value);
            } catch (Throwable ignored) {
            }
        }
        return LEGACY_AMPERSAND.deserialize(value.replace('§', '&'));
    }

    private static boolean looksLikeMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        return input.contains("<#")
                || input.contains("</")
                || input.matches(".*<[a-zA-Z][a-zA-Z0-9_:-]*>.*");
    }

    public static Component noItalic(Component component) {
        if (component == null) {
            return Component.empty().decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        }
        return component.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> noItalic(List<Component> components) {
        List<Component> result = new ArrayList<>();
        if (components == null) {
            return result;
        }
        for (Component component : components) {
            result.add(noItalic(component));
        }
        return result;
    }
}
