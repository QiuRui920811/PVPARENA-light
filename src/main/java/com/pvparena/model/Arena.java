package com.pvparena.model;

import org.bukkit.Location;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class Arena {
    private final String id;
    private String worldName;
    private Location spawn1;
    private Location spawn2;
    private Location minBound;
    private Location maxBound;
    private ArenaStatus status;
    private final Map<String, ArenaDoor> doors = new LinkedHashMap<>();

    public Arena(String id) {
        this.id = id;
        this.status = ArenaStatus.FREE;
    }

    public String getId() {
        return id;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public Location getSpawn1() {
        return spawn1;
    }

    public void setSpawn1(Location spawn1) {
        this.spawn1 = spawn1;
    }

    public Location getSpawn2() {
        return spawn2;
    }

    public void setSpawn2(Location spawn2) {
        this.spawn2 = spawn2;
    }

    public Location getMinBound() {
        return minBound;
    }

    public void setMinBound(Location minBound) {
        this.minBound = minBound;
    }

    public Location getMaxBound() {
        return maxBound;
    }

    public void setMaxBound(Location maxBound) {
        this.maxBound = maxBound;
    }

    public ArenaStatus getStatus() {
        return status;
    }

    public void setStatus(ArenaStatus status) {
        this.status = status;
    }

    public boolean isReady() {
        return worldName != null && spawn1 != null && spawn2 != null;
    }

    public Collection<ArenaDoor> getDoors() {
        return doors.values();
    }

    public ArenaDoor getDoor(String doorId) {
        if (doorId == null) {
            return null;
        }
        return doors.get(doorId.toLowerCase());
    }

    public void upsertDoor(ArenaDoor door) {
        if (door == null) {
            return;
        }
        doors.put(door.getId().toLowerCase(), door);
    }

    public boolean removeDoor(String doorId) {
        if (doorId == null) {
            return false;
        }
        return doors.remove(doorId.toLowerCase()) != null;
    }

    public void clearDoors() {
        doors.clear();
    }

    public boolean isInsideBounds(Location location) {
        if (minBound == null || maxBound == null || location == null) {
            return true;
        }
        if (!location.getWorld().getName().equalsIgnoreCase(minBound.getWorld().getName())) {
            return true;
        }
        double minX = Math.min(minBound.getX(), maxBound.getX());
        double minY = Math.min(minBound.getY(), maxBound.getY());
        double minZ = Math.min(minBound.getZ(), maxBound.getZ());
        double maxX = Math.max(minBound.getX(), maxBound.getX());
        double maxY = Math.max(minBound.getY(), maxBound.getY());
        double maxZ = Math.max(minBound.getZ(), maxBound.getZ());
        double expand = 1.0;
        return location.getX() >= (minX - expand) && location.getX() <= (maxX + expand)
            && location.getY() >= (minY - expand) && location.getY() <= (maxY + expand)
            && location.getZ() >= (minZ - expand) && location.getZ() <= (maxZ + expand);
    }
}
