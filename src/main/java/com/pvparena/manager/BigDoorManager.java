package com.pvparena.manager;

import com.pvparena.model.Arena;
import com.pvparena.model.ArenaDoor;
import com.pvparena.util.MessageUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BigDoorManager {
    private final JavaPlugin plugin;
    private final Map<String, Map<String, LinkedHashMap<DoorBlockKey, BlockData>>> closedSnapshots = new HashMap<>();

    public BigDoorManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isDebugEnabled() {
        return plugin.getConfig().getBoolean("debug.bigdoor", false);
    }

    private void debug(String message) {
        if (!isDebugEnabled()) {
            return;
        }
        plugin.getLogger().info("[BigDoorDebug] " + message);
    }

    public void invalidateDoorSnapshot(String arenaId, String doorId) {
        if (arenaId == null || doorId == null) {
            return;
        }
        Map<String, LinkedHashMap<DoorBlockKey, BlockData>> arenaDoors = closedSnapshots.get(arenaId.toLowerCase());
        if (arenaDoors == null) {
            return;
        }
        arenaDoors.remove(doorId.toLowerCase());
        if (arenaDoors.isEmpty()) {
            closedSnapshots.remove(arenaId.toLowerCase());
        }
    }

    public void invalidateArenaSnapshots(String arenaId) {
        if (arenaId == null) {
            return;
        }
        closedSnapshots.remove(arenaId.toLowerCase());
    }

    public void clearAllSnapshots() {
        closedSnapshots.clear();
    }

    public void openArenaDoors(Arena arena) {
        if (arena == null || !plugin.getConfig().getBoolean("bigdoor.enabled", true)) {
            return;
        }
        World world = plugin.getServer().getWorld(arena.getWorldName());
        if (world == null) {
            debug("openArenaDoors skipped: world not loaded for arena=" + arena.getId() + " world=" + arena.getWorldName());
            return;
        }
        for (ArenaDoor door : arena.getDoors()) {
            if (door == null || !door.isReady()) {
                continue;
            }
            Location anchor = door.getMinBound() != null ? door.getMinBound() : arena.getSpawn1();
            if (anchor == null) {
                openDoor(arena, door);
                continue;
            }
            int chunkX = anchor.getBlockX() >> 4;
            int chunkZ = anchor.getBlockZ() >> 4;
            plugin.getServer().getRegionScheduler().run(plugin, world, chunkX, chunkZ, task -> openDoor(arena, door));
        }
    }

    public void closeArenaDoorsWithDelay(Arena arena) {
        if (arena == null || !plugin.getConfig().getBoolean("bigdoor.enabled", true)) {
            return;
        }
        for (ArenaDoor door : arena.getDoors()) {
            int delaySeconds = Math.max(0, door.getCloseDelaySeconds());
            long delayTicks = delaySeconds * 20L;
            World world = plugin.getServer().getWorld(arena.getWorldName());
            if (world == null) {
                continue;
            }
            Location anchor = door.getMinBound() != null ? door.getMinBound() : arena.getSpawn1();
            if (anchor == null) {
                continue;
            }
            int chunkX = anchor.getBlockX() >> 4;
            int chunkZ = anchor.getBlockZ() >> 4;
            plugin.getServer().getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, task -> {
                if (door.isEvictOnClose()) {
                    evictPlayersInsideDoor(arena, door);
                }
                closeDoor(arena, door);
            }, delayTicks);
        }
    }

    public void openDoor(Arena arena, ArenaDoor door) {
        if (arena == null || door == null || !door.isReady()) {
            debug("openDoor skipped (invalid arena/door/ready)");
            return;
        }
        World world = plugin.getServer().getWorld(arena.getWorldName());
        if (world == null) {
            debug("openDoor skipped: world not loaded for arena=" + arena.getId() + " world=" + arena.getWorldName());
            return;
        }

        LinkedHashMap<DoorBlockKey, BlockData> snapshot = getOrCreateSnapshot(arena, door);
        if (snapshot.isEmpty()) {
            captureClosedSnapshot(world, door, snapshot);
        }
        int snapshotSolid = countNonAir(snapshot);
        if (snapshotSolid == 0) {
            int liveSolid = countNonAirInArea(world, door);
            if (liveSolid > 0) {
                debug("snapshot had 0 solid blocks, recapturing from live area solid=" + liveSolid);
                captureClosedSnapshotOverwrite(world, door, snapshot);
                snapshotSolid = countNonAir(snapshot);
            }
        }
        debug("openDoor arena=" + arena.getId()
            + " door=" + door.getId()
            + " anim=" + door.getAnimationType()
            + " dist=" + door.getAnimationDistance()
            + " blocks=" + snapshot.size()
            + " solid=" + snapshotSolid);
        if ("lift".equalsIgnoreCase(door.getAnimationType())) {
            animateLiftOpen(world, snapshot, door.getAnimationDistance());
            return;
        }
        if ("swing".equalsIgnoreCase(door.getAnimationType()) || "bigdoor".equalsIgnoreCase(door.getAnimationType())) {
            animateSwingOpen(world, door, snapshot, door.getAnimationDistance());
            return;
        }
        applyArea(world, door, Material.AIR);
    }

    public void closeDoor(Arena arena, ArenaDoor door) {
        if (arena == null || door == null || !door.isReady()) {
            debug("closeDoor skipped (invalid arena/door/ready)");
            return;
        }
        World world = plugin.getServer().getWorld(arena.getWorldName());
        if (world == null) {
            debug("closeDoor skipped: world not loaded for arena=" + arena.getId() + " world=" + arena.getWorldName());
            return;
        }

        LinkedHashMap<DoorBlockKey, BlockData> snapshot = getOrCreateSnapshot(arena, door);
        if (snapshot.isEmpty()) {
            captureClosedSnapshot(world, door, snapshot);
        }
        int snapshotSolid = countNonAir(snapshot);
        if (snapshotSolid == 0) {
            int liveSolid = countNonAirInArea(world, door);
            if (liveSolid > 0) {
                debug("snapshot had 0 solid blocks on close, recapturing from live area solid=" + liveSolid);
                captureClosedSnapshotOverwrite(world, door, snapshot);
                snapshotSolid = countNonAir(snapshot);
            }
        }
        debug("closeDoor arena=" + arena.getId()
            + " door=" + door.getId()
            + " anim=" + door.getAnimationType()
            + " dist=" + door.getAnimationDistance()
            + " blocks=" + snapshot.size()
            + " solid=" + snapshotSolid);
        if ("lift".equalsIgnoreCase(door.getAnimationType())) {
            animateLiftClose(world, snapshot, door.getAnimationDistance());
            return;
        }
        if ("swing".equalsIgnoreCase(door.getAnimationType()) || "bigdoor".equalsIgnoreCase(door.getAnimationType())) {
            animateSwingClose(world, door, snapshot, door.getAnimationDistance());
            return;
        }
        applySnapshot(world, snapshot);
    }

    private long getAnimationStepTicks() {
        return Math.max(1L, plugin.getConfig().getLong("bigdoor.animation-step-ticks", 2L));
    }

    private boolean useSmoothLiftAnimation() {
        return plugin.getConfig().getBoolean("bigdoor.lift-smooth", true);
    }

    private int getSmoothLiftMaxBlocks() {
        return Math.max(64, plugin.getConfig().getInt("bigdoor.lift-smooth-max-blocks", 2048));
    }

    private boolean useSmoothSwingAnimation() {
        return plugin.getConfig().getBoolean("bigdoor.swing-smooth", true);
    }

    private int getSmoothSwingMaxBlocks() {
        return Math.max(64, plugin.getConfig().getInt("bigdoor.swing-smooth-max-blocks", 2048));
    }

    private long[] buildStepDelays(int steps) {
        int safeSteps = Math.max(1, steps);
        long[] delays = new long[safeSteps];

        int configuredTotal = plugin.getConfig().getInt("bigdoor.animation-total-ticks", 0);
        boolean easing = plugin.getConfig().getBoolean("bigdoor.animation-ease-in-out", true);
        if (configuredTotal > 0) {
            long total = Math.max(1L, configuredTotal);
            long prev = -1L;
            for (int i = 1; i <= safeSteps; i++) {
                double progress = (double) i / (double) safeSteps;
                double mapped = easing ? (1.0D - Math.cos(Math.PI * progress)) / 2.0D : progress;
                long at = Math.round(mapped * total);
                if (at <= prev) {
                    at = prev + 1L;
                }
                delays[i - 1] = Math.max(0L, at);
                prev = delays[i - 1];
            }
            return delays;
        }

        long stepTicks = getAnimationStepTicks();
        for (int i = 0; i < safeSteps; i++) {
            delays[i] = (long) i * stepTicks;
        }
        return delays;
    }

    private void animateLiftOpen(World world, LinkedHashMap<DoorBlockKey, BlockData> snapshot, int distance) {
        if (useSmoothLiftAnimation() && animateLiftOpenSmooth(world, snapshot, distance)) {
            return;
        }
        List<Map.Entry<DoorBlockKey, BlockData>> entries = new ArrayList<>();
        for (Map.Entry<DoorBlockKey, BlockData> entry : snapshot.entrySet()) {
            BlockData data = entry.getValue();
            if (data != null && data.getMaterial() != Material.AIR) {
                entries.add(entry);
            }
        }
        entries.sort((a, b) -> Integer.compare(b.getKey().y, a.getKey().y));
        if (entries.isEmpty()) {
            debug("animateLiftOpen skipped: no entries");
            return;
        }
        DoorBlockKey anchor = entries.get(0).getKey();
        int chunkX = anchor.x >> 4;
        int chunkZ = anchor.z >> 4;
        long stepTicks = getAnimationStepTicks();
        int steps = Math.max(1, distance);
        long[] delays = buildStepDelays(steps);
        debug("animateLiftOpen start entries=" + entries.size() + " distance=" + steps + " stepTicks=" + stepTicks + " totalTicks=" + delays[delays.length - 1]);

        for (int step = 1; step <= steps; step++) {
            final int currentStep = step;
            long delay = delays[currentStep - 1];
            runRegionTask(world, chunkX, chunkZ, delay, () -> {
                for (Map.Entry<DoorBlockKey, BlockData> entry : entries) {
                    DoorBlockKey key = entry.getKey();
                    BlockData data = entry.getValue();

                    int fromY = key.y + (currentStep - 1);
                    int toY = key.y + currentStep;
                    if (fromY >= world.getMinHeight() && fromY < world.getMaxHeight()) {
                        world.getBlockAt(key.x, fromY, key.z).setType(Material.AIR, false);
                    }
                    if (toY >= world.getMinHeight() && toY < world.getMaxHeight()) {
                        Block target = world.getBlockAt(key.x, toY, key.z);
                        target.setType(data.getMaterial(), false);
                        target.setBlockData(data, false);
                    }
                }
            });
        }
    }

    private void animateLiftClose(World world, LinkedHashMap<DoorBlockKey, BlockData> snapshot, int distance) {
        if (useSmoothLiftAnimation() && animateLiftCloseSmooth(world, snapshot, distance)) {
            return;
        }
        List<Map.Entry<DoorBlockKey, BlockData>> entries = new ArrayList<>();
        for (Map.Entry<DoorBlockKey, BlockData> entry : snapshot.entrySet()) {
            BlockData data = entry.getValue();
            if (data != null && data.getMaterial() != Material.AIR) {
                entries.add(entry);
            }
        }
        entries.sort((a, b) -> Integer.compare(a.getKey().y, b.getKey().y));
        if (entries.isEmpty()) {
            debug("animateLiftClose skipped: no entries");
            return;
        }
        int effectiveDistance = Math.max(1, distance);
        DoorBlockKey anchor = entries.get(0).getKey();
        int chunkX = anchor.x >> 4;
        int chunkZ = anchor.z >> 4;
        long stepTicks = getAnimationStepTicks();
        long[] delays = buildStepDelays(effectiveDistance);
        debug("animateLiftClose start entries=" + entries.size() + " distance=" + effectiveDistance + " stepTicks=" + stepTicks + " totalTicks=" + delays[delays.length - 1]);

        for (int step = effectiveDistance; step >= 1; step--) {
            final int currentStep = step;
            long delay = delays[effectiveDistance - step];
            runRegionTask(world, chunkX, chunkZ, delay, () -> {
                for (Map.Entry<DoorBlockKey, BlockData> entry : entries) {
                    DoorBlockKey key = entry.getKey();
                    BlockData data = entry.getValue();

                    int fromY = key.y + currentStep;
                    int toY = key.y + (currentStep - 1);
                    if (fromY >= world.getMinHeight() && fromY < world.getMaxHeight()) {
                        world.getBlockAt(key.x, fromY, key.z).setType(Material.AIR, false);
                    }
                    if (toY >= world.getMinHeight() && toY < world.getMaxHeight()) {
                        Block target = world.getBlockAt(key.x, toY, key.z);
                        target.setType(data.getMaterial(), false);
                        target.setBlockData(data, false);
                    }
                }
            });
        }
    }

    private boolean animateLiftOpenSmooth(World world, LinkedHashMap<DoorBlockKey, BlockData> snapshot, int distance) {
        List<Map.Entry<DoorBlockKey, BlockData>> entries = new ArrayList<>();
        for (Map.Entry<DoorBlockKey, BlockData> entry : snapshot.entrySet()) {
            BlockData data = entry.getValue();
            if (data != null && data.getMaterial() != Material.AIR) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            return true;
        }
        if (entries.size() > getSmoothLiftMaxBlocks()) {
            debug("animateLiftOpenSmooth fallback: too many blocks=" + entries.size());
            return false;
        }

        int effectiveDistance = Math.max(1, distance);
        long[] delays = buildStepDelays(effectiveDistance);
        int totalTicks = (int) Math.max(2L, delays[delays.length - 1]);
        double stepY = (double) effectiveDistance / (double) totalTicks;

        List<LiftFallingEntry> entities = new ArrayList<>();
        for (Map.Entry<DoorBlockKey, BlockData> entry : entries) {
            DoorBlockKey key = entry.getKey();
            BlockData data = entry.getValue();
            Location loc = new Location(world, key.x + 0.5D, key.y, key.z + 0.5D);
            FallingBlock falling = world.spawnFallingBlock(loc, data);
            falling.setGravity(false);
            falling.setDropItem(false);
            try {
                falling.setHurtEntities(false);
            } catch (Throwable ignored) {
            }
            entities.add(new LiftFallingEntry(key, data, falling, loc.getY()));
            if (key.y >= world.getMinHeight() && key.y < world.getMaxHeight()) {
                world.getBlockAt(key.x, key.y, key.z).setType(Material.AIR, false);
            }
        }

        DoorBlockKey anchor = entries.get(0).getKey();
        int chunkX = anchor.x >> 4;
        int chunkZ = anchor.z >> 4;
        runLiftFallingTick(world, chunkX, chunkZ, entities, 0, totalTicks, stepY, () -> {
            for (LiftFallingEntry entry : entities) {
                FallingBlock falling = entry.entity;
                if (falling != null && !falling.isDead()) {
                    falling.remove();
                }
                int toY = entry.key.y + effectiveDistance;
                if (toY < world.getMinHeight() || toY >= world.getMaxHeight()) {
                    continue;
                }
                Block target = world.getBlockAt(entry.key.x, toY, entry.key.z);
                target.setType(entry.data.getMaterial(), false);
                target.setBlockData(entry.data, false);
            }
        });
        return true;
    }

    private boolean animateLiftCloseSmooth(World world, LinkedHashMap<DoorBlockKey, BlockData> snapshot, int distance) {
        List<Map.Entry<DoorBlockKey, BlockData>> entries = new ArrayList<>();
        for (Map.Entry<DoorBlockKey, BlockData> entry : snapshot.entrySet()) {
            BlockData data = entry.getValue();
            if (data != null && data.getMaterial() != Material.AIR) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            return true;
        }
        if (entries.size() > getSmoothLiftMaxBlocks()) {
            debug("animateLiftCloseSmooth fallback: too many blocks=" + entries.size());
            return false;
        }

        int effectiveDistance = Math.max(1, distance);
        long[] delays = buildStepDelays(effectiveDistance);
        int totalTicks = (int) Math.max(2L, delays[delays.length - 1]);
        double stepY = (double) effectiveDistance / (double) totalTicks;

        List<LiftFallingEntry> entities = new ArrayList<>();
        for (Map.Entry<DoorBlockKey, BlockData> entry : entries) {
            DoorBlockKey key = entry.getKey();
            BlockData data = entry.getValue();
            int fromY = key.y + effectiveDistance;
            Location loc = new Location(world, key.x + 0.5D, fromY, key.z + 0.5D);
            FallingBlock falling = world.spawnFallingBlock(loc, data);
            falling.setGravity(false);
            falling.setDropItem(false);
            try {
                falling.setHurtEntities(false);
            } catch (Throwable ignored) {
            }
            entities.add(new LiftFallingEntry(key, data, falling, loc.getY()));
            if (fromY >= world.getMinHeight() && fromY < world.getMaxHeight()) {
                world.getBlockAt(key.x, fromY, key.z).setType(Material.AIR, false);
            }
        }

        DoorBlockKey anchor = entries.get(0).getKey();
        int chunkX = anchor.x >> 4;
        int chunkZ = anchor.z >> 4;
        runLiftFallingTick(world, chunkX, chunkZ, entities, 0, totalTicks, -stepY, () -> {
            for (LiftFallingEntry entry : entities) {
                FallingBlock falling = entry.entity;
                if (falling != null && !falling.isDead()) {
                    falling.remove();
                }
                if (entry.key.y < world.getMinHeight() || entry.key.y >= world.getMaxHeight()) {
                    continue;
                }
                Block target = world.getBlockAt(entry.key.x, entry.key.y, entry.key.z);
                target.setType(entry.data.getMaterial(), false);
                target.setBlockData(entry.data, false);
            }
        });
        return true;
    }

    private void runLiftFallingTick(World world, int chunkX, int chunkZ,
                                    List<LiftFallingEntry> entries,
                                    int tick, int totalTicks, double vy,
                                    Runnable onFinish) {
        if (tick >= totalTicks) {
            runRegionTask(world, chunkX, chunkZ, 2L, onFinish);
            return;
        }
        runRegionTask(world, chunkX, chunkZ, 1L, () -> {
            int nextTick = tick + 1;
            for (LiftFallingEntry entry : entries) {
                FallingBlock falling = entry.entity;
                if (falling == null || falling.isDead()) {
                    continue;
                }
                double targetY = entry.baseY + (vy * nextTick);
                double currentY = falling.getLocation().getY();
                double deltaY = (targetY - currentY) * 0.101D;
                falling.setVelocity(new Vector(0.0D, deltaY, 0.0D));
            }
            runLiftFallingTick(world, chunkX, chunkZ, entries, tick + 1, totalTicks, vy, onFinish);
        });
    }

    private void animateSwingOpen(World world, ArenaDoor door, LinkedHashMap<DoorBlockKey, BlockData> snapshot, int animationDistance) {
        List<Map.Entry<DoorBlockKey, BlockData>> entries = new ArrayList<>();
        for (Map.Entry<DoorBlockKey, BlockData> entry : snapshot.entrySet()) {
            BlockData data = entry.getValue();
            if (data != null && data.getMaterial() != Material.AIR) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            debug("animateSwingOpen skipped: no entries");
            return;
        }
        SwingSpec spec = SwingSpec.from(entries, door);
        if (useSmoothSwingAnimation() && animateSwingOpenSmooth(world, entries, spec, animationDistance)) {
            return;
        }
        int steps = Math.max(8, Math.max(1, animationDistance) * 4);
        long[] delays = buildStepDelays(steps);
        DoorBlockKey anchor = entries.get(0).getKey();
        int chunkX = anchor.x >> 4;
        int chunkZ = anchor.z >> 4;

        Map<Integer, DoorBlockKey> previousPositions = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            previousPositions.put(i, entries.get(i).getKey());
        }

        debug("animateSwingOpen start entries=" + entries.size() + " steps=" + steps + " totalTicks=" + delays[delays.length - 1]);
        for (int step = 1; step <= steps; step++) {
            final int currentStep = step;
            long delay = delays[currentStep - 1];
            runRegionTask(world, chunkX, chunkZ, delay, () -> {
                for (DoorBlockKey prev : previousPositions.values()) {
                    if (prev.y >= world.getMinHeight() && prev.y < world.getMaxHeight()) {
                        world.getBlockAt(prev.x, prev.y, prev.z).setType(Material.AIR, false);
                    }
                }

                double progress = (double) currentStep / (double) steps;
                double angle = (Math.PI / 2.0D) * progress;
                if (!spec.clockwise) {
                    angle = -angle;
                }
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);

                Map<Integer, DoorBlockKey> nextPositions = new HashMap<>();
                for (int i = 0; i < entries.size(); i++) {
                    Map.Entry<DoorBlockKey, BlockData> entry = entries.get(i);
                    DoorBlockKey key = entry.getKey();
                    BlockData data = entry.getValue();

                    double relX = (key.x + 0.5D) - spec.pivotX;
                    double relZ = (key.z + 0.5D) - spec.pivotZ;
                    double rotX = relX * cos - relZ * sin;
                    double rotZ = relX * sin + relZ * cos;
                    int toX = (int) Math.floor(spec.pivotX + rotX);
                    int toZ = (int) Math.floor(spec.pivotZ + rotZ);

                    if (key.y < world.getMinHeight() || key.y >= world.getMaxHeight()) {
                        continue;
                    }
                    Block target = world.getBlockAt(toX, key.y, toZ);
                    target.setType(data.getMaterial(), false);
                    target.setBlockData(data, false);
                    nextPositions.put(i, new DoorBlockKey(toX, key.y, toZ));
                }
                previousPositions.clear();
                previousPositions.putAll(nextPositions);
            });
        }
    }

    private void animateSwingClose(World world, ArenaDoor door, LinkedHashMap<DoorBlockKey, BlockData> snapshot, int animationDistance) {
        List<Map.Entry<DoorBlockKey, BlockData>> entries = new ArrayList<>();
        for (Map.Entry<DoorBlockKey, BlockData> entry : snapshot.entrySet()) {
            BlockData data = entry.getValue();
            if (data != null && data.getMaterial() != Material.AIR) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            debug("animateSwingClose skipped: no entries");
            return;
        }
        SwingSpec spec = SwingSpec.from(entries, door);
        if (useSmoothSwingAnimation() && animateSwingCloseSmooth(world, entries, spec, animationDistance)) {
            return;
        }
        int steps = Math.max(8, Math.max(1, animationDistance) * 4);
        long[] delays = buildStepDelays(steps);
        DoorBlockKey anchor = entries.get(0).getKey();
        int chunkX = anchor.x >> 4;
        int chunkZ = anchor.z >> 4;

        Map<Integer, DoorBlockKey> previousPositions = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<DoorBlockKey, BlockData> entry = entries.get(i);
            DoorBlockKey key = entry.getKey();
            DoorBlockKey opened = rotateKey(key, spec, spec.clockwise ? (Math.PI / 2.0D) : (-Math.PI / 2.0D));
            previousPositions.put(i, opened);
        }

        debug("animateSwingClose start entries=" + entries.size() + " steps=" + steps + " totalTicks=" + delays[delays.length - 1]);
        for (int step = 1; step <= steps; step++) {
            final int currentStep = step;
            long delay = delays[currentStep - 1];
            runRegionTask(world, chunkX, chunkZ, delay, () -> {
                for (DoorBlockKey prev : previousPositions.values()) {
                    if (prev.y >= world.getMinHeight() && prev.y < world.getMaxHeight()) {
                        world.getBlockAt(prev.x, prev.y, prev.z).setType(Material.AIR, false);
                    }
                }

                double progress = (double) currentStep / (double) steps;
                double angle = (Math.PI / 2.0D) * (1.0D - progress);
                if (!spec.clockwise) {
                    angle = -angle;
                }
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);

                Map<Integer, DoorBlockKey> nextPositions = new HashMap<>();
                for (int i = 0; i < entries.size(); i++) {
                    Map.Entry<DoorBlockKey, BlockData> entry = entries.get(i);
                    DoorBlockKey key = entry.getKey();
                    BlockData data = entry.getValue();

                    double relX = (key.x + 0.5D) - spec.pivotX;
                    double relZ = (key.z + 0.5D) - spec.pivotZ;
                    double rotX = relX * cos - relZ * sin;
                    double rotZ = relX * sin + relZ * cos;
                    int toX = (int) Math.floor(spec.pivotX + rotX);
                    int toZ = (int) Math.floor(spec.pivotZ + rotZ);

                    if (key.y < world.getMinHeight() || key.y >= world.getMaxHeight()) {
                        continue;
                    }
                    Block target = world.getBlockAt(toX, key.y, toZ);
                    target.setType(data.getMaterial(), false);
                    target.setBlockData(data, false);
                    nextPositions.put(i, new DoorBlockKey(toX, key.y, toZ));
                }
                previousPositions.clear();
                previousPositions.putAll(nextPositions);
            });
        }
    }

    private DoorBlockKey rotateKey(DoorBlockKey key, SwingSpec spec, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double relX = (key.x + 0.5D) - spec.pivotX;
        double relZ = (key.z + 0.5D) - spec.pivotZ;
        double rotX = relX * cos - relZ * sin;
        double rotZ = relX * sin + relZ * cos;
        int toX = (int) Math.floor(spec.pivotX + rotX);
        int toZ = (int) Math.floor(spec.pivotZ + rotZ);
        return new DoorBlockKey(toX, key.y, toZ);
    }

    private boolean animateSwingOpenSmooth(World world,
                                           List<Map.Entry<DoorBlockKey, BlockData>> entries,
                                           SwingSpec spec,
                                           int animationDistance) {
        if (entries.size() > getSmoothSwingMaxBlocks()) {
            debug("animateSwingOpenSmooth fallback: too many blocks=" + entries.size());
            return false;
        }
        int steps = Math.max(8, Math.max(1, animationDistance) * 4);
        long[] delays = buildStepDelays(steps);
        int totalTicks = (int) Math.max(2L, delays[delays.length - 1]);
        double anglePerTick = (Math.PI / 2.0D) / (double) totalTicks;
        if (!spec.clockwise) {
            anglePerTick = -anglePerTick;
        }

        List<SwingFallingEntry> entities = new ArrayList<>();
        for (Map.Entry<DoorBlockKey, BlockData> entry : entries) {
            DoorBlockKey key = entry.getKey();
            BlockData data = entry.getValue();
            Location loc = new Location(world, key.x + 0.5D, key.y, key.z + 0.5D);
            FallingBlock falling = world.spawnFallingBlock(loc, data);
            falling.setGravity(false);
            falling.setDropItem(false);
            try {
                falling.setHurtEntities(false);
            } catch (Throwable ignored) {
            }
            entities.add(new SwingFallingEntry(key, data, falling, loc.getX(), loc.getY(), loc.getZ()));
            if (key.y >= world.getMinHeight() && key.y < world.getMaxHeight()) {
                world.getBlockAt(key.x, key.y, key.z).setType(Material.AIR, false);
            }
        }

        DoorBlockKey anchor = entries.get(0).getKey();
        int chunkX = anchor.x >> 4;
        int chunkZ = anchor.z >> 4;
        runSwingFallingTick(world, chunkX, chunkZ, entities, spec, 0, totalTicks, anglePerTick, () -> {
            double endAngle = spec.clockwise ? (Math.PI / 2.0D) : (-Math.PI / 2.0D);
            for (SwingFallingEntry entry : entities) {
                FallingBlock falling = entry.entity;
                if (falling != null && !falling.isDead()) {
                    falling.remove();
                }
                DoorBlockKey to = rotateKey(entry.key, spec, endAngle);
                if (to.y < world.getMinHeight() || to.y >= world.getMaxHeight()) {
                    continue;
                }
                Block target = world.getBlockAt(to.x, to.y, to.z);
                target.setType(entry.data.getMaterial(), false);
                target.setBlockData(entry.data, false);
            }
        });
        return true;
    }

    private boolean animateSwingCloseSmooth(World world,
                                            List<Map.Entry<DoorBlockKey, BlockData>> entries,
                                            SwingSpec spec,
                                            int animationDistance) {
        if (entries.size() > getSmoothSwingMaxBlocks()) {
            debug("animateSwingCloseSmooth fallback: too many blocks=" + entries.size());
            return false;
        }
        int steps = Math.max(8, Math.max(1, animationDistance) * 4);
        long[] delays = buildStepDelays(steps);
        int totalTicks = (int) Math.max(2L, delays[delays.length - 1]);
        double anglePerTick = (Math.PI / 2.0D) / (double) totalTicks;
        if (!spec.clockwise) {
            anglePerTick = -anglePerTick;
        }

        List<SwingFallingEntry> entities = new ArrayList<>();
        double startAngle = spec.clockwise ? (Math.PI / 2.0D) : (-Math.PI / 2.0D);
        for (Map.Entry<DoorBlockKey, BlockData> entry : entries) {
            DoorBlockKey key = entry.getKey();
            BlockData data = entry.getValue();
            DoorBlockKey opened = rotateKey(key, spec, startAngle);
            Location loc = new Location(world, opened.x + 0.5D, opened.y, opened.z + 0.5D);
            FallingBlock falling = world.spawnFallingBlock(loc, data);
            falling.setGravity(false);
            falling.setDropItem(false);
            try {
                falling.setHurtEntities(false);
            } catch (Throwable ignored) {
            }
            entities.add(new SwingFallingEntry(key, data, falling, loc.getX(), loc.getY(), loc.getZ()));
            if (opened.y >= world.getMinHeight() && opened.y < world.getMaxHeight()) {
                world.getBlockAt(opened.x, opened.y, opened.z).setType(Material.AIR, false);
            }
        }

        DoorBlockKey anchor = entries.get(0).getKey();
        int chunkX = anchor.x >> 4;
        int chunkZ = anchor.z >> 4;
        runSwingFallingTick(world, chunkX, chunkZ, entities, spec, 0, totalTicks, -anglePerTick, () -> {
            for (SwingFallingEntry entry : entities) {
                FallingBlock falling = entry.entity;
                if (falling != null && !falling.isDead()) {
                    falling.remove();
                }
                if (entry.key.y < world.getMinHeight() || entry.key.y >= world.getMaxHeight()) {
                    continue;
                }
                Block target = world.getBlockAt(entry.key.x, entry.key.y, entry.key.z);
                target.setType(entry.data.getMaterial(), false);
                target.setBlockData(entry.data, false);
            }
        });
        return true;
    }

    private void runSwingFallingTick(World world, int chunkX, int chunkZ,
                                     List<SwingFallingEntry> entries,
                                     SwingSpec spec,
                                     int tick, int totalTicks, double deltaAngle,
                                     Runnable onFinish) {
        if (tick >= totalTicks) {
            runRegionTask(world, chunkX, chunkZ, 2L, onFinish);
            return;
        }
        runRegionTask(world, chunkX, chunkZ, 1L, () -> {
            int nextTick = tick + 1;
            for (SwingFallingEntry entry : entries) {
                FallingBlock falling = entry.entity;
                if (falling == null || falling.isDead()) {
                    continue;
                }
                double angle = deltaAngle * nextTick;
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                double relX = entry.baseX - spec.pivotX;
                double relZ = entry.baseZ - spec.pivotZ;
                double targetX = spec.pivotX + (relX * cos - relZ * sin);
                double targetZ = spec.pivotZ + (relX * sin + relZ * cos);
                Location cur = falling.getLocation();
                double vx = (targetX - cur.getX()) * 0.101D;
                double vz = (targetZ - cur.getZ()) * 0.101D;
                falling.setVelocity(new Vector(vx, 0.0D, vz));
            }
            runSwingFallingTick(world, chunkX, chunkZ, entries, spec, tick + 1, totalTicks, deltaAngle, onFinish);
        });
    }

    private void runRegionTask(World world, int chunkX, int chunkZ, long delayTicks, Runnable action) {
        if (delayTicks <= 0L) {
            plugin.getServer().getRegionScheduler().run(plugin, world, chunkX, chunkZ, task -> action.run());
            return;
        }
        plugin.getServer().getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, task -> action.run(), delayTicks);
    }

    private void evictPlayersInsideDoor(Arena arena, ArenaDoor door) {
        World world = plugin.getServer().getWorld(arena.getWorldName());
        if (world == null || door == null || !door.isReady()) {
            return;
        }
        Location target = arena.getSpawn1() != null ? arena.getSpawn1() : world.getSpawnLocation();
        for (Player player : world.getPlayers()) {
            if (!door.contains(player.getLocation())) {
                continue;
            }
            MessageUtil.send(player, "door_evict_warning",
                Placeholder.unparsed("arena", arena.getId()),
                Placeholder.unparsed("door", door.getId()));
            player.teleportAsync(target);
        }
    }

    private LinkedHashMap<DoorBlockKey, BlockData> getOrCreateSnapshot(Arena arena, ArenaDoor door) {
        Map<String, LinkedHashMap<DoorBlockKey, BlockData>> arenaDoors =
            closedSnapshots.computeIfAbsent(arena.getId().toLowerCase(), k -> new HashMap<>());
        return arenaDoors.computeIfAbsent(door.getId().toLowerCase(), k -> new LinkedHashMap<>());
    }

    private void captureClosedSnapshot(World world, ArenaDoor door, LinkedHashMap<DoorBlockKey, BlockData> out) {
        int minX = Math.min(door.getMinBound().getBlockX(), door.getMaxBound().getBlockX());
        int minY = Math.min(door.getMinBound().getBlockY(), door.getMaxBound().getBlockY());
        int minZ = Math.min(door.getMinBound().getBlockZ(), door.getMaxBound().getBlockZ());
        int maxX = Math.max(door.getMinBound().getBlockX(), door.getMaxBound().getBlockX());
        int maxY = Math.max(door.getMinBound().getBlockY(), door.getMaxBound().getBlockY());
        int maxZ = Math.max(door.getMinBound().getBlockZ(), door.getMaxBound().getBlockZ());

        int maxBlocks = Math.max(128, plugin.getConfig().getInt("bigdoor.max-blocks-per-door", 65536));
        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (count++ >= maxBlocks) {
                        return;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    out.putIfAbsent(new DoorBlockKey(x, y, z), block.getBlockData().clone());
                }
            }
        }
    }

    private void captureClosedSnapshotOverwrite(World world, ArenaDoor door, LinkedHashMap<DoorBlockKey, BlockData> out) {
        out.clear();
        captureClosedSnapshot(world, door, out);
    }

    private void captureClosedSnapshotOverwrite(World world, ArenaDoor door, LinkedHashMap<DoorBlockKey, BlockData> out, int padding) {
        out.clear();
        captureClosedSnapshot(world, door, out, padding);
    }

    private void captureClosedSnapshot(World world, ArenaDoor door, LinkedHashMap<DoorBlockKey, BlockData> out, int padding) {
        int minX = Math.min(door.getMinBound().getBlockX(), door.getMaxBound().getBlockX()) - padding;
        int minY = Math.min(door.getMinBound().getBlockY(), door.getMaxBound().getBlockY()) - padding;
        int minZ = Math.min(door.getMinBound().getBlockZ(), door.getMaxBound().getBlockZ()) - padding;
        int maxX = Math.max(door.getMinBound().getBlockX(), door.getMaxBound().getBlockX()) + padding;
        int maxY = Math.max(door.getMinBound().getBlockY(), door.getMaxBound().getBlockY()) + padding;
        int maxZ = Math.max(door.getMinBound().getBlockZ(), door.getMaxBound().getBlockZ()) + padding;

        int maxBlocks = Math.max(128, plugin.getConfig().getInt("bigdoor.max-blocks-per-door", 65536));
        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (count++ >= maxBlocks) {
                        return;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    out.putIfAbsent(new DoorBlockKey(x, y, z), block.getBlockData().clone());
                }
            }
        }
    }

    private int countNonAir(LinkedHashMap<DoorBlockKey, BlockData> snapshot) {
        int count = 0;
        for (BlockData data : snapshot.values()) {
            if (data != null && data.getMaterial() != Material.AIR) {
                count++;
            }
        }
        return count;
    }

    private int countNonAirInArea(World world, ArenaDoor door) {
        int minX = Math.min(door.getMinBound().getBlockX(), door.getMaxBound().getBlockX());
        int minY = Math.min(door.getMinBound().getBlockY(), door.getMaxBound().getBlockY());
        int minZ = Math.min(door.getMinBound().getBlockZ(), door.getMaxBound().getBlockZ());
        int maxX = Math.max(door.getMinBound().getBlockX(), door.getMaxBound().getBlockX());
        int maxY = Math.max(door.getMinBound().getBlockY(), door.getMaxBound().getBlockY());
        int maxZ = Math.max(door.getMinBound().getBlockZ(), door.getMaxBound().getBlockZ());

        int maxBlocks = Math.max(128, plugin.getConfig().getInt("bigdoor.max-blocks-per-door", 65536));
        int scanned = 0;
        int solid = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (scanned++ >= maxBlocks) {
                        return solid;
                    }
                    if (world.getBlockAt(x, y, z).getType() != Material.AIR) {
                        solid++;
                    }
                }
            }
        }
        return solid;
    }

    private int countNonAirInArea(World world, ArenaDoor door, int padding) {
        int minX = Math.min(door.getMinBound().getBlockX(), door.getMaxBound().getBlockX()) - padding;
        int minY = Math.min(door.getMinBound().getBlockY(), door.getMaxBound().getBlockY()) - padding;
        int minZ = Math.min(door.getMinBound().getBlockZ(), door.getMaxBound().getBlockZ()) - padding;
        int maxX = Math.max(door.getMinBound().getBlockX(), door.getMaxBound().getBlockX()) + padding;
        int maxY = Math.max(door.getMinBound().getBlockY(), door.getMaxBound().getBlockY()) + padding;
        int maxZ = Math.max(door.getMinBound().getBlockZ(), door.getMaxBound().getBlockZ()) + padding;

        int maxBlocks = Math.max(128, plugin.getConfig().getInt("bigdoor.max-blocks-per-door", 65536));
        int scanned = 0;
        int solid = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (scanned++ >= maxBlocks) {
                        return solid;
                    }
                    if (world.getBlockAt(x, y, z).getType() != Material.AIR) {
                        solid++;
                    }
                }
            }
        }
        return solid;
    }

    private void applyArea(World world, ArenaDoor door, Material toType) {
        int minX = Math.min(door.getMinBound().getBlockX(), door.getMaxBound().getBlockX());
        int minY = Math.min(door.getMinBound().getBlockY(), door.getMaxBound().getBlockY());
        int minZ = Math.min(door.getMinBound().getBlockZ(), door.getMaxBound().getBlockZ());
        int maxX = Math.max(door.getMinBound().getBlockX(), door.getMaxBound().getBlockX());
        int maxY = Math.max(door.getMinBound().getBlockY(), door.getMaxBound().getBlockY());
        int maxZ = Math.max(door.getMinBound().getBlockZ(), door.getMaxBound().getBlockZ());

        List<DoorBlockKey> blocks = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    blocks.add(new DoorBlockKey(x, y, z));
                }
            }
        }
        int perTick = Math.max(32, plugin.getConfig().getInt("bigdoor.blocks-per-tick", 256));
        applyTypeBatch(world, blocks, 0, perTick, toType);
    }

    private void applyTypeBatch(World world, List<DoorBlockKey> blocks, int index, int perTick, Material type) {
        int end = Math.min(blocks.size(), index + perTick);
        for (int i = index; i < end; i++) {
            DoorBlockKey key = blocks.get(i);
            world.getBlockAt(key.x, key.y, key.z).setType(type, false);
        }
        if (end < blocks.size()) {
            DoorBlockKey anchor = blocks.get(end);
            int chunkX = anchor.x >> 4;
            int chunkZ = anchor.z >> 4;
            plugin.getServer().getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ,
                task -> applyTypeBatch(world, blocks, end, perTick, type), 1L);
        }
    }

    private void applySnapshot(World world, LinkedHashMap<DoorBlockKey, BlockData> snapshot) {
        List<Map.Entry<DoorBlockKey, BlockData>> entries = new ArrayList<>(snapshot.entrySet());
        int perTick = Math.max(32, plugin.getConfig().getInt("bigdoor.blocks-per-tick", 256));
        applySnapshotBatch(world, entries, 0, perTick);
    }

    private void applySnapshotBatch(World world, List<Map.Entry<DoorBlockKey, BlockData>> entries, int index, int perTick) {
        int end = Math.min(entries.size(), index + perTick);
        for (int i = index; i < end; i++) {
            Map.Entry<DoorBlockKey, BlockData> entry = entries.get(i);
            DoorBlockKey key = entry.getKey();
            BlockData data = entry.getValue();
            Block block = world.getBlockAt(key.x, key.y, key.z);
            block.setType(data.getMaterial(), false);
            block.setBlockData(data, false);
        }
        if (end < entries.size()) {
            DoorBlockKey anchor = entries.get(end).getKey();
            int chunkX = anchor.x >> 4;
            int chunkZ = anchor.z >> 4;
            plugin.getServer().getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ,
                task -> applySnapshotBatch(world, entries, end, perTick), 1L);
        }
    }

    private static final class DoorBlockKey {
        private final int x;
        private final int y;
        private final int z;

        private DoorBlockKey(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DoorBlockKey that)) {
                return false;
            }
            return x == that.x && y == that.y && z == that.z;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(x);
            result = 31 * result + Integer.hashCode(y);
            result = 31 * result + Integer.hashCode(z);
            return result;
        }
    }

    private static final class LiftFallingEntry {
        private final DoorBlockKey key;
        private final BlockData data;
        private final FallingBlock entity;
        private final double baseY;

        private LiftFallingEntry(DoorBlockKey key, BlockData data, FallingBlock entity, double baseY) {
            this.key = key;
            this.data = data;
            this.entity = entity;
            this.baseY = baseY;
        }
    }

    private static final class SwingFallingEntry {
        private final DoorBlockKey key;
        private final BlockData data;
        private final FallingBlock entity;
        private final double baseX;
        private final double baseY;
        private final double baseZ;

        private SwingFallingEntry(DoorBlockKey key, BlockData data, FallingBlock entity,
                                  double baseX, double baseY, double baseZ) {
            this.key = key;
            this.data = data;
            this.entity = entity;
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
        }
    }

    private static final class SwingSpec {
        private final double pivotX;
        private final double pivotZ;
        private final boolean clockwise;

        private SwingSpec(double pivotX, double pivotZ, boolean clockwise) {
            this.pivotX = pivotX;
            this.pivotZ = pivotZ;
            this.clockwise = clockwise;
        }

        private static SwingSpec from(List<Map.Entry<DoorBlockKey, BlockData>> entries, ArenaDoor door) {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (Map.Entry<DoorBlockKey, BlockData> entry : entries) {
                DoorBlockKey key = entry.getKey();
                if (key.x < minX) {
                    minX = key.x;
                }
                if (key.x > maxX) {
                    maxX = key.x;
                }
                if (key.z < minZ) {
                    minZ = key.z;
                }
                if (key.z > maxZ) {
                    maxZ = key.z;
                }
            }

            int spanX = Math.max(1, maxX - minX + 1);
            int spanZ = Math.max(1, maxZ - minZ + 1);

            boolean clockwise = spanX >= spanZ;
            String dir = door == null ? "auto" : door.getSwingDirection();
            if ("inward".equalsIgnoreCase(dir)) {
                clockwise = true;
            } else if ("outward".equalsIgnoreCase(dir)) {
                clockwise = false;
            }
            return new SwingSpec(minX + 0.5D, minZ + 0.5D, clockwise);
        }
    }
}
