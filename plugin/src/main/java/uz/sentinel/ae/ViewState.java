package uz.sentinel.ae;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * What the network thread needs to know about one viewer, written by the main thread. Storage positions are packed
 * block keys ({@link Nms#key}), grouped by chunk so a chunk resend or unload can drop them at once.
 */
final class ViewState {
    final UUID id;
    volatile UUID world;
    volatile int minY;
    volatile boolean bypass;
    /** Storage blocks the client has as air. */
    final Map<Long, Set<Long>> hidden = new ConcurrentHashMap<>();
    /** Storage blocks the client has really (revealed after line of sight); updates for them pass. */
    final Map<Long, Set<Long>> revealed = new ConcurrentHashMap<>();
    /** Newly hidden positions for the main thread to check (chunk sends, block updates). */
    final ConcurrentLinkedQueue<long[]> fresh = new ConcurrentLinkedQueue<>();
    /** Blocks whose update was held back with a hidden one in the same packet: the main thread sends them. */
    final ConcurrentLinkedQueue<long[]> resend = new ConcurrentLinkedQueue<>();
    /** Concealed subjects: entity ids and positions (x, y, z per id), replaced as a whole every tick. */
    volatile int[] concealedIds = new int[0];
    volatile double[] concealedPos = new double[0];

    /** Main thread only: when each revealed storage block was last in sight (for hiding it again). */
    final Map<Long, Long> lastSeen = new java.util.HashMap<>();

    ViewState(UUID id) { this.id = id; }

    static long chunkOf(long key) { return Nms.chunkKey(Nms.keyX(key) >> 4, Nms.keyZ(key) >> 4); }

    boolean isHidden(long key) {
        Set<Long> s = hidden.get(chunkOf(key));
        return s != null && s.contains(key);
    }

    boolean isRevealed(long key) {
        Set<Long> s = revealed.get(chunkOf(key));
        return s != null && s.contains(key);
    }

    void hide(long key) {
        hidden.computeIfAbsent(chunkOf(key), k -> ConcurrentHashMap.newKeySet()).add(key);
        Set<Long> r = revealed.get(chunkOf(key));
        if (r != null) r.remove(key);
    }

    void unhide(long key) {
        Set<Long> s = hidden.get(chunkOf(key));
        if (s != null) s.remove(key);
    }

    void markRevealed(long key) {
        unhide(key);
        revealed.computeIfAbsent(chunkOf(key), k -> ConcurrentHashMap.newKeySet()).add(key);
    }

    void dropChunk(long chunk) { hidden.remove(chunk); revealed.remove(chunk); }

    void clear() { hidden.clear(); revealed.clear(); fresh.clear(); resend.clear(); lastSeen.clear(); }

    boolean concealedId(int entityId) {
        for (int i : concealedIds) if (i == entityId) return true;
        return false;
    }

    /** True when (x, y, z) is within the radius of a concealed living subject (x, y, z, radius per entry). */
    boolean nearConcealed(double x, double y, double z) {
        double[] p = concealedPos;
        for (int i = 0; i + 3 < p.length; i += 4) {
            double dx = p[i] - x, dy = p[i + 1] - y, dz = p[i + 2] - z, r = p[i + 3];
            if (dx * dx + dy * dy + dz * dz <= r * r) return true;
        }
        return false;
    }

    /** True when a hidden storage block is within one block of (x, y, z) (its open/close sound, its particles). */
    boolean nearHidden(double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (isHidden(Nms.key(bx + dx, by + dy, bz + dz))) return true;
        }
        return false;
    }
}
