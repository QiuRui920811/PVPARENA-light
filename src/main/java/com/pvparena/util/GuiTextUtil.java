package com.pvparena.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

public final class GuiTextUtil {
    private GuiTextUtil() {
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
