package uz.sentinel.ae;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

/**
 * Decides, for every viewer and nearby protected subject, whether the subject is in the viewer's line of sight, and
 * makes Paper send the subject only while it is.
 *
 * <p>With {@link TrackerAccess} (normal case) a concealed subject is simply not tracked for that viewer: the track
 * event is cancelled and the viewer is removed from the tracker's {@code seenBy}, so no spawn, movement, equipment or
 * metadata packet reaches the client. The tab list and other plugins' hide/show are untouched. Without it, the public
 * {@code hideEntity}/{@code showEntity} API is used. Main thread only.</p>
 */
final class VisibilityEngine {
    static final class Pair {
        final Player viewer;
        final Entity target;
        boolean concealed;
        boolean apiHidden;     // fallback mode: hidden through Player#hideEntity
        int blockedStreak;
        long due;
        boolean dead;
        double distance;
        Pair(Player viewer, Entity target) { this.viewer = viewer; this.target = target; }
    }

    private record Item(Pair pair, long due, long seq) {}

    private static final class Motion {
        long tick;
        double x, y, z, vx, vy, vz;
    }

    private static final class ViewerState {
        long bypassChecked = Long.MIN_VALUE;
        boolean bypass;
    }

    private final Plugin plugin;
    private final TrackerAccess access;
    private volatile Settings settings;
    private boolean[] occluding = new boolean[0];

    private final Map<UUID, Map<UUID, Pair>> byViewer = new HashMap<>();
    private final Map<UUID, Set<Pair>> byTarget = new HashMap<>();
    private final PriorityQueue<Item> queue = new PriorityQueue<>((a, b) ->
            a.due != b.due ? Long.compare(a.due, b.due) : Long.compare(a.seq, b.seq));
    private final Map<UUID, Motion> motion = new HashMap<>();
    private final Map<UUID, ViewerState> viewers = new HashMap<>();
    private final List<Pair> pendingApiHide = new ArrayList<>();
    private long seq;
    private long tick;
    private boolean busy;      // our own untrack/retrack calls fire track events; those follow the pair state
    private boolean warned;

    // statistics
    private long checks, rays, conceals, reveals, lagging, checkNanos, purges;
    private long lastTickNanos, maxTickNanos, sumTickNanos;
    private int sampledTicks;

    private final double[] box = new double[6], ahead = new double[3], points = new double[SamplePoints.COUNT * 3];
    private final Location la = new Location(null, 0, 0, 0), lb = new Location(null, 0, 0, 0);

    VisibilityEngine(Plugin plugin, Settings settings, TrackerAccess access) {
        this.plugin = plugin;
        this.access = access;
        apply(settings);
    }

    boolean direct() { return access != null; }

    void apply(Settings s) {
        settings = s;
        Material[] all = Material.values();
        boolean[] table = new boolean[all.length];
        for (Material m : all) {
            try {
                if (!m.isLegacy() && m.isBlock()) table[m.ordinal()] = m.isOccluding() && !s.transparent().contains(m);
            } catch (RuntimeException ignored) { /* materials without block data */ }
        }
        occluding = table;
        for (Map<UUID, Pair> m : byViewer.values()) for (Pair p : m.values()) schedule(p, tick + 1);
    }

    // ------------------------------------------------------------------ pairs

    private Pair pair(Player viewer, Entity target, boolean create) {
        Map<UUID, Pair> map = byViewer.get(viewer.getUniqueId());
        Pair p = map == null ? null : map.get(target.getUniqueId());
        if (p != null && (p.viewer != viewer || p.target != target)) { debug("drop (new object)", p); drop(p); p = null; } // relog
        if (p == null && create) {
            p = new Pair(viewer, target);
            byViewer.computeIfAbsent(viewer.getUniqueId(), k -> new HashMap<>()).put(target.getUniqueId(), p);
            byTarget.computeIfAbsent(target.getUniqueId(), k -> new HashSet<>()).add(p);
        }
        return p;
    }

