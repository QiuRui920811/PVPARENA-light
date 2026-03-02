package com.pvparena.manager;

import com.pvparena.config.ArenasConfig;
import com.pvparena.model.Arena;
import com.pvparena.model.ArenaDoor;
import com.pvparena.model.ArenaStatus;
import com.pvparena.model.Mode;
import com.pvparena.util.LocationUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ArenaManager {
    private final ArenasConfig arenasConfig;
    private final Map<String, Arena> arenas = new HashMap<>();
    private final Set<String> attemptedWorldLoads = new HashSet<>();

    public ArenaManager(ArenasConfig arenasConfig) {
        this.arenasConfig = arenasConfig;
        load();
    }

    public void load() {
        arenas.clear();
        attemptedWorldLoads.clear();
        ConfigurationSection section = arenasConfig.getConfig().getConfigurationSection("arenas");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection arenaSection = section.getConfigurationSection(id);
            if (arenaSection == null) {
                continue;
            }
            Arena arena = new Arena(id.toLowerCase());
            String worldName = arenaSection.getString("world");
            arena.setWorldName(worldName);
            arena.setSpawn1(LocationUtil.deserialize(arenaSection.getConfigurationSection("spawn1")));
            arena.setSpawn2(LocationUtil.deserialize(arenaSection.getConfigurationSection("spawn2")));
            arena.setMinBound(LocationUtil.deserialize(arenaSection.getConfigurationSection("bounds.min")));
            arena.setMaxBound(LocationUtil.deserialize(arenaSection.getConfigurationSection("bounds.max")));
            String duelMapIconRaw = arenaSection.getString("duel-map-icon", "");
            if (duelMapIconRaw != null && !duelMapIconRaw.isBlank()) {
                Material duelMapIcon = Material.matchMaterial(duelMapIconRaw.trim());
                if (duelMapIcon != null && !duelMapIcon.isAir()) {
                    arena.setDuelMapIcon(duelMapIcon);
                }
            }
            ConfigurationSection doorsSection = arenaSection.getConfigurationSection("doors");
            if (doorsSection != null) {
                for (String doorId : doorsSection.getKeys(false)) {
                    ConfigurationSection ds = doorsSection.getConfigurationSection(doorId);
                    if (ds == null) {
                        continue;
                    }
                    ArenaDoor door = new ArenaDoor(doorId.toLowerCase());
                    door.setMinBound(LocationUtil.deserialize(ds.getConfigurationSection("bounds.min")));
                    door.setMaxBound(LocationUtil.deserialize(ds.getConfigurationSection("bounds.max")));
                    door.setCloseDelaySeconds(ds.getInt("close-delay-seconds", 8));
                    door.setEvictOnClose(ds.getBoolean("evict-on-close", true));
                    door.setAnimationType(ds.getString("animation.type", "instant"));
                    door.setAnimationDistance(ds.getInt("animation.distance", 3));
                    door.setSwingDirection(ds.getString("animation.swing-direction", "auto"));
                    arena.upsertDoor(door);
                }
            }
            arena.setStatus(ArenaStatus.FREE);
            arenas.put(arena.getId(), arena);
        }
    }

    public Arena getArena(String id) {
        return arenas.get(id.toLowerCase());
    }

    public Arena getFreeArena() {
        for (Arena arena : arenas.values()) {
            if (isArenaUsable(arena)) {
                return arena;
            }
        }
        return null;
    }

    public Arena getFreeArena(Mode mode) {
        if (mode == null || !mode.hasArenaRestriction()) {
            return getFreeArena();
        }
        for (String arenaId : mode.getPreferredArenaIds()) {
            Arena arena = arenas.get(arenaId);
            if (isArenaUsable(arena)) {
                return arena;
            }
        }
        return null;
    }

    private boolean isArenaUsable(Arena arena) {
        if (arena == null || arena.getStatus() != ArenaStatus.FREE || !arena.isReady()) {
            return false;
        }
        World world = Bukkit.getWorld(arena.getWorldName());
        if (world == null && arena.getWorldName() != null && !arena.getWorldName().isEmpty()) {
            // Avoid repeated expensive createWorld attempts if the world isn't available yet.
            if (attemptedWorldLoads.add(arena.getWorldName().toLowerCase())) {
                world = Bukkit.createWorld(new WorldCreator(arena.getWorldName()));
            }
        }
        return world != null;
    }

    public Collection<Arena> getArenas() {
        return arenas.values();
    }

    public boolean deleteArena(String id) {
        String key = id.toLowerCase();
        Arena removed = arenas.remove(key);
        if (removed == null) {
            return false;
        }
        ConfigurationSection section = arenasConfig.getConfig().getConfigurationSection("arenas");
        if (section != null) {
            section.set(key, null);
            arenasConfig.save();
        }
        return true;
    }

    public Arena createArena(String id, World world, Location baseLocation) {
        Arena arena = new Arena(id.toLowerCase());
        arena.setWorldName(world.getName());
        arena.setSpawn1(baseLocation);
        arena.setSpawn2(baseLocation);
        arenas.put(arena.getId(), arena);
        saveArena(arena);
        return arena;
    }

    public void saveArena(Arena arena) {
        ConfigurationSection section = arenasConfig.getConfig().getConfigurationSection("arenas");
        if (section == null) {
            section = arenasConfig.getConfig().createSection("arenas");
        }
        ConfigurationSection arenaSection = section.getConfigurationSection(arena.getId());
        if (arenaSection == null) {
            arenaSection = section.createSection(arena.getId());
        }
        arenaSection.set("world", arena.getWorldName());
        if (arena.getSpawn1() != null) {
            arenaSection.createSection("spawn1", LocationUtil.serialize(arena.getSpawn1()));
        }
        if (arena.getSpawn2() != null) {
            arenaSection.createSection("spawn2", LocationUtil.serialize(arena.getSpawn2()));
        }
        if (arena.getMinBound() != null) {
            arenaSection.createSection("bounds.min", LocationUtil.serialize(arena.getMinBound()));
        }
        if (arena.getMaxBound() != null) {
            arenaSection.createSection("bounds.max", LocationUtil.serialize(arena.getMaxBound()));
        }
        if (arena.getDuelMapIcon() != null && !arena.getDuelMapIcon().isAir()) {
            arenaSection.set("duel-map-icon", arena.getDuelMapIcon().name());
        } else {
            arenaSection.set("duel-map-icon", null);
        }
        arenaSection.set("doors", null);
        ConfigurationSection doorsSection = arenaSection.createSection("doors");
        for (ArenaDoor door : arena.getDoors()) {
            ConfigurationSection ds = doorsSection.createSection(door.getId());
            if (door.getMinBound() != null) {
                ds.createSection("bounds.min", LocationUtil.serialize(door.getMinBound()));
            }
            if (door.getMaxBound() != null) {
                ds.createSection("bounds.max", LocationUtil.serialize(door.getMaxBound()));
            }
            ds.set("close-delay-seconds", door.getCloseDelaySeconds());
            ds.set("evict-on-close", door.isEvictOnClose());
            ds.set("animation.type", door.getAnimationType());
            ds.set("animation.distance", door.getAnimationDistance());
            ds.set("animation.swing-direction", door.getSwingDirection());
        }
        arenasConfig.save();
    }
}
