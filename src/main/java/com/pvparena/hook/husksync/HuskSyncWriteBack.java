package com.pvparena.hook.husksync;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes the player's restored state back into HuskSync's current data.
 *
 * <p>We use a custom save cause with {@code fireDataSaveEvent=false} so this write-back is not
 * blocked by our own HuskSync save/sync cancellation hook during restore windows.</p>
 */
public final class HuskSyncWriteBack {
    private static final long WRITEBACK_THROTTLE_MS = 1500L;
    private static final Map<UUID, Long> LAST_WRITEBACK = new ConcurrentHashMap<>();
    private static volatile ReflectionCache CACHE;

    private HuskSyncWriteBack() {
    }

    public static void writeBackCurrentState(JavaPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        Long last = LAST_WRITEBACK.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (last != null && now - last < WRITEBACK_THROTTLE_MS) {
            return;
        }
        LAST_WRITEBACK.put(player.getUniqueId(), now);
        if (!isPluginEnabled(plugin, "HuskSync")) {
            return;
        }

        try {
            ReflectionCache reflection = getOrCreateCache();
            if (reflection == null) {
                return;
            }

            Object api = reflection.apiGetInstance.invoke(null);
            Object user = reflection.apiGetUser.invoke(api, player);
            Object saveCause = reflection.saveCauseOf.invoke(null, "PVPA_RESTORE", false);

            Object builder = reflection.apiSnapshotBuilder.invoke(api);
            builder = reflection.builderSaveCause.invoke(builder, saveCause);

            ItemStack[] inventoryContents = safeClone(player.getInventory().getContents());
            int heldItemSlot = player.getInventory().getHeldItemSlot();
            ItemStack[] enderChestContents = safeClone(player.getEnderChest().getContents());

            Object inventory = reflection.inventoryFrom.invoke(null, inventoryContents, heldItemSlot);
            Object enderChest = reflection.enderChestAdapt.invoke(null, enderChestContents);
            Object health = reflection.healthAdapt.invoke(null, player);
            Object hunger = reflection.hungerAdapt.invoke(null, player);
            Object experience = reflection.experienceAdapt.invoke(null, player);
            Object potionEffects = reflection.potionEffectsFrom.invoke(null, player.getActivePotionEffects());
            Object gameMode = reflection.gameModeAdapt.invoke(null, player);
            Object flightStatus = reflection.flightStatusAdapt.invoke(null, player);

            builder = reflection.builderInventory.invoke(builder, inventory);
            builder = reflection.builderEnderChest.invoke(builder, enderChest);
            builder = reflection.builderHealth.invoke(builder, health);
            builder = reflection.builderHunger.invoke(builder, hunger);
            builder = reflection.builderExperience.invoke(builder, experience);
            builder = reflection.builderPotionEffects.invoke(builder, potionEffects);
            builder = reflection.builderGameMode.invoke(builder, gameMode);
            builder = reflection.builderFlightStatus.invoke(builder, flightStatus);

            Object snapshot = reflection.builderBuild.invoke(builder);
            reflection.apiSetCurrentData.invoke(api, user, snapshot);
        } catch (Throwable ignored) {
            // HuskSync missing, not registered yet, or API failure — ignore to avoid breaking restore.
        }
    }

