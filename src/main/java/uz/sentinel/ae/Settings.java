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
public record Settings(Set<EntityType> types, double maxDistance, int lookaheadTicks, double boxMargin,
                       int pairsPerTick, long maxNanosPerTick, int concealAfter, boolean skipHolograms,
                       Set<Material> transparent, boolean debug, boolean apiOnly) {

    public static Settings from(ConfigurationSection c, Consumer<String> warn) {
        Set<EntityType> types = EnumSet.noneOf(EntityType.class);
        for (String name : c.getStringList("protected-types")) {
            try { types.add(EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException e) { warn.accept("Unknown entity type in protected-types: " + name); }
        }
        Set<Material> transparent = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("transparent-blocks")) {
            Material m = Material.matchMaterial(name.trim());
            if (m == null) warn.accept("Unknown material in transparent-blocks: " + name); else transparent.add(m);
        }
        return new Settings(types,
                clamp(c.getDouble("max-distance", 80), 8, 256),
                (int) clamp(c.getInt("lookahead-ticks", 3), 0, 10),
                clamp(c.getDouble("box-margin", 0.25), 0, 1.5),
                (int) clamp(c.getInt("pairs-per-tick", 512), 16, 20000),
                (long) clamp(c.getInt("max-micros-per-tick", 2000), 200, 20000) * 1000L,
                (int) clamp(c.getInt("conceal-after-checks", 2), 1, 10),
                c.getBoolean("skip-holograms", true),
                transparent,
                c.getBoolean("debug", false),
                "api".equalsIgnoreCase(c.getString("tracker-mode", "auto")));
    }

    static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    /** Defaults used when nothing else is configured. */
    public static List<String> defaultTypes() { return List.of("PLAYER", "ARMOR_STAND"); }
}