    private void drop(Pair p) {
        if (p.dead) return;
        p.dead = true;
        Map<UUID, Pair> map = byViewer.get(p.viewer.getUniqueId());
        if (map != null && map.get(p.target.getUniqueId()) == p) {
            map.remove(p.target.getUniqueId());
            if (map.isEmpty()) byViewer.remove(p.viewer.getUniqueId());
        }
        Set<Pair> set = byTarget.get(p.target.getUniqueId());
        if (set != null) { set.remove(p); if (set.isEmpty()) byTarget.remove(p.target.getUniqueId()); }
    }

    /** {@code due == 0} means "not scheduled"; an earlier schedule is never postponed. */
    private void schedule(Pair p, long due) {
        if (p.dead || (p.due != 0 && p.due <= due)) return;
        p.due = due;
        queue.add(new Item(p, due, seq++));
    }

    /** Re-checks every pair involving this entity on the next tick (teleport, game mode change...). */
    void touch(Entity entity) {
        Map<UUID, Pair> asViewer = byViewer.get(entity.getUniqueId());
        if (asViewer != null) for (Pair p : asViewer.values()) schedule(p, tick + 1);
        Set<Pair> asTarget = byTarget.get(entity.getUniqueId());
        if (asTarget != null) for (Pair p : asTarget) schedule(p, tick + 1);
        ViewerState vs = viewers.get(entity.getUniqueId());
        if (vs != null) vs.bypassChecked = Long.MIN_VALUE;
    }

    /** Forgets everything about an entity that left the world or a player that quit. */
    void forget(Entity entity) {
        UUID id = entity.getUniqueId();
        Map<UUID, Pair> asViewer = byViewer.get(id);
        if (asViewer != null) for (Pair p : new ArrayList<>(asViewer.values())) drop(p);
        Set<Pair> asTarget = byTarget.get(id);
        if (asTarget != null) for (Pair p : new ArrayList<>(asTarget)) drop(p);
        motion.remove(id);
        viewers.remove(id);
    }

    void forgetWorld(World world) { if (access != null) access.forgetWorld(world); }

    /**
     * Called from the track event, before Paper sends {@code target} to {@code viewer}. Returns false to refuse; the
     * caller then cancels the event and calls {@link #refused}.
     */
    boolean allowTrack(Player viewer, Entity target) {
        if (viewer == target || !settings.types().contains(target.getType())) return true;
        Pair p = pair(viewer, target, false);
        if (busy || p != null) return p == null || !p.concealed;
        p = pair(viewer, target, true);
        p.concealed = !visible(p);   // nothing was shown yet, so no hysteresis
        p.blockedStreak = p.concealed ? settings.concealAfter() : 0;
        if (p.concealed) conceals++;
        debug(p.concealed ? "new-pair concealed" : "new-pair shown", p);
        schedule(p, tick + SamplePoints.interval(p.distance, p.concealed));
        return !p.concealed;
    }

    /** After a cancelled track event: make sure the viewer gets no packets at all for the subject. */
    void refused(Player viewer, Entity target) {
        if (access != null) {
            try { access.purge(viewer, target); purges++; return; }
            catch (Throwable t) { warnOnce(t); }
        }
        Pair p = pair(viewer, target, false);
        if (p != null && !p.apiHidden) pendingApiHide.add(p); // cannot hide inside the tracker's own callback
    }

    /** Registers subjects that were already being sent when the plugin started. */
    void adopt(Player viewer, Entity target) {
        if (viewer == target || !settings.types().contains(target.getType())) return;
        schedule(pair(viewer, target, true), tick + 1);
    }

    // ------------------------------------------------------------------ scheduling

