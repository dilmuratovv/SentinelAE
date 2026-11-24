package uz.sentinel.ae;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/** Validated configuration. Every number is clamped to a safe range. */
public record Settings(Set<EntityType> types, double maxDistance, int lookaheadTicks, int maxPingTicks, double boxMargin,
                       int pairsPerTick, long maxNanosPerTick, double secondaryShare, int failOpenTicks, int concealAfter,
                       boolean skipHolograms, Set<Material> transparent, boolean debug, boolean apiOnly,
                       boolean storageEsp, Set<Material> storageBlocks, double storageRevealDistance, int storageCheckTicks,
                       int storageMicrosPerTick, int storageRehideTicks, boolean hideSounds) {

    /** Groups of entity kinds that ESP and base finders draw. */
    public static final List<String> DEFAULT_GROUPS = List.of("players", "armor-stands", "item-frames", "hanging", "vehicles",
            "projectiles", "pets", "items", "villagers");

    public static final List<String> DEFAULT_STORAGE = List.of("CHEST", "TRAPPED_CHEST", "BARREL", "ENDER_CHEST", "SHULKER_BOX",
            "WHITE_SHULKER_BOX", "ORANGE_SHULKER_BOX", "MAGENTA_SHULKER_BOX", "LIGHT_BLUE_SHULKER_BOX", "YELLOW_SHULKER_BOX",
            "LIME_SHULKER_BOX", "PINK_SHULKER_BOX", "GRAY_SHULKER_BOX", "LIGHT_GRAY_SHULKER_BOX", "CYAN_SHULKER_BOX",
            "PURPLE_SHULKER_BOX", "BLUE_SHULKER_BOX", "BROWN_SHULKER_BOX", "GREEN_SHULKER_BOX", "RED_SHULKER_BOX",
            "BLACK_SHULKER_BOX", "HOPPER", "DROPPER", "DISPENSER", "FURNACE", "BLAST_FURNACE", "SMOKER", "BREWING_STAND",
            "SPAWNER", "CRAFTER", "BEACON", "DECORATED_POT", "CHISELED_BOOKSHELF", "JUKEBOX", "LECTERN");

    public static Settings from(ConfigurationSection c, Consumer<String> warn) {
        Set<EntityType> types = EnumSet.noneOf(EntityType.class);
        List<String> groups = c.isList("protected") ? c.getStringList("protected") : DEFAULT_GROUPS;
        for (String g : groups) {
            if (!EntityGroups.add(g.trim().toLowerCase(Locale.ROOT), types)) warn.accept("Unknown group in protected: " + g);
        }
        for (String name : c.getStringList("protected-types")) {
            try { types.add(EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException e) { warn.accept("Unknown entity type in protected-types: " + name); }
        }
        Set<Material> transparent = materials(c.getStringList("transparent-blocks"), warn, "transparent-blocks", true);
        List<String> storage = c.isList("storage-esp.blocks") ? c.getStringList("storage-esp.blocks") : DEFAULT_STORAGE;
        Set<Material> storageBlocks = materials(storage, warn, "storage-esp.blocks", false);
        return new Settings(types,
                clamp(c.getDouble("max-distance", 128), 8, 256),
                (int) clamp(c.getInt("lookahead-ticks", 3), 0, 10),
                (int) clamp(c.getInt("max-ping-lookahead-ticks", 6), 0, 20),
                clamp(c.getDouble("box-margin", 0.25), 0, 1.5),
                (int) clamp(c.getInt("pairs-per-tick", 768), 16, 20000),
                (long) clamp(c.getInt("max-micros-per-tick", 2500), 200, 20000) * 1000L,
                clamp(c.getDouble("secondary-share", 0.4), 0.05, 0.95),
                (int) clamp(c.getInt("fail-open-ticks", 4), 1, 40),
                (int) clamp(c.getInt("conceal-after-checks", 2), 1, 10),
                c.getBoolean("skip-holograms", true),
                transparent,
                c.getBoolean("debug", false),
                "api".equalsIgnoreCase(c.getString("tracker-mode", "auto")),
                c.getBoolean("storage-esp.enabled", true),
                storageBlocks,
                clamp(c.getDouble("storage-esp.reveal-distance", 48), 8, 128),
                (int) clamp(c.getInt("storage-esp.check-ticks", 3), 1, 40),
                (int) clamp(c.getInt("storage-esp.max-micros-per-tick", 1500), 100, 20000),
                (int) clamp(c.getInt("storage-esp.rehide-after-ticks", 200), 0, 72000),
                c.getBoolean("hide-sounds", true));
    }

    private static Set<Material> materials(List<String> names, Consumer<String> warn, String key, boolean report) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material m = Material.matchMaterial(name.trim());
            if (m == null) { if (report) warn.accept("Unknown material in " + key + ": " + name); }
            else set.add(m);
        }
        return set;
    }

    static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    /** Kept for older configurations that only list entity types. */
    public static List<String> defaultTypes() { return List.of("PLAYER", "ARMOR_STAND"); }

    /** Players are checked first; everything else shares what is left of the budget. */
    public boolean primary(EntityType t) { return t == EntityType.PLAYER; }
}