    private static ReflectionCache getOrCreateCache() {
        ReflectionCache local = CACHE;
        if (local != null) {
            return local;
        }
        synchronized (HuskSyncWriteBack.class) {
            if (CACHE != null) {
                return CACHE;
            }
            try {
                Class<?> apiClass = Class.forName("net.william278.husksync.api.BukkitHuskSyncAPI");
                Class<?> saveCauseClass = Class.forName("net.william278.husksync.data.DataSnapshot$SaveCause");
                Class<?> inventoryClass = Class.forName("net.william278.husksync.data.BukkitData$Items$Inventory");
                Class<?> enderChestClass = Class.forName("net.william278.husksync.data.BukkitData$Items$EnderChest");
                Class<?> healthClass = Class.forName("net.william278.husksync.data.BukkitData$Health");
                Class<?> hungerClass = Class.forName("net.william278.husksync.data.BukkitData$Hunger");
                Class<?> experienceClass = Class.forName("net.william278.husksync.data.BukkitData$Experience");
                Class<?> potionEffectsClass = Class.forName("net.william278.husksync.data.BukkitData$PotionEffects");
                Class<?> gameModeClass = Class.forName("net.william278.husksync.data.BukkitData$GameMode");
                Class<?> flightStatusClass = Class.forName("net.william278.husksync.data.BukkitData$FlightStatus");

                Object api = apiClass.getMethod("getInstance").invoke(null);
                Object builderSample = apiClass.getMethod("snapshotBuilder").invoke(api);
                Class<?> builderClass = builderSample.getClass();

                CACHE = new ReflectionCache(
                        apiClass.getMethod("getInstance"),
                        apiClass.getMethod("getUser", Player.class),
                        apiClass.getMethod("snapshotBuilder"),
                        findSetCurrentData(apiClass),
                        saveCauseClass.getMethod("of", String.class, boolean.class),
                        findMethod(builderClass, "saveCause", saveCauseClass),
                        findMethod(builderClass, "inventory", inventoryClass),
                        findMethod(builderClass, "enderChest", enderChestClass),
                        findMethod(builderClass, "health", healthClass),
                        findMethod(builderClass, "hunger", hungerClass),
                        findMethod(builderClass, "experience", experienceClass),
                        findMethod(builderClass, "potionEffects", potionEffectsClass),
                        findMethod(builderClass, "gameMode", gameModeClass),
                        findMethod(builderClass, "flightStatus", flightStatusClass),
                        findMethod(builderClass, "build"),
                        inventoryClass.getMethod("from", ItemStack[].class, int.class),
                        enderChestClass.getMethod("adapt", ItemStack[].class),
                        healthClass.getMethod("adapt", Player.class),
                        hungerClass.getMethod("adapt", Player.class),
                        experienceClass.getMethod("adapt", Player.class),
                        potionEffectsClass.getMethod("from", Iterable.class),
                        gameModeClass.getMethod("adapt", Player.class),
                        flightStatusClass.getMethod("adapt", Player.class)
                );
                return CACHE.isValid() ? CACHE : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static Object invokeBuilder(Object builder, String methodName, Object arg) throws Exception {
        if (builder == null || arg == null) {
            return builder;
        }
        Method method = findMethod(builder.getClass(), methodName, arg.getClass());
        if (method == null) {
            return null;
        }
        return method.invoke(builder, arg);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... directParamTypes) {
        try {
            return type.getMethod(name, directParamTypes);
        } catch (Throwable ignored) {
        }

        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            if (directParamTypes.length == 0 && method.getParameterCount() == 0) {
                return method;
            }
            if (directParamTypes.length == 1 && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(directParamTypes[0])) {
                return method;
            }
        }
        return null;
    }

    private static Method findSetCurrentData(Class<?> apiClass) {
        for (Method method : apiClass.getMethods()) {
            if (!method.getName().equals("setCurrentData")) {
                continue;
            }
            if (method.getParameterCount() == 2) {
                return method;
            }
        }
        return null;
    }

    private record ReflectionCache(
            Method apiGetInstance,
            Method apiGetUser,
            Method apiSnapshotBuilder,
            Method apiSetCurrentData,
            Method saveCauseOf,
            Method builderSaveCause,
            Method builderInventory,
            Method builderEnderChest,
            Method builderHealth,
            Method builderHunger,
            Method builderExperience,
            Method builderPotionEffects,
            Method builderGameMode,
            Method builderFlightStatus,
            Method builderBuild,
            Method inventoryFrom,
            Method enderChestAdapt,
            Method healthAdapt,
            Method hungerAdapt,
            Method experienceAdapt,
            Method potionEffectsFrom,
            Method gameModeAdapt,
            Method flightStatusAdapt
    ) {
        boolean isValid() {
            return apiGetInstance != null
                    && apiGetUser != null
                    && apiSnapshotBuilder != null
                    && apiSetCurrentData != null
                    && saveCauseOf != null
                    && builderSaveCause != null
                    && builderInventory != null
                    && builderEnderChest != null
                    && builderHealth != null
                    && builderHunger != null
                    && builderExperience != null
                    && builderPotionEffects != null
                    && builderGameMode != null
                    && builderFlightStatus != null
                    && builderBuild != null
                    && inventoryFrom != null
                    && enderChestAdapt != null
                    && healthAdapt != null
                    && hungerAdapt != null
                    && experienceAdapt != null
                    && potionEffectsFrom != null
                    && gameModeAdapt != null
                    && flightStatusAdapt != null;
        }
    }

    private static ItemStack[] safeClone(ItemStack[] contents) {
        return contents == null ? new ItemStack[0] : contents.clone();
    }

    private static boolean isPluginEnabled(JavaPlugin plugin, String name) {
        try {
            var other = plugin.getServer().getPluginManager().getPlugin(name);
            return other != null && other.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
