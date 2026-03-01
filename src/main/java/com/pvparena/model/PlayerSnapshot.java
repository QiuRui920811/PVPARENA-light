package com.pvparena.model;

import com.pvparena.util.LocationUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerSnapshot {
    private ItemStack[] contents;
    private ItemStack[] armor;
    private ItemStack offhand;
    private Collection<PotionEffect> effects;
    private double health;
    private int food;
    private float saturation;
    private float exp;
    private int level;
    private GameMode gameMode;
    private Location location;
    private double maxHealthBase;
    private boolean allowFlight;
    private boolean flying;
    private boolean invulnerable;
    private boolean invisible;
    private boolean collidable;
    private boolean canPickupItems;
    private boolean silent;

    public PlayerSnapshot(Player player) {
        this.contents = cloneItems(player.getInventory().getContents());
        this.armor = cloneItems(player.getInventory().getArmorContents());
        this.offhand = player.getInventory().getItemInOffHand() != null ? player.getInventory().getItemInOffHand().clone() : null;
        this.effects = cloneEffects(player.getActivePotionEffects());
        this.health = player.getHealth();
        this.food = player.getFoodLevel();
        this.saturation = player.getSaturation();
        this.exp = player.getExp();
        this.level = player.getLevel();
        this.gameMode = player.getGameMode();
        this.location = player.getLocation();
        this.allowFlight = player.getAllowFlight();
        this.flying = player.isFlying();
        this.invulnerable = player.isInvulnerable();
        this.invisible = player.isInvisible();
        this.collidable = player.isCollidable();
        this.canPickupItems = player.getCanPickupItems();
        this.silent = player.isSilent();
        if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            this.maxHealthBase = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue();
        } else {
            this.maxHealthBase = 20.0;
        }
    }

    private PlayerSnapshot() {
    }

    public void restore(Player player) {
        player.getInventory().setContents(contents != null ? cloneItems(contents) : new ItemStack[0]);
        player.getInventory().setArmorContents(armor != null ? cloneItems(armor) : new ItemStack[4]);
        player.getInventory().setItemInOffHand(offhand != null ? offhand.clone() : null);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        if (effects != null) {
            for (PotionEffect effect : cloneEffects(effects)) {
                player.addPotionEffect(effect);
            }
        }
        if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(maxHealthBase);
        }
        double safeHealth = health;
        if (Double.isNaN(safeHealth) || safeHealth <= 0.0) {
            safeHealth = 1.0;
        }
        player.setHealth(Math.min(player.getMaxHealth(), safeHealth));
        player.setFoodLevel(food);
        player.setSaturation(saturation);
        player.setExp(exp);
        player.setLevel(level);
        player.setGameMode(gameMode);
        player.setAllowFlight(allowFlight);
        player.setFlying(flying);
        try {
            player.setInvulnerable(invulnerable);
        } catch (Throwable ignored) {
        }
        try {
            player.setInvisible(invisible);
        } catch (Throwable ignored) {
        }
        try {
            player.setCollidable(collidable);
        } catch (Throwable ignored) {
        }
        try {
            player.setCanPickupItems(canPickupItems);
        } catch (Throwable ignored) {
        }
        try {
            player.setSilent(silent);
        } catch (Throwable ignored) {
        }
        try {
            player.updateInventory();
        } catch (Throwable ignored) {
        }
    }

    private static ItemStack[] cloneItems(ItemStack[] source) {
        if (source == null) {
            return new ItemStack[0];
        }
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] != null ? source[i].clone() : null;
        }
        return copy;
    }

    private static Collection<PotionEffect> cloneEffects(Collection<PotionEffect> source) {
        List<PotionEffect> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (PotionEffect effect : source) {
            if (effect == null) {
                continue;
            }
            copy.add(new PotionEffect(
                    effect.getType(),
                    effect.getDuration(),
                    effect.getAmplifier(),
                    effect.isAmbient(),
                    effect.hasParticles(),
                    effect.hasIcon()
            ));
        }
        return copy;
    }

    public Location getLocation() {
        return location;
    }

    public String debugFingerprint() {
        int inv = countNonAir(contents);
        int arm = countNonAir(armor);
        String off = (offhand == null || offhand.getType().isAir()) ? "AIR" : offhand.getType().name();
        return "inv=" + inv + ",armor=" + arm + ",offhand=" + off + ",lvl=" + level + ",gm=" + gameMode;
    }

    private static int countNonAir(ItemStack[] items) {
        if (items == null) {
            return 0;
        }
        int c = 0;
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                c++;
            }
        }
        return c;
    }

    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("contents_b64", serializeItems(contents));
        map.put("armor_b64", serializeItems(armor));
        map.put("offhand_b64", serializeItems(new ItemStack[]{offhand}));
        map.put("effects", serializeEffects(effects));
        map.put("health", health);
        map.put("food", food);
        map.put("saturation", saturation);
        map.put("exp", exp);
        map.put("level", level);
        map.put("gameMode", gameMode.name());
        map.put("location", location != null ? LocationUtil.serialize(location) : null);
        map.put("maxHealthBase", maxHealthBase);
        map.put("allowFlight", allowFlight);
        map.put("flying", flying);
        map.put("invulnerable", invulnerable);
        map.put("invisible", invisible);
        map.put("collidable", collidable);
        map.put("canPickupItems", canPickupItems);
        map.put("silent", silent);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static PlayerSnapshot deserialize(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        try {
            PlayerSnapshot snapshot = new PlayerSnapshot();
            if (map.containsKey("contents_b64")) {
                snapshot.contents = deserializeItems(String.valueOf(map.get("contents_b64")));
                snapshot.armor = deserializeItems(String.valueOf(map.get("armor_b64")));
                Object offhandObj = map.get("offhand_b64");
                if (offhandObj != null) {
                    ItemStack[] off = deserializeItems(String.valueOf(offhandObj));
                    snapshot.offhand = off.length > 0 ? off[0] : null;
                }
            } else {
                Object contentsObj = map.get("contents");
                if (contentsObj instanceof List<?> list) {
                    snapshot.contents = deserializeItemList(list);
                } else {
                    snapshot.contents = (ItemStack[]) contentsObj;
                }
                Object armorObj = map.get("armor");
                if (armorObj instanceof List<?> list) {
                    snapshot.armor = deserializeItemList(list);
                } else {
                    snapshot.armor = (ItemStack[]) armorObj;
                }
                Object offhandObj = map.get("offhand");
                if (offhandObj instanceof ItemStack stack) {
                    snapshot.offhand = stack;
                } else if (offhandObj instanceof Map<?, ?> offMap) {
                    try {
                        snapshot.offhand = ItemStack.deserialize((Map<String, Object>) offMap);
                    } catch (Exception ignored) {
                    }
                }
            }
            snapshot.effects = deserializeEffects(map.get("effects"));
            snapshot.health = Double.parseDouble(map.get("health").toString());
            snapshot.food = Integer.parseInt(map.get("food").toString());
            snapshot.saturation = Float.parseFloat(map.get("saturation").toString());
            snapshot.exp = Float.parseFloat(map.get("exp").toString());
            snapshot.level = Integer.parseInt(map.get("level").toString());
            snapshot.gameMode = GameMode.valueOf(map.get("gameMode").toString());
            Object locObj = map.get("location");
            if (locObj instanceof Map<?, ?> locMap) {
                snapshot.location = LocationUtil.deserialize(asSectionMap(locMap));
            } else if (locObj instanceof Location loc) {
                snapshot.location = loc;
            }
            snapshot.maxHealthBase = Double.parseDouble(map.get("maxHealthBase").toString());
            snapshot.allowFlight = Boolean.parseBoolean(String.valueOf(map.getOrDefault("allowFlight", false)));
            snapshot.flying = Boolean.parseBoolean(String.valueOf(map.getOrDefault("flying", false)));
            snapshot.invulnerable = Boolean.parseBoolean(String.valueOf(map.getOrDefault("invulnerable", false)));
            snapshot.invisible = Boolean.parseBoolean(String.valueOf(map.getOrDefault("invisible", false)));
            snapshot.collidable = Boolean.parseBoolean(String.valueOf(map.getOrDefault("collidable", true)));
            snapshot.canPickupItems = Boolean.parseBoolean(String.valueOf(map.getOrDefault("canPickupItems", true)));
            snapshot.silent = Boolean.parseBoolean(String.valueOf(map.getOrDefault("silent", false)));
            return snapshot;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String serializeItems(ItemStack[] items) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos);
            if (items == null) {
                oos.writeInt(0);
            } else {
                oos.writeInt(items.length);
                for (ItemStack item : items) {
                    oos.writeObject(item);
                }
            }
            oos.close();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception ex) {
            return "";
        }
    }

    private static ItemStack[] deserializeItems(String data) {
        if (data == null || data.isEmpty()) {
            return new ItemStack[0];
        }
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream ois = new BukkitObjectInputStream(bais);
            int size = ois.readInt();
            ItemStack[] items = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                items[i] = (ItemStack) ois.readObject();
            }
            ois.close();
            return items;
        } catch (Exception ex) {
            return new ItemStack[0];
        }
    }

    private static List<Map<String, Object>> serializeEffects(Collection<PotionEffect> effects) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (effects == null) {
            return list;
        }
        for (PotionEffect effect : effects) {
            list.add(effect.serialize());
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Collection<PotionEffect> deserializeEffects(Object obj) {
        List<PotionEffect> list = new ArrayList<>();
        if (!(obj instanceof List<?> rawList)) {
            return list;
        }
        for (Object entry : rawList) {
            if (entry instanceof Map<?, ?> map) {
                try {
                    Object typeObj = map.get("type");
                    Object durationObj = map.get("duration");
                    Object amplifierObj = map.get("amplifier");
                    Object ambientObj = map.get("ambient");
                    Object particlesObj = map.get("particles");
                    if (typeObj == null) {
                        continue;
                    }
                    PotionEffectType type = PotionEffectType.getByName(typeObj.toString());
                    if (type == null) {
                        continue;
                    }
                    int duration = durationObj != null ? Integer.parseInt(durationObj.toString()) : 200;
                    int amplifier = amplifierObj != null ? Integer.parseInt(amplifierObj.toString()) : 0;
                    boolean ambient = ambientObj != null && Boolean.parseBoolean(ambientObj.toString());
                    boolean particles = particlesObj == null || Boolean.parseBoolean(particlesObj.toString());
                    list.add(new PotionEffect(type, duration, amplifier, ambient, particles));
                } catch (Exception ignored) {
                }
            }
        }
        return list;
    }

    private static ItemStack[] deserializeItemList(List<?> list) {
        List<ItemStack> items = new ArrayList<>();
        for (Object obj : list) {
            if (obj instanceof ItemStack stack) {
                items.add(stack);
            } else if (obj instanceof Map<?, ?> map) {
                try {
                    items.add(ItemStack.deserialize((Map<String, Object>) map));
                } catch (Exception ignored) {
                }
            }
        }
        return items.toArray(new ItemStack[0]);
    }

    @SuppressWarnings("unchecked")
    private static org.bukkit.configuration.ConfigurationSection asSectionMap(Map<?, ?> map) {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.createSection("loc", (Map<String, Object>) map);
        return yaml.getConfigurationSection("loc");
    }
}