    void tick(long now) {
        tick = now;
        Settings s = settings;
        long start = System.nanoTime();
        if (!pendingApiHide.isEmpty()) {
            for (Pair p : pendingApiHide) if (!p.dead && p.concealed && !p.apiHidden) apiHide(p);
            pendingApiHide.clear();
        }
        int done = 0;
        boolean cut = false;
        while (!queue.isEmpty() && queue.peek().due <= now) {
            if (done >= s.pairsPerTick() || System.nanoTime() - start >= s.maxNanosPerTick()) { cut = true; break; }
            Item item = queue.poll();
            Pair p = item.pair;
            if (p.dead || item.due != p.due) continue;
            p.due = 0;
            long c0 = System.nanoTime();
            check(p);
            checkNanos += System.nanoTime() - c0;
            done++;
        }
        // Counting late pairs walks the queue, so only do it when the budget actually ran out.
        long late = 0;
        if (cut) for (Item it : queue) if (it.due <= now && !it.pair.dead && it.due == it.pair.due) late++;
        lagging = late;
        long took = System.nanoTime() - start;
        lastTickNanos = took;
        maxTickNanos = Math.max(maxTickNanos, took);
        sumTickNanos += took;
        if (++sampledTicks >= 200) { sampledTicks = 1; sumTickNanos = took; maxTickNanos = took; }
    }

    private void check(Pair p) {
        Player v = p.viewer;
        Entity t = p.target;
        if (!v.isOnline() || !t.isValid() || v.getWorld() != t.getWorld()) { drop(p); return; }
        checks++;
        boolean vis = visible(p);
        if (vis) {
            p.blockedStreak = 0;
            if (p.concealed) reveal(p);
        } else {
            p.blockedStreak++;
            if (!p.concealed && p.blockedStreak >= settings.concealAfter()) conceal(p);
        }
        if (p.dead) return;
        if (p.concealed) {
            // far away: forget the pair; if the subject comes back the tracker offers it again
            if (p.distance > settings.maxDistance() + 16 && !p.apiHidden) { drop(p); return; }
        } else if (!seen(p)) {
            debug("drop (not sent)", p);
            drop(p); // out of tracking range, or hidden by another plugin: not our business until tracked again
            return;
        }
        schedule(p, tick + SamplePoints.interval(p.distance, p.concealed));
    }

    /**
     * Whether the pair is still worth monitoring while shown. Direct mode asks the tracker; the fallback avoids
     * getTrackedBy() (it copies a set on every call) and keeps pairs until they are clearly out of range.
     */
    private boolean seen(Pair p) {
        if (access != null) return access.isSeen(p.viewer, p.target);
        if (!p.viewer.canSee(p.target)) return false;              // another plugin hides it
        if (p.distance < 24) return true;                           // inside every default tracking range
        return p.target.getTrackedBy().contains(p.viewer);          // rare: far pairs only
    }

    private void conceal(Pair p) {
        p.concealed = true;
        conceals++;
        debug("conceal", p);
        if (access != null) {
            busy = true;
            try { access.untrack(p.viewer, p.target); return; }
            catch (Throwable t) { warnOnce(t); }
            finally { busy = false; }
        }
        apiHide(p);
    }

    private void reveal(Pair p) {
        p.concealed = false;
        reveals++;
        debug("reveal", p);
        if (p.apiHidden) { apiShow(p); return; }
        if (access != null) {
            busy = true;
            try { access.retrack(p.viewer, p.target); }
            catch (Throwable t) { warnOnce(t); }
            finally { busy = false; }
        }
    }

    private void apiHide(Pair p) {
        if (!plugin.isEnabled() || !p.viewer.isOnline() || !p.target.isValid()) return;
        p.apiHidden = true;
        busy = true;
        try { p.viewer.hideEntity(plugin, p.target); } finally { busy = false; }
    }

    private void apiShow(Pair p) {
        p.apiHidden = false;
        if (!p.viewer.isOnline() || !p.target.isValid()) return;
        busy = true;
        try { p.viewer.showEntity(plugin, p.target); } finally { busy = false; }
    }

    private void debug(String what, Pair p) {
        if (settings.debug()) plugin.getLogger().info("[debug] t=" + tick + " " + what + " " + p.target.getName()
                + " for " + p.viewer.getName() + String.format(" d=%.1f", p.distance));
    }

