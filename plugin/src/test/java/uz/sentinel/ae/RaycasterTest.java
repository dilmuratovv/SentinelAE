package uz.sentinel.ae;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RaycasterTest {
    private static final class Blocks implements Raycaster.Occluder {
        final Set<Long> solid = new HashSet<>();
        int lookups;
        Blocks add(int x, int y, int z) { solid.add(key(x, y, z)); return this; }
        Blocks wall(int z, int x0, int x1, int y0, int y1) {
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) add(x, y, z);
            return this;
        }
        static long key(int x, int y, int z) { return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF); }
        @Override public boolean occludes(int x, int y, int z) { lookups++; return solid.contains(key(x, y, z)); }
    }

    @Test void emptyWorldIsClear() {
        assertTrue(Raycaster.clear(0.5, 81.6, 0.5, 0.5, 80.9, 10.5, new Blocks()));
    }

    @Test void wallBlocks() {
        Blocks b = new Blocks().wall(5, -3, 3, 78, 85);
        assertFalse(Raycaster.clear(0.5, 81.6, 0.5, 0.5, 80.9, 10.5, b));
    }

    @Test void holeInWallLetsRayThrough() {
        Blocks b = new Blocks().wall(5, -3, 3, 78, 85);
        b.solid.remove(Blocks.key(0, 81, 5));
        assertTrue(Raycaster.clear(0.5, 81.6, 0.5, 0.5, 81.4, 10.5, b));
    }

    @Test void startVoxelIsIgnored() {
        Blocks b = new Blocks().add(0, 81, 0);
        assertTrue(Raycaster.clear(0.5, 81.6, 0.5, 0.5, 81.6, 10.5, b));
    }

    @Test void endVoxelIsTested() {
        Blocks b = new Blocks().add(0, 81, 10);
        assertFalse(Raycaster.clear(0.5, 81.6, 0.5, 0.5, 81.6, 10.5, b));
    }

    @Test void negativeCoordinatesAndDirections() {
        Blocks b = new Blocks().wall(-5, -10, 10, 60, 70);
        assertFalse(Raycaster.clear(-0.5, 65.2, -0.5, -3.7, 64.1, -9.2, b));
        assertTrue(Raycaster.clear(-0.5, 65.2, -0.5, -3.7, 64.1, -3.2, b)); // voxel z=-5 spans [-5,-4)
    }

    @Test void rayPassingWallEdgeIsClear() {
        // wall covers x <= 5 at z = 7; eye at x 5.5, target point well right of the corner's shadow
        Blocks b = new Blocks().wall(7, 0, 5, 80, 85);
        assertTrue(Raycaster.clear(5.5, 81.62, 2.5, 7.0, 81.0, 12.5, b));
        assertFalse(Raycaster.clear(5.5, 81.62, 2.5, 6.4, 81.0, 12.5, b));
    }

    @Test void visitsEveryVoxelOnTheLineAndStops() {
        Blocks b = new Blocks();
        assertTrue(Raycaster.clear(0.5, 0.5, 0.5, 20.5, 0.5, 0.5, b));
        assertEquals(20, b.lookups);
    }

    @Test void diagonalTraversalMatchesBruteForce() {
        java.util.Random r = new java.util.Random(7);
        for (int n = 0; n < 2000; n++) {
            Blocks b = new Blocks();
            for (int k = 0; k < 40; k++) b.add(r.nextInt(12) - 6, r.nextInt(12) - 6, r.nextInt(12) - 6);
            double sx = r.nextDouble() * 10 - 5, sy = r.nextDouble() * 10 - 5, sz = r.nextDouble() * 10 - 5;
            double ex = r.nextDouble() * 10 - 5, ey = r.nextDouble() * 10 - 5, ez = r.nextDouble() * 10 - 5;
            assertEquals(brute(sx, sy, sz, ex, ey, ez, b), Raycaster.clear(sx, sy, sz, ex, ey, ez, b),
                    "ray " + sx + "," + sy + "," + sz + " -> " + ex + "," + ey + "," + ez);
        }
    }

    /** Dense sampling: blocked when any sample (except inside the start voxel) lies in a solid voxel. */
    private static boolean brute(double sx, double sy, double sz, double ex, double ey, double ez, Blocks b) {
        int x0 = Raycaster.floor(sx), y0 = Raycaster.floor(sy), z0 = Raycaster.floor(sz);
        int steps = 20000;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = Raycaster.floor(sx + (ex - sx) * t), y = Raycaster.floor(sy + (ey - sy) * t), z = Raycaster.floor(sz + (ez - sz) * t);
            if (x == x0 && y == y0 && z == z0) continue;
            if (b.solid.contains(Blocks.key(x, y, z))) return false;
        }
        return true;
    }
}
