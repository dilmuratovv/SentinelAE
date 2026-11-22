package uz.sentinel.ae;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SamplePointsTest {
    private static final double[] PLAYER = {9.7, 80, 12.2, 10.3, 81.8, 12.8};

    @Test void fifteenPointsCentreFirst() {
        double[] out = new double[SamplePoints.COUNT * 3];
        SamplePoints.fill(PLAYER, new double[3], 0, out);
        assertEquals(10.0, out[0], 1e-9);
        assertEquals(80.9, out[1], 1e-9);
        assertEquals(12.5, out[2], 1e-9);
        for (int i = 0; i < out.length; i += 3) {
            assertTrue(out[i] >= 9.7 - 1e-9 && out[i] <= 10.3 + 1e-9);
            assertTrue(out[i + 1] > 80 && out[i + 1] < 81.8);
        }
    }

    @Test void marginWidensCorners() {
        double[] out = new double[SamplePoints.COUNT * 3];
        SamplePoints.fill(PLAYER, new double[3], 0.25, out);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        for (int i = 0; i < out.length; i += 3) { minX = Math.min(minX, out[i]); maxX = Math.max(maxX, out[i]); }
        assertEquals(9.45, minX, 1e-9);
        assertEquals(10.55, maxX, 1e-9);
    }

    @Test void lookAheadStretchesTowardsMovement() {
        double[] out = new double[SamplePoints.COUNT * 3];
        SamplePoints.fill(PLAYER, new double[]{0.9, 0, 0}, 0, out);
        double maxX = -Double.MAX_VALUE, minX = Double.MAX_VALUE;
        for (int i = 0; i < out.length; i += 3) { maxX = Math.max(maxX, out[i]); minX = Math.min(minX, out[i]); }
        assertEquals(11.2, maxX, 1e-9);
        assertEquals(9.7, minX, 1e-9, "the current position stays covered");
    }

    @Test void intervalsGrowWithDistanceAndConcealedPairsAreFaster() {
        assertEquals(1, SamplePoints.interval(3, true));
        assertEquals(4, SamplePoints.interval(3, false));
        assertTrue(SamplePoints.interval(40, true) > SamplePoints.interval(10, true));
        assertTrue(SamplePoints.interval(40, false) <= 10);
    }
}