    private void warnOnce(Throwable t) {
        if (warned) return;
        warned = true;
        plugin.getLogger().log(Level.WARNING, "Tracker access failed; falling back to hide/show for this case", t);
    }

    /** Lets every concealed subject be sent again (plugin disable). */
    void releaseAll() {
        for (Map<UUID, Pair> m : new ArrayList<>(byViewer.values())) {
            for (Pair p : new ArrayList<>(m.values())) {
                if (p.concealed && p.viewer.isOnline() && p.target.isValid()) {
                    p.concealed = false;
                    try {
                        if (p.apiHidden) apiShow(p);
                        else if (access != null) { busy = true; access.retrack(p.viewer, p.target); }
                    } catch (Throwable ignored) {
                        // shutting down: the client receives the entity after its next movement
                    } finally { busy = false; }
                }
                drop(p);
            }
        }
        queue.clear();
    }

    // ------------------------------------------------------------------ line of sight

    private boolean bypass(Player v) {
        if (v.getGameMode() == GameMode.SPECTATOR) return true;
        ViewerState st = viewers.computeIfAbsent(v.getUniqueId(), k -> new ViewerState());
        if (tick - st.bypassChecked >= 40) {
            st.bypass = v.hasPermission("sentinelae.bypass");
            st.bypassChecked = tick;
        }
        return st.bypass;
    }

    /** Per-tick velocity estimate from observed positions (players move client side, so getVelocity is unreliable). */
    private Motion motion(Entity e, double x, double y, double z) {
        Motion m = motion.get(e.getUniqueId());
        if (m == null) {
            m = new Motion();
            m.tick = tick; m.x = x; m.y = y; m.z = z;
            motion.put(e.getUniqueId(), m);
            return m;
        }
        if (m.tick != tick) {
            long dt = Math.max(1, tick - m.tick);
            if (dt > 20) { m.vx = m.vy = m.vz = 0; } // a stale sample says nothing about current motion
            else {
                m.vx = clampSpeed((x - m.x) / dt);
                m.vy = clampSpeed((y - m.y) / dt);
                m.vz = clampSpeed((z - m.z) / dt);
            }
            m.tick = tick; m.x = x; m.y = y; m.z = z;
        }
        return m;
    }

    /** Faster than sprint-jumping is either a teleport or flight; prediction then helps little and costs accuracy. */
    private static double clampSpeed(double v) { return Math.max(-0.8, Math.min(0.8, v)); }

    /** Teleports are not movement: the next sample must not turn the jump into a velocity. */
    void resetMotion(Entity e) { motion.remove(e.getUniqueId()); }

    /** True when the viewer may see the subject (or the subject must not be concealed at all). Sets {@code p.distance}. */
    boolean visible(Pair p) {
        Settings s = settings;
        Player v = p.viewer;
        Entity t = p.target;
        v.getLocation(la);
        t.getLocation(lb);
        p.distance = la.getWorld() == lb.getWorld() ? Math.sqrt(la.distanceSquared(lb)) : Double.MAX_VALUE;
        if (!s.types().contains(t.getType())) return true;
        if (bypass(v)) return true;
        if (!t.isVisibleByDefault() || t.isGlowing()) return true;
        if (v.getVehicle() == t || t.getVehicle() == v || t.getPassengers().contains(v)) return true;
        if (s.skipHolograms() && t instanceof ArmorStand stand && stand.isCustomNameVisible()
                && (stand.isMarker() || stand.isInvisible())) return true;
        if (p.distance == Double.MAX_VALUE || p.distance > s.maxDistance() || p.distance < 1.0) return true;

        double ex = la.getX(), ey = la.getY() + v.getEyeHeight(), ez = la.getZ();
        Motion tm = motion(t, lb.getX(), lb.getY(), lb.getZ()), vm = motion(v, la.getX(), la.getY(), la.getZ());
        BoundingBox b = t.getBoundingBox();
        int look = s.lookaheadTicks();
        box[0] = b.getMinX(); box[1] = b.getMinY(); box[2] = b.getMinZ();
        box[3] = b.getMaxX(); box[4] = b.getMaxY(); box[5] = b.getMaxZ();
        ahead[0] = tm.vx * look; ahead[1] = tm.vy * look; ahead[2] = tm.vz * look;
        Occluder occ = new Occluder(v.getWorld(), occluding);
        // A subject cannot walk through a wall, so a prediction that crosses one is discarded.
        if (ahead[0] != 0 || ahead[1] != 0 || ahead[2] != 0) {
            double cx = b.getCenterX(), cy = b.getCenterY(), cz = b.getCenterZ();
            if (!Raycaster.clear(cx, cy, cz, cx + ahead[0], cy + ahead[1], cz + ahead[2], occ)) ahead[0] = ahead[1] = ahead[2] = 0;
        }
        SamplePoints.fill(box, ahead, s.boxMargin(), points);

        if (clearFrom(ex, ey, ez, occ)) return true;
        double px = vm.vx * look, py = vm.vy * look, pz = vm.vz * look;
        if (px * px + py * py + pz * pz > 0.0025 && Raycaster.clear(ex, ey, ez, ex + px, ey + py, ez + pz, occ)) {
            return clearFrom(ex + px, ey + py, ez + pz, occ);
        }
        return false;
    }

