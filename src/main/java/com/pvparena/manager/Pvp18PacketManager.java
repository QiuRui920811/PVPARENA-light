package com.pvparena.manager;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.wrappers.WrappedParticle;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Pvp18PacketManager {
    private final Plugin plugin;
    private final ProtocolManager protocolManager;
    private final Set<UUID> arenaPlayers = ConcurrentHashMap.newKeySet();
    private PacketListener listener;

    public Pvp18PacketManager(Plugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    public void register() {
        if (listener != null) {
            return;
        }
        listener = new PacketAdapter(plugin, ListenerPriority.NORMAL, buildPacketTypes()) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Player player = event.getPlayer();
                if (player == null || !arenaPlayers.contains(player.getUniqueId())) {
                    return;
                }
                PacketType type = event.getPacketType();
                PacketContainer packet = event.getPacket();
                if (isParticlePacket(type)) {
                    if (isBlockedParticle(packet)) {
                        event.setCancelled(true);
                    }
                    return;
                }
                if (type == PacketType.Play.Server.ANIMATION) {
                    if (isBlockedAnimation(packet)) {
                        event.setCancelled(true);
                    }
                    return;
                }
                if (isSoundPacket(type)) {
                    if (isBlockedSound(type, player, packet)) {
                        event.setCancelled(true);
                    }
                }
            }
        };
        protocolManager.addPacketListener(listener);
    }

    public void shutdown() {
        if (listener != null) {
            protocolManager.removePacketListener(listener);
            listener = null;
        }
        arenaPlayers.clear();
    }

    public void mark(UUID playerId) {
        arenaPlayers.add(playerId);
    }

    public void unmark(UUID playerId) {
        arenaPlayers.remove(playerId);
    }

    public boolean isMarked(UUID playerId) {
        return arenaPlayers.contains(playerId);
    }

    private PacketType[] buildPacketTypes() {
        List<PacketType> types = new ArrayList<>();
        types.add(PacketType.Play.Server.WORLD_PARTICLES);
        types.add(PacketType.Play.Server.ANIMATION);
        types.add(PacketType.Play.Server.NAMED_SOUND_EFFECT);
        types.add(PacketType.Play.Server.ENTITY_SOUND);

        PacketType optionalSound = resolveOptionalServerPacket("SOUND_EFFECT");
        if (optionalSound != null) {
            types.add(optionalSound);
        }
        PacketType optionalLevelParticles = resolveOptionalServerPacket("LEVEL_PARTICLES");
        if (optionalLevelParticles != null) {
            types.add(optionalLevelParticles);
        }
        return types.toArray(new PacketType[0]);
    }

    private PacketType resolveOptionalServerPacket(String fieldName) {
        try {
            Field field = PacketType.Play.Server.class.getField(fieldName);
            Object value = field.get(null);
            if (value instanceof PacketType packetType) {
                return packetType;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isSoundPacket(PacketType type) {
        if (type == PacketType.Play.Server.NAMED_SOUND_EFFECT
                || type == PacketType.Play.Server.ENTITY_SOUND) {
            return true;
        }
        String name = type != null ? type.name() : "";
        return "SOUND_EFFECT".equalsIgnoreCase(name);
    }

    private boolean isParticlePacket(PacketType type) {
        if (type == PacketType.Play.Server.WORLD_PARTICLES) {
            return true;
        }
        String name = type != null ? type.name() : "";
        return "LEVEL_PARTICLES".equalsIgnoreCase(name);
    }

    private boolean isBlockedParticle(PacketContainer packet) {
        try {
            WrappedParticle wrapped = packet.getNewParticles().readSafely(0);
            if (wrapped != null) {
                Particle particle = wrapped.getParticle();
                if (particle == Particle.SWEEP_ATTACK
                        || particle == Particle.CRIT) {
                    debugParticle(true, particle.name().toLowerCase());
                    return true;
                }
                String key = particle.name().toLowerCase();
                boolean blocked = "crit".equals(key) || "sweep_attack".equals(key);
                debugParticle(blocked, key);
                return blocked;
            }
        } catch (Exception ignored) {
        }
        try {
            EnumWrappers.Particle particle = packet.getParticles().readSafely(0);
            if (particle != null) {
                String key = particle.name().toLowerCase();
                boolean blocked = "crit".equals(key) || "sweep_attack".equals(key);
                debugParticle(blocked, key);
                return blocked;
            }
        } catch (Exception ignored) {
        }
        try {
            Object raw = packet.getModifier().readSafely(0);
            if (raw != null) {
                String key = raw.toString().toLowerCase();
                boolean blocked = key.contains("sweep_attack")
                        || key.contains("minecraft:crit")
                        || key.contains("particle=crit")
                        || key.contains("particle{crit}");
                debugParticle(blocked, key);
                return blocked;
            }
        } catch (Exception ignored) {
        }
        try {
            String dump = packet.toString().toLowerCase();
            boolean blocked = dump.contains("sweep_attack")
                    || dump.contains("minecraft:crit")
                    || dump.contains("particle=crit")
                    || dump.contains("particle{crit}");
            debugParticle(blocked, dump);
            return blocked;
        } catch (Exception ignored) {
        }
        return false;
    }

    private void debugParticle(boolean blocked, String key) {
        try {
            if (!(plugin instanceof org.bukkit.plugin.java.JavaPlugin javaPlugin)) {
                return;
            }
            if (!javaPlugin.getConfig().getBoolean("debug.combat", false)) {
                return;
            }
            String lower = key == null ? "" : key.toLowerCase();
            if (!(lower.contains("crit") || lower.contains("sweep") || lower.contains("enchanted_hit"))) {
                return;
            }
            String out = lower.length() > 180 ? lower.substring(0, 180) + "..." : lower;
            javaPlugin.getLogger().info("[CombatParticle] blocked=" + blocked + " key=" + out.replace('\n', ' ').replace('\r', ' '));
        } catch (Throwable ignored) {
        }
    }

    private boolean isBlockedSound(PacketType type, Player player, PacketContainer packet) {
        String key = null;
        try {
            key = packet.getStrings().readSafely(0);
        } catch (Exception ignored) {
        }
        if (key == null) {
            try {
                Object raw = packet.getModifier().readSafely(0);
                if (raw != null) {
                    key = raw.toString();
                }
            } catch (Exception ignored) {
            }
        }
        if (key == null) {
            try {
                key = packet.toString();
            } catch (Exception ignored) {
            }
        }
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase();
        boolean blocked = lower.contains("entity.player.attack")
            || lower.contains("attack.")
            || lower.contains("attack_")
                || lower.contains("crit")
                || lower.contains("critical")
                || lower.contains("sweep");
        if (isInterestingSound(lower)) {
            debugSound(type, player, blocked, lower);
        }
        return blocked;
    }

    private boolean isInterestingSound(String lower) {
        return lower.contains("attack") || lower.contains("crit") || lower.contains("sweep");
    }

    private void debugSound(PacketType type, Player player, boolean blocked, String lower) {
        try {
            if (!(plugin instanceof org.bukkit.plugin.java.JavaPlugin javaPlugin)) {
                return;
            }
            if (!javaPlugin.getConfig().getBoolean("debug.combat", false)) {
                return;
            }
            String key = lower;
            if (key.length() > 220) {
                key = key.substring(0, 220) + "...";
            }
            String playerName = player != null ? player.getName() : "unknown";
            javaPlugin.getLogger().info("[CombatSound] player=" + playerName
                    + " type=" + (type != null ? type.name() : "null")
                    + " blocked=" + blocked
                    + " key=" + key.replace('\n', ' ').replace('\r', ' '));
        } catch (Throwable ignored) {
        }
    }

    private boolean isBlockedAnimation(PacketContainer packet) {
        try {
            Byte animation = packet.getBytes().readSafely(0);
            if (animation != null) {
                // Vanilla animation ids: 4=critical, 5=magic critical
                return animation == 4 || animation == 5;
            }
        } catch (Exception ignored) {
        }
        try {
            Integer animation0 = packet.getIntegers().readSafely(0);
            if (animation0 != null && (animation0 == 4 || animation0 == 5)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            Integer animation = packet.getIntegers().readSafely(1);
            if (animation != null) {
                return animation == 4 || animation == 5;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}