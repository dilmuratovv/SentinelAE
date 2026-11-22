package uz.sentinel.ae;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsTest {
    @Test void numbersAreClamped() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("max-distance", 100000);
        c.set("lookahead-ticks", -4);
        c.set("box-margin", 9);
        c.set("pairs-per-tick", 1);
        c.set("max-micros-per-tick", 5);
        c.set("conceal-after-checks", 0);
        Settings s = Settings.from(c, w -> { });
        assertEquals(256, s.maxDistance());
        assertEquals(0, s.lookaheadTicks());
        assertEquals(1.5, s.boxMargin());
        assertEquals(16, s.pairsPerTick());
        assertEquals(200_000, s.maxNanosPerTick());
        assertEquals(1, s.concealAfter());
    }

    @Test void defaultsWhenMissing() {
        Settings s = Settings.from(new YamlConfiguration(), w -> { });
        assertEquals(80, s.maxDistance());
        assertEquals(3, s.lookaheadTicks());
        assertEquals(2, s.concealAfter());
        assertTrue(s.skipHolograms());
    }

    @Test void unknownNamesAreReported() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("protected-types", List.of("PLAYER", "NOT_A_MOB"));
        List<String> warnings = new ArrayList<>();
        Settings s = Settings.from(c, warnings::add);
        assertEquals(1, s.types().size());
        assertEquals(1, warnings.size());
    }
}
