package com.pvparena.config;

import org.bukkit.plugin.java.JavaPlugin;

public class ArenasConfig extends YamlFile {
    public ArenasConfig(JavaPlugin plugin, String fileName) {
        super(plugin, fileName);
    }

    @Override
    protected boolean shouldApplyDefaults() {
        return false;
    }
}
