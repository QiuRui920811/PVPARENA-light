package com.pvparena.config;

import org.bukkit.plugin.java.JavaPlugin;

public class KitsConfig extends YamlFile {
    public KitsConfig(JavaPlugin plugin, String fileName) {
        super(plugin, fileName);
    }

    @Override
    protected boolean shouldApplyDefaults() {
        return false;
    }
}
