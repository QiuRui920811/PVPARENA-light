package com.pvparena.manager;

import com.pvparena.config.KitsConfig;
import com.pvparena.model.ModeKit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class KitManager {
    private final KitsConfig kitsConfig;
    // Folia runs events/tasks on different region threads.
    // Use an atomic map swap on reload to avoid readers seeing a partially-cleared map.
    private volatile Map<String, ModeKit> kits = new ConcurrentHashMap<>();

    public KitManager(KitsConfig kitsConfig) {
        this.kitsConfig = kitsConfig;
        load();
    }

    public void load() {
        Map<String, ModeKit> loaded = new HashMap<>();
        ConfigurationSection section = kitsConfig.getConfig().getConfigurationSection("kits");
        if (section == null) {
            kits = new ConcurrentHashMap<>();
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection kitSection = section.getConfigurationSection(id);
            if (kitSection == null) {
                continue;
            }
            List<ItemStack> items = getItemList(kitSection.getList("items"));
            List<ItemStack> armor = getItemList(kitSection.getList("armor"));
            List<PotionEffect> effects = getPotionList(kitSection.getList("effects"));
            items.removeIf(item -> isArmor(item));
            loaded.put(id.toLowerCase(), new ModeKit(items, armor, effects));
        }
        kits = new ConcurrentHashMap<>(loaded);
    }

    private boolean isArmor(ItemStack item) {
        if (item == null) {
            return false;
        }
        String name = item.getType().name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
    }

    @SuppressWarnings("unchecked")
    private List<ItemStack> getItemList(List<?> list) {
        List<ItemStack> result = new ArrayList<>();
        if (list == null) {
            return result;
        }
        for (Object obj : list) {
            if (obj instanceof ItemStack stack) {
                result.add(stack);
            } else if (obj instanceof Map<?, ?> map) {
                try {
                    result.add(ItemStack.deserialize((Map<String, Object>) map));
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<PotionEffect> getPotionList(List<?> list) {
        List<PotionEffect> result = new ArrayList<>();
        if (list == null) {
            return result;
        }
        for (Object obj : list) {
            if (obj instanceof PotionEffect effect) {
                result.add(effect);
            } else if (obj instanceof Map<?, ?> map) {
                PotionEffect effect = deserializeEffect(map);
                if (effect != null) {
                    result.add(effect);
                }
            }
        }
        return result;
    }

    private PotionEffect deserializeEffect(Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        Object typeObj = map.get("effect");
        if (typeObj == null) {
            typeObj = map.get("type");
        }
        if (typeObj == null) {
            return null;
        }
        String raw = typeObj.toString();
        PotionEffectType type = PotionEffectType.getByName(raw.toUpperCase());
        if (type == null && raw.contains(":")) {
            NamespacedKey key = NamespacedKey.fromString(raw);
            if (key != null) {
                type = PotionEffectType.getByKey(key);
            }
        }
        if (type == null) {
            return null;
        }
        int duration = getInt(map, "duration", 200);
        int amplifier = getInt(map, "amplifier", 0);
        boolean ambient = getBoolean(map, "ambient", false);
        boolean particles = getBoolean(map, "has-particles", true);
        if (map.containsKey("particles")) {
            particles = getBoolean(map, "particles", true);
        }
        boolean icon = getBoolean(map, "has-icon", true);
        if (map.containsKey("icon")) {
            icon = getBoolean(map, "icon", true);
        }
        return new PotionEffect(type, duration, amplifier, ambient, particles, icon);
    }

    private int getInt(Map<?, ?> map, String key, int def) {
        Object value = map.get(key);
        return value == null ? def : Integer.parseInt(value.toString());
    }

    private boolean getBoolean(Map<?, ?> map, String key, boolean def) {
        Object value = map.get(key);
        return value == null ? def : Boolean.parseBoolean(value.toString());
    }

    public ModeKit getKit(String id) {
        return kits.get(id.toLowerCase());
    }

    public boolean deleteKit(String id) {
        String key = id.toLowerCase();
        if (!kits.containsKey(key)) {
            return false;
        }
        kits.remove(key);
        ConfigurationSection kitsSection = kitsConfig.getConfig().getConfigurationSection("kits");
        if (kitsSection != null) {
            kitsSection.set(key, null);
            kitsConfig.save();
        }
        return true;
    }

    public java.util.Set<String> getKitIds() {
        return java.util.Collections.unmodifiableSet(kits.keySet());
    }

    public void saveKit(String id, ModeKit kit) {
        ConfigurationSection kitsSection = kitsConfig.getConfig().getConfigurationSection("kits");
        if (kitsSection == null) {
            kitsSection = kitsConfig.getConfig().createSection("kits");
        }
        ConfigurationSection kitSection = kitsSection.getConfigurationSection(id.toLowerCase());
        if (kitSection == null) {
            kitSection = kitsSection.createSection(id.toLowerCase());
        }
        kitSection.set("items", kit.getItems());
        kitSection.set("armor", kit.getArmor());
        kitSection.set("effects", kit.getPotionEffects());
        kitsConfig.save();
        kits.put(id.toLowerCase(), kit);
    }
}