    private boolean clearFrom(double ex, double ey, double ez, Occluder occ) {
        for (int i = 0; i < points.length; i += 3) {
            rays++;
            if (Raycaster.clear(ex, ey, ez, points[i], points[i + 1], points[i + 2], occ)) return true;
        }
        return false;
    }

    /** Reads blocks for the raycaster, caching the chunk-loaded test. Unloaded chunks never hide anything. */
    private static final class Occluder implements Raycaster.Occluder {
        private final World world;
        private final boolean[] table;
        private final int minY, maxY;
        private int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
        private boolean loaded;

        Occluder(World world, boolean[] table) {
            this.world = world; this.table = table;
            this.minY = world.getMinHeight(); this.maxY = world.getMaxHeight();
        }

        @Override public boolean occludes(int x, int y, int z) {
            if (y < minY || y >= maxY) return false;
            int ccx = x >> 4, ccz = z >> 4;
            if (ccx != cx || ccz != cz) { cx = ccx; cz = ccz; loaded = world.isChunkLoaded(ccx, ccz); }
            if (!loaded) return false;
            int ord = world.getType(x, y, z).ordinal();
            return ord < table.length && table[ord];
        }
    }

    // ------------------------------------------------------------------ status

    /** One-shot decision for /sae check. */
    String explain(Player viewer, Entity target) {
        Pair probe = new Pair(viewer, target);
        long before = rays;
        boolean vis = visible(probe);
        Pair live = pair(viewer, target, false);
        return target.getName() + " for " + viewer.getName() + ": " + (vis ? "visible" : "blocked")
                + " (" + (rays - before) + " rays, " + String.format("%.1f", probe.distance) + " blocks), state="
                + (live == null ? "not monitored" : live.concealed ? "concealed" : "shown")
                + ", sent=" + (access != null ? access.isSeen(viewer, target) : target.getTrackedBy().contains(viewer));
    }

    String status() {
        int monitored = 0, concealed = 0;
        for (Map<UUID, Pair> m : byViewer.values()) for (Pair p : m.values()) { monitored++; if (p.concealed) concealed++; }
        long avg = sampledTicks == 0 ? 0 : sumTickNanos / sampledTicks / 1000;
        return "active (" + (access != null ? "direct tracker" : "hide/show fallback") + "), concealed=" + concealed
                + ", monitored=" + monitored + ", checks=" + checks + ", rays=" + rays
                + ", us/check=" + (checks == 0 ? "0" : String.format("%.1f", checkNanos / 1000.0 / checks))
                + ", conceals=" + conceals + ", reveals=" + reveals + ", purges=" + purges + ", lagging=" + lagging
                + ", tick last=" + lastTickNanos / 1000 + "us avg=" + avg + "us max=" + maxTickNanos / 1000 + "us";
    }
}
