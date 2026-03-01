package com.pvparena.hook;

import com.pvparena.manager.MatchManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

public class HuskSyncHook {
    private static final String HUSKSYNC_PLUGIN_NAME = "HuskSync";

    private final JavaPlugin plugin;
    private final MatchManager matchManager;

    public HuskSyncHook(JavaPlugin plugin, MatchManager matchManager) {
        this.plugin = plugin;
        this.matchManager = matchManager;

        PluginManager pm = plugin.getServer().getPluginManager();
        Plugin huskSync = pm.getPlugin(HUSKSYNC_PLUGIN_NAME);
        if (huskSync == null || !huskSync.isEnabled()) {
            throw new IllegalStateException("HuskSync is not enabled");
        }

        boolean saveHooked = registerHuskSyncDataSaveEvent(pm);
        boolean preSyncHooked = registerHuskSyncPreSyncEvent(pm);

        if (saveHooked || preSyncHooked) {
            plugin.getLogger().info("Hooked into HuskSync (blocking match-time saves/syncs to prevent inventory overwrites)");
        } else {
            plugin.getLogger().warning("HuskSync detected but could not hook BukkitDataSaveEvent/BukkitPreSyncEvent; falling back to delayed restores only");
        }
    }

    private boolean registerHuskSyncDataSaveEvent(PluginManager pm) {
        final Class<? extends Event> eventClass;
        try {
            // HuskSync Bukkit platform event (v3)
            eventClass = (Class<? extends Event>) Class.forName("net.william278.husksync.event.BukkitDataSaveEvent");
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.FINE, "Failed to find HuskSync BukkitDataSaveEvent class", ex);
            return false;
        }

        final Listener listener = new Listener() {
        };

        pm.registerEvent(eventClass, listener, EventPriority.HIGHEST, (ignored, event) -> {
            try {
                handleDataSaveEvent(event);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "Error handling HuskSync BukkitDataSaveEvent", t);
            }
        }, plugin, false);
        return true;
    }

    private boolean registerHuskSyncPreSyncEvent(PluginManager pm) {
        final Class<? extends Event> eventClass;
        try {
            eventClass = (Class<? extends Event>) Class.forName("net.william278.husksync.event.BukkitPreSyncEvent");
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.FINE, "Failed to find HuskSync BukkitPreSyncEvent class", ex);
            return false;
        }

        final Listener listener = new Listener() {
        };

        pm.registerEvent(eventClass, listener, EventPriority.HIGHEST, (ignored, event) -> {
            try {
                handlePreSyncEvent(event);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "Error handling HuskSync BukkitPreSyncEvent", t);
            }
        }, plugin, false);
        return true;
    }

    private void handleDataSaveEvent(Event event) throws Exception {
        Object user = invoke(event, "getUser");
        if (user == null) {
            return;
        }

        Object uuidObj = invoke(user, "getUuid");
        if (!(uuidObj instanceof UUID uuid)) {
            return;
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }

        // Prevent HuskSync from persisting match kit state (or mid-restore state) into its DB.
        if (matchManager.isInMatch(player)
                || matchManager.hasPendingRestore(uuid)
                || matchManager.isExternalSyncBlocked(uuid)) {
            invoke(event, "setCancelled", true);
        }
    }

    private void handlePreSyncEvent(Event event) throws Exception {
        Object user = invoke(event, "getUser");
        if (user == null) {
            return;
        }

        Object uuidObj = invoke(user, "getUuid");
        if (!(uuidObj instanceof UUID uuid)) {
            return;
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }

        // If a restore is pending (e.g., player died mid-match) or player is/was in a match,
        // prevent HuskSync from applying DB state over PvPArena's restore.
        if (matchManager.isInMatch(player)
                || matchManager.hasPendingRestore(uuid)
                || matchManager.isExternalSyncBlocked(uuid)) {
            invoke(event, "setCancelled", true);
        }
    }

    private static Object invoke(Object target, String methodName, Object... args) throws Exception {
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                paramTypes[i] = Object.class;
            } else if (arg instanceof Boolean) {
                paramTypes[i] = boolean.class;
            } else {
                paramTypes[i] = arg.getClass();
            }
        }

        Method method;
        try {
            method = target.getClass().getMethod(methodName, paramTypes);
        } catch (NoSuchMethodException ex) {
            // Try to find compatible primitive/wrapper overloads
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(methodName)) {
                    continue;
                }
                if (m.getParameterCount() != args.length) {
                    continue;
                }
                method = m;
                method.setAccessible(true);
                return method.invoke(target, args);
            }
            throw ex;
        }
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
