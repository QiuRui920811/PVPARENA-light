package com.pvparena.config;

import org.bukkit.plugin.java.JavaPlugin;

public class ModesConfig extends YamlFile {
    public ModesConfig(JavaPlugin plugin, String fileName) {
        super(plugin, fileName);
    }

    @Override
    protected boolean shouldApplyDefaults() {
        return false;
    }
}
