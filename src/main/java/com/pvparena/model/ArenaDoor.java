package com.pvparena.model;

import org.bukkit.Location;

public class ArenaDoor {
    private final String id;
    private Location minBound;
    private Location maxBound;
    private int closeDelaySeconds = 8;
    private boolean evictOnClose = true;
    private String animationType = "instant";
    private int animationDistance = 3;
    private String swingDirection = "auto";

    public ArenaDoor(String id) {
        this.id = id == null ? "door" : id.toLowerCase();
    }

    public String getId() {
        return id;
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

    public int getCloseDelaySeconds() {
        return closeDelaySeconds;
    }

    public void setCloseDelaySeconds(int closeDelaySeconds) {
        this.closeDelaySeconds = Math.max(0, closeDelaySeconds);
    }

    public boolean isEvictOnClose() {
        return evictOnClose;
    }

    public void setEvictOnClose(boolean evictOnClose) {
        this.evictOnClose = evictOnClose;
    }

    public String getAnimationType() {
        return animationType;
    }

    public void setAnimationType(String animationType) {
        if (animationType == null || animationType.isBlank()) {
            this.animationType = "instant";
            return;
        }
        this.animationType = animationType.toLowerCase();
    }

    public int getAnimationDistance() {
        return animationDistance;
    }

    public void setAnimationDistance(int animationDistance) {
        this.animationDistance = Math.max(1, animationDistance);
    }

    public String getSwingDirection() {
        return swingDirection;
    }

    public boolean setSwingDirection(String swingDirection) {
        String normalized = normalizeSwingDirection(swingDirection);
        if (normalized == null) {
            return false;
        }
        this.swingDirection = normalized;
        return true;
    }

    public static String normalizeSwingDirection(String value) {
        if (value == null || value.isBlank()) {
            return "auto";
        }
        String v = value.toLowerCase();
        if (v.equals("auto")) {
            return "auto";
        }
        if (v.equals("in") || v.equals("inward") || v.equals("inner") || v.equals("cw") || v.equals("clockwise")) {
            return "inward";
        }
        if (v.equals("out") || v.equals("outward") || v.equals("outer") || v.equals("ccw") || v.equals("counterclockwise")) {
            return "outward";
        }
        return null;
    }

    public boolean isReady() {
        return minBound != null && maxBound != null
            && minBound.getWorld() != null && maxBound.getWorld() != null;
    }

    public boolean contains(Location location) {
        if (!isReady() || location == null || location.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().getName().equalsIgnoreCase(minBound.getWorld().getName())) {
            return false;
        }
        double minX = Math.min(minBound.getX(), maxBound.getX());
        double minY = Math.min(minBound.getY(), maxBound.getY());
        double minZ = Math.min(minBound.getZ(), maxBound.getZ());
        double maxX = Math.max(minBound.getX(), maxBound.getX());
        double maxY = Math.max(minBound.getY(), maxBound.getY());
        double maxZ = Math.max(minBound.getZ(), maxBound.getZ());
        return location.getX() >= minX && location.getX() <= maxX
            && location.getY() >= minY && location.getY() <= maxY
            && location.getZ() >= minZ && location.getZ() <= maxZ;
    }
}
