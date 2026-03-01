package com.pvparena.hook.husksync;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/**
 * Writes the player's restored state back into HuskSync's current data.
 *
 * <p>We use a custom save cause with {@code fireDataSaveEvent=false} so this write-back is not
 * blocked by our own HuskSync save/sync cancellation hook during restore windows.</p>
 */
public final class HuskSyncWriteBack {

    private HuskSyncWriteBack() {
    }

    public static void writeBackCurrentState(JavaPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return;
        }
        if (!isPluginEnabled(plugin, "HuskSync")) {
            return;
        }

        try {
            Class<?> apiClass = Class.forName("net.william278.husksync.api.BukkitHuskSyncAPI");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object user = apiClass.getMethod("getUser", Player.class).invoke(api, player);

            // SaveCause.of("PVPA_RESTORE", false)
            Class<?> saveCauseClass = Class.forName("net.william278.husksync.data.DataSnapshot$SaveCause");
            Object saveCause = saveCauseClass.getMethod("of", String.class, boolean.class)
                    .invoke(null, "PVPA_RESTORE", false);

            // api.snapshotBuilder()
            Object builder = apiClass.getMethod("snapshotBuilder").invoke(api);
            Method saveCauseMethod = findMethod(builder.getClass(), "saveCause", saveCauseClass);
            if (saveCauseMethod == null) {
                return;
            }
            builder = saveCauseMethod.invoke(builder, saveCause);

            ItemStack[] inventoryContents = safeClone(player.getInventory().getContents());
            int heldItemSlot = player.getInventory().getHeldItemSlot();
            ItemStack[] enderChestContents = safeClone(player.getEnderChest().getContents());

            Class<?> inventoryClass = Class.forName("net.william278.husksync.data.BukkitData$Items$Inventory");
            Class<?> enderChestClass = Class.forName("net.william278.husksync.data.BukkitData$Items$EnderChest");
            Class<?> healthClass = Class.forName("net.william278.husksync.data.BukkitData$Health");
            Class<?> hungerClass = Class.forName("net.william278.husksync.data.BukkitData$Hunger");
            Class<?> experienceClass = Class.forName("net.william278.husksync.data.BukkitData$Experience");
            Class<?> potionEffectsClass = Class.forName("net.william278.husksync.data.BukkitData$PotionEffects");
            Class<?> gameModeClass = Class.forName("net.william278.husksync.data.BukkitData$GameMode");
            Class<?> flightStatusClass = Class.forName("net.william278.husksync.data.BukkitData$FlightStatus");

            Object inventory = inventoryClass.getMethod("from", ItemStack[].class, int.class)
                    .invoke(null, inventoryContents, heldItemSlot);
            Object enderChest = enderChestClass.getMethod("adapt", ItemStack[].class)
                    .invoke(null, enderChestContents);
            Object health = healthClass.getMethod("adapt", Player.class).invoke(null, player);
            Object hunger = hungerClass.getMethod("adapt", Player.class).invoke(null, player);
            Object experience = experienceClass.getMethod("adapt", Player.class).invoke(null, player);
            Object potionEffects = potionEffectsClass.getMethod("from", Iterable.class)
                    .invoke(null, player.getActivePotionEffects());
            Object gameMode = gameModeClass.getMethod("adapt", Player.class).invoke(null, player);
            Object flightStatus = flightStatusClass.getMethod("adapt", Player.class).invoke(null, player);

            builder = invokeBuilder(builder, "inventory", inventory);
            builder = invokeBuilder(builder, "enderChest", enderChest);
            builder = invokeBuilder(builder, "health", health);
            builder = invokeBuilder(builder, "hunger", hunger);
            builder = invokeBuilder(builder, "experience", experience);
            builder = invokeBuilder(builder, "potionEffects", potionEffects);
            builder = invokeBuilder(builder, "gameMode", gameMode);
            builder = invokeBuilder(builder, "flightStatus", flightStatus);
            if (builder == null) {
                return;
            }

            Method buildMethod = findMethod(builder.getClass(), "build");
            if (buildMethod == null) {
                return;
            }
            Object snapshot = buildMethod.invoke(builder);

            Method setCurrentData = findSetCurrentData(apiClass);
            if (setCurrentData == null) {
                return;
            }
            setCurrentData.invoke(api, user, snapshot);
        } catch (Throwable ignored) {
            // HuskSync missing, not registered yet, or API failure — ignore to avoid breaking restore.
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
