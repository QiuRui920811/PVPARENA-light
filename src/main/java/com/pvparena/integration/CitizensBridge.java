package com.pvparena.integration;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

public class CitizensBridge {
    private final JavaPlugin plugin;
    private final boolean available;

    public CitizensBridge(JavaPlugin plugin) {
        this.plugin = plugin;
        this.available = plugin != null
            && plugin.getServer().getPluginManager().isPluginEnabled("Citizens");
    }

    public boolean isAvailable() {
        return available;
    }

    public CitizensNpc spawnNpc(Location location, String name) {
        if (!available || location == null || location.getWorld() == null) {
            return null;
        }
        try {
            Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Method getRegistry = citizensApi.getMethod("getNPCRegistry");
            Object registry = getRegistry.invoke(null);
            Class<?> registryClass = Class.forName("net.citizensnpcs.api.npc.NPCRegistry");
            Method createNpc = registryClass.getMethod("createNPC", EntityType.class, String.class);
            Object npc = createNpc.invoke(registry, EntityType.PLAYER, name);
            Class<?> npcClass = Class.forName("net.citizensnpcs.api.npc.NPC");
            Method spawn = npcClass.getMethod("spawn", Location.class);
            spawn.invoke(npc, location);
            Method getEntity = npcClass.getMethod("getEntity");
            Object entity = getEntity.invoke(npc);
            if (entity instanceof LivingEntity living) {
                return new CitizensNpc(npc, living);
            }
        } catch (Exception ex) {
            if (plugin != null) {
                plugin.getLogger().warning("Citizens NPC spawn failed: " + ex.getMessage());
            }
        }
        return null;
    }

    public static class CitizensNpc {
        private final Object npc;
        private final LivingEntity entity;

        public CitizensNpc(Object npc, LivingEntity entity) {
            this.npc = npc;
            this.entity = entity;
        }

        public LivingEntity getEntity() {
            return entity;
        }

        public void setSpeed(double speed) {
            if (npc == null) {
                return;
            }
            try {
                Class<?> npcClass = Class.forName("net.citizensnpcs.api.npc.NPC");
                Method getNavigator = npcClass.getMethod("getNavigator");
                Object navigator = getNavigator.invoke(npc);
                if (navigator == null) {
                    return;
                }
                try {
                    Method setSpeed = navigator.getClass().getMethod("setSpeedModifier", float.class);
                    setSpeed.invoke(navigator, (float) speed);
                } catch (Exception ignored) {
                }
            } catch (Exception ignored) {
            }
        }

        public void setTarget(Player player) {
            if (npc == null || player == null) {
                return;
            }
            try {
                Class<?> npcClass = Class.forName("net.citizensnpcs.api.npc.NPC");
                Method getNavigator = npcClass.getMethod("getNavigator");
                Object navigator = getNavigator.invoke(npc);
                if (navigator == null) {
                    return;
                }
                try {
                    Method setTarget = navigator.getClass().getMethod("setTarget", org.bukkit.entity.Entity.class, boolean.class);
                    setTarget.invoke(navigator, player, true);
                    return;
                } catch (Exception ignored) {
                }
                try {
                    Method setTarget = navigator.getClass().getMethod("setTarget", org.bukkit.entity.Entity.class);
                    setTarget.invoke(navigator, player);
                    return;
                } catch (Exception ignored) {
                }
                try {
                    Method setTarget = navigator.getClass().getMethod("setTarget", Location.class);
                    setTarget.invoke(navigator, player.getLocation());
                } catch (Exception ignored) {
                }
            } catch (Exception ignored) {
            }
        }

        public void applyEquipment(ItemStack weapon, java.util.List<ItemStack> armor) {
            if (npc == null) {
                return;
            }
            try {
                Class<?> npcClass = Class.forName("net.citizensnpcs.api.npc.NPC");
                Method getOrAddTrait = npcClass.getMethod("getOrAddTrait", Class.class);
                Class<?> equipmentClass = Class.forName("net.citizensnpcs.api.trait.trait.Equipment");
                Object equipment = getOrAddTrait.invoke(npc, equipmentClass);
                Class<?> slotClass = Class.forName("net.citizensnpcs.api.trait.trait.Equipment$EquipmentSlot");
                Method set = equipmentClass.getMethod("set", slotClass, ItemStack.class);
                Method valueOf = slotClass.getMethod("valueOf", String.class);

                if (weapon != null) {
                    Object handSlot = null;
                    try {
                        handSlot = valueOf.invoke(null, "HAND");
                    } catch (Exception ignored) {
                    }
                    if (handSlot == null) {
                        try {
                            handSlot = valueOf.invoke(null, "MAINHAND");
                        } catch (Exception ignored) {
                        }
                    }
                    if (handSlot != null) {
                        set.invoke(equipment, handSlot, weapon.clone());
                    }
                }

                if (armor != null) {
                    for (ItemStack item : armor) {
                        if (item == null || item.getType().isAir()) {
                            continue;
                        }
                        String type = item.getType().name();
                        String slotName = null;
                        if (type.endsWith("_HELMET")) slotName = "HELMET";
                        else if (type.endsWith("_CHESTPLATE")) slotName = "CHESTPLATE";
                        else if (type.endsWith("_LEGGINGS")) slotName = "LEGGINGS";
                        else if (type.endsWith("_BOOTS")) slotName = "BOOTS";
                        if (slotName == null) {
                            continue;
                        }
                        try {
                            Object slot = valueOf.invoke(null, slotName);
                            set.invoke(equipment, slot, item.clone());
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        public void destroy() {
            if (npc == null) {
                return;
            }
            try {
                try {
                    Class<?> reasonClass = Class.forName("net.citizensnpcs.api.event.DespawnReason");
                    Method valueOf = reasonClass.getMethod("valueOf", String.class);
                    Object reason = valueOf.invoke(null, "PLUGIN");
                    Class<?> npcClass = Class.forName("net.citizensnpcs.api.npc.NPC");
                    Method despawn = npcClass.getMethod("despawn", reasonClass);
                    despawn.invoke(npc, reason);
                    return;
                } catch (Exception ignored) {
                }
                Class<?> npcClass = Class.forName("net.citizensnpcs.api.npc.NPC");
                Method destroy = npcClass.getMethod("destroy");
                destroy.invoke(npc);
            } catch (Exception ignored) {
            }
        }
    }
}
