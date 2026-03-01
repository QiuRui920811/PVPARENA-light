package com.pvparena.integration;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.MinecraftKey;
import com.comphenix.protocol.wrappers.WrappedParticle;
import com.pvparena.manager.Pvp18Manager;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class PvpPacketInterceptor {
    private final JavaPlugin plugin;
    private final Pvp18Manager pvp18Manager;
    private PacketAdapter adapter;

    public PvpPacketInterceptor(JavaPlugin plugin, Pvp18Manager pvp18Manager) {
        this.plugin = plugin;
        this.pvp18Manager = pvp18Manager;
    }

    public void register() {
        if (adapter != null) {
            return;
        }
        ProtocolManager manager = ProtocolLibrary.getProtocolManager();
        adapter = new PacketAdapter(plugin, ListenerPriority.NORMAL,
                PacketType.Play.Server.WORLD_PARTICLES,
            PacketType.Play.Server.NAMED_SOUND_EFFECT) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Player player = event.getPlayer();
                if (player == null || !pvp18Manager.isTaggedUuid(player.getUniqueId())) {
                    return;
                }
                PacketType type = event.getPacketType();
                PacketContainer packet = event.getPacket();
                if (type == PacketType.Play.Server.WORLD_PARTICLES) {
                    if (isBlockedParticle(packet)) {
                        event.setCancelled(true);
                    }
                    return;
                }
                if (type == PacketType.Play.Server.NAMED_SOUND_EFFECT
                        || type == PacketType.Play.Server.NAMED_SOUND_EFFECT) {
                    if (isBlockedSound(packet)) {
                        event.setCancelled(true);
                    }
                }
            }
        };
        manager.addPacketListener(adapter);
    }

    public void unregister() {
        if (adapter == null) {
            return;
        }
        ProtocolLibrary.getProtocolManager().removePacketListener(adapter);
        adapter = null;
    }

    private boolean isBlockedParticle(PacketContainer packet) {
        Object handle = packet.getModifier().readSafely(0);
        if (handle == null) {
            return false;
        }
        WrappedParticle<?> particle = WrappedParticle.fromHandle(handle);
        if (particle == null) {
            return false;
        }
        Particle type = particle.getParticle();
        return type == Particle.SWEEP_ATTACK
            || type == Particle.CRIT;
    }

    private boolean isBlockedSound(PacketContainer packet) {
        MinecraftKey key = packet.getModifier()
                .withType(MinecraftKey.class, MinecraftKey.getConverter())
                .readSafely(0);
        if (key == null) {
            return false;
        }
        String full = key.getFullKey();
        if (full == null) {
            full = key.getKey();
        }
        if (full == null) {
            return false;
        }
        return full.contains("attack.")
                || full.contains("attack_crit")
                || full.contains("attack.sweep");
    }
}