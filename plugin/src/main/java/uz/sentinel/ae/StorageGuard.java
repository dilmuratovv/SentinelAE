package uz.sentinel.ae;

import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Storage ESP protection and the packet guards (main thread side). Every player gets a {@link PacketGuard} in their
 * connection. Storage blocks arrive as air; this class reveals each one with a real block change once the player has
 * line of sight to one of its exposed faces within {@code storage-reveal-distance}. A revealed block stays revealed
 * until its chunk is sent again (the client already knows it).
 */
final class StorageGuard {
    private final Plugin plugin;
    private final Nms nms;
    private final java.util.function.Supplier<boolean[]> occluding;
    private volatile Settings settings;
    private final Map<UUID, ViewState> views = new ConcurrentHashMap<>();
    private final Map<UUID, PacketGuard> guards = new ConcurrentHashMap<>();
    /** Block key -> entity id of who broke it, for two ticks (to drop a concealed breaker's break effects). */
    final Map<Long, Integer> breakers = new ConcurrentHashMap<>();
    private final Map<Long, Long> breakerExpiry = new HashMap<>();
    private long tick;
    private long reveals, rehides, probes, injected, failedInjections, rays;

    StorageGuard(Plugin plugin, Nms nms, Settings settings, java.util.function.Supplier<boolean[]> occluding) {
        this.plugin = plugin; this.nms = nms; this.settings = settings; this.occluding = occluding;
    }

    void apply(Settings s) { settings = s; for (PacketGuard g : guards.values()) g.apply(s); }

    ViewState view(Player p) { return views.get(p.getUniqueId()); }

    // ------------------------------------------------------------------ connection

    void inject(Player p) {
        ViewState view = views.computeIfAbsent(p.getUniqueId(), ViewState::new);
        refresh(p, view);
        Object raw = Nms.channel(p);
        if (!(raw instanceof Channel ch)) { failedInjections++; return; }
        PacketGuard guard = new PacketGuard(view, nms, settings, breakers);
        guards.put(p.getUniqueId(), guard);
        ch.eventLoop().execute(() -> {
            try {
                if (ch.pipeline().get(PacketGuard.NAME) != null) ch.pipeline().remove(PacketGuard.NAME);
                if (ch.pipeline().get("packet_handler") != null) ch.pipeline().addBefore("packet_handler", PacketGuard.NAME, guard);
                else ch.pipeline().addLast(PacketGuard.NAME, guard);
                injected++;
            } catch (RuntimeException e) {
                failedInjections++;
            }
        });
    }

    void remove(Player p) {
        views.remove(p.getUniqueId());
        guards.remove(p.getUniqueId());
        Object raw = Nms.channel(p);
        if (raw instanceof Channel ch && ch.isOpen()) ch.eventLoop().execute(() -> {
            try { if (ch.pipeline().get(PacketGuard.NAME) != null) ch.pipeline().remove(PacketGuard.NAME); } catch (RuntimeException ignored) { }
        });
    }

    void removeAll() { for (Player p : Bukkit.getOnlinePlayers()) remove(p); }

    /** Plugin disable: give every player the real storage blocks back, then leave their connections. */
    void releaseAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ViewState v = views.get(p.getUniqueId());
            if (v != null) { v.bypass = true; revealAll(p, v); }
        }
        removeAll();
    }

    private void refresh(Player p, ViewState v) {
        World w = p.getWorld();
        if (!w.getUID().equals(v.world)) { v.clear(); v.world = w.getUID(); v.minY = w.getMinHeight(); }
        v.bypass = p.getGameMode() == GameMode.SPECTATOR || p.hasPermission("sentinelae.bypass");
    }

    /** The client is about to get every chunk again (world change, respawn): forget what it had. */
    void resetView(Player p) {
        ViewState v = views.get(p.getUniqueId());
        if (v != null) { v.clear(); refresh(p, v); }
    }

    void chunkUnloaded(Player p, int cx, int cz) {
        ViewState v = views.get(p.getUniqueId());
        if (v != null) v.dropChunk(Nms.chunkKey(cx, cz));
    }

    /**
     * A player clicked (used or started breaking) a block. A legitimate client cannot click a storage block it was
     * sent as air or stone; a hacked one can send clicks at guessed positions within reach to find chests behind
     * walls. Unless the block is really in sight, the click is refused and nothing is revealed.
     */
    boolean refuseProbe(Player p, Block b) {
        if (!settings.storageEsp()) return false;
        ViewState v = views.get(p.getUniqueId());
        if (v == null || v.bypass) return false;
        long key = Nms.key(b.getX(), b.getY(), b.getZ());
        if (!v.isHidden(key)) return false;
        if (visible(p, b.getX(), b.getY(), b.getZ())) { sendReal(p, key, true); v.lastSeen.put(key, tick); return false; }
        probes++;
        if (settings.debug()) plugin.getLogger().info("[debug] storage probe refused " + b.getType() + " " + b.getX() + ","
                + b.getY() + "," + b.getZ() + " by " + p.getName());
        return true;
    }

    void broken(Block b, Player breaker) {
        long key = Nms.key(b.getX(), b.getY(), b.getZ());
        breakers.put(key, breaker.getEntityId());
        breakerExpiry.put(key, tick + 3);
    }

    /** Concealed subjects for the sound/particle filter, set by the visibility engine every tick. */
    void concealed(UUID viewer, int[] ids, double[] pos) {
        ViewState v = views.get(viewer);
        if (v != null) { v.concealedIds = ids; v.concealedPos = pos; }
    }

    // ------------------------------------------------------------------ main tick

    void tick(long now) {
        tick = now;
        if (!breakerExpiry.isEmpty()) {
            for (Iterator<Map.Entry<Long, Long>> it = breakerExpiry.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, Long> e = it.next();
                if (e.getValue() <= now) { breakers.remove(e.getKey()); it.remove(); }
            }
        }
        Settings s = settings;
        long budget = System.nanoTime() + s.storageMicrosPerTick() * 1000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            ViewState v = views.get(p.getUniqueId());
            if (v == null) continue;
            if ((now + p.getEntityId()) % 20 == 0) refresh(p, v);
            for (long[] keys; (keys = v.resend.poll()) != null; ) for (long k : keys) sendReal(p, k, false);
            if (!s.storageEsp() || v.bypass) {
                if (!v.hidden.isEmpty()) revealAll(p, v);
                continue;
            }
            // new chunks and new storage blocks first: the player may be looking right at them
            for (long[] keys; (keys = v.fresh.poll()) != null; ) for (long k : keys) if (v.isHidden(k)) consider(p, v, k);
            // then everything hidden near the player, a few times a second, while the budget lasts
            if ((now + p.getEntityId()) % s.storageCheckTicks() == 0 && System.nanoTime() < budget) scan(p, v, budget);
        }
    }

    private void scan(Player p, ViewState v, long budget) {
        Location eye = p.getEyeLocation();
        int pcx = eye.getBlockX() >> 4, pcz = eye.getBlockZ() >> 4;
        int reach = (int) Math.ceil(settings.storageRevealDistance() / 16.0) + 1;
        // Revealed blocks that have been out of sight for a while go back to hidden: an ESP that does not keep its
        // own record (most do not) loses them again. Not within 16 blocks, where the player may turn back any moment.
        long rehide = settings.storageRehideTicks();
        if (rehide > 0) {
            for (Map.Entry<Long, Set<Long>> e : v.revealed.entrySet()) {
                for (Long k : e.getValue().toArray(new Long[0])) {
                    if (System.nanoTime() > budget) return;
                    int x = Nms.keyX(k), y = Nms.keyY(k), z = Nms.keyZ(k);
                    if (visible(p, x, y, z)) { v.lastSeen.put(k, tick); continue; }
                    long seen = v.lastSeen.computeIfAbsent(k, kk -> tick);
                    double dx = x + 0.5 - eye.getX(), dy = y + 0.5 - eye.getY(), dz = z + 0.5 - eye.getZ();
                    if (tick - seen >= rehide && dx * dx + dy * dy + dz * dz > 256) rehideBlock(p, v, k);
                }
            }
        }
        for (Map.Entry<Long, Set<Long>> e : v.hidden.entrySet()) {
            long ck = e.getKey();
            int cx = (int) (ck >> 32), cz = (int) ck;
            if (Math.abs(cx - pcx) > reach || Math.abs(cz - pcz) > reach) continue;
            for (Long k : e.getValue().toArray(new Long[0])) {
                if (System.nanoTime() > budget) return;
                consider(p, v, k);
            }
        }
    }

    private void consider(Player p, ViewState v, long key) {
        if (visible(p, Nms.keyX(key), Nms.keyY(key), Nms.keyZ(key))) { sendReal(p, key, true); v.lastSeen.put(key, tick); }
    }

    /** Sends the stand-in block again (air, or stone for full blocks) and marks the block hidden. */
    private void rehideBlock(Player p, ViewState v, long key) {
        World w = p.getWorld();
        int x = Nms.keyX(key), y = Nms.keyY(key), z = Nms.keyZ(key);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        Block b = w.getBlockAt(x, y, z);
        v.lastSeen.remove(key);
        if (!settings.storageBlocks().contains(b.getType())) { v.unhide(key); return; }
        v.hide(key);
        rehides++;
        p.sendBlockChange(b.getLocation(), (b.getType().isOccluding() ? org.bukkit.Material.STONE : org.bukkit.Material.AIR).createBlockData());
        if (settings.debug()) plugin.getLogger().info("[debug] storage rehide " + b.getType() + " " + x + "," + y + "," + z + " for " + p.getName());
    }

    private void revealAll(Player p, ViewState v) {
        List<Long> all = new ArrayList<>();
        for (Set<Long> set : v.hidden.values()) all.addAll(set);
        for (long k : all) sendReal(p, k, true);
    }

    /** Sends the real block (and its block entity data) to the player. */
    private void sendReal(Player p, long key, boolean reveal) {
        ViewState v = views.get(p.getUniqueId());
        if (v == null) return;
        World w = p.getWorld();
        int x = Nms.keyX(key), y = Nms.keyY(key), z = Nms.keyZ(key);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        Block b = w.getBlockAt(x, y, z);
        BlockState state = b.getState(false);
        boolean storage = settings.storageBlocks().contains(b.getType());
        if (storage) v.markRevealed(key); else v.unhide(key);
        if (reveal) reveals++;
        if (settings.debug()) plugin.getLogger().info("[debug] storage " + (reveal ? "reveal " : "send ") + b.getType() + " " + x + "," + y + "," + z + " to " + p.getName());
        Location loc = b.getLocation();
        p.sendBlockChange(loc, b.getBlockData());
        if (state instanceof TileState tile) {
            try { p.sendBlockUpdate(loc, tile); } catch (RuntimeException ignored) { /* no client-side data for this one */ }
        }
    }

    /**
     * Line of sight from the player's eyes to an exposed face of the block (a point just outside each face that
     * points at the player, five points per face), within the reveal distance.
     */
    boolean visible(Player p, int bx, int by, int bz) {
        Location eye = p.getEyeLocation();
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();
        double cx = bx + 0.5, cy = by + 0.5, cz = bz + 0.5;
        double d2 = (cx - ex) * (cx - ex) + (cy - ey) * (cy - ey) + (cz - ez) * (cz - ez);
        double max = settings.storageRevealDistance();
        if (d2 > max * max) return false;
        if (d2 < 4) return true; // inside the block's reach box
        WorldOccluder occ = new WorldOccluder(p.getWorld(), occluding.get());
        for (int face = 0; face < 6; face++) {
            int nx = face == 0 ? -1 : face == 1 ? 1 : 0, ny = face == 2 ? -1 : face == 3 ? 1 : 0, nz = face == 4 ? -1 : face == 5 ? 1 : 0;
            double fx = cx + nx * 0.55, fy = cy + ny * 0.55, fz = cz + nz * 0.55;
            if ((ex - fx) * nx + (ey - fy) * ny + (ez - fz) * nz <= 0) continue;       // face points away
            if (occ.occludes(bx + nx, by + ny, bz + nz)) continue;                      // face covered
            for (int k = 0; k < 5; k++) {
                double u = k == 1 ? -0.35 : k == 2 ? 0.35 : 0, w = k == 3 ? -0.35 : k == 4 ? 0.35 : 0;
                double px = fx + (nx == 0 ? u : 0), py = fy + (ny == 0 ? (nx != 0 ? u : w) : 0), pz = fz + (nz == 0 ? w : 0);
                if (nx != 0) { py = fy + u; pz = fz + w; }
                if (ny != 0) { px = fx + u; pz = fz + w; }
                if (nz != 0) { px = fx + u; py = fy + w; }
                rays++;
                if (Raycaster.clear(ex, ey, ez, px, py, pz, occ)) return true;
            }
        }
        return false;
    }

    String status() {
        int hidden = 0, revealed = 0;
        long rewritten = 0, dropped = 0;
        for (ViewState v : views.values()) {
            for (Set<Long> s : v.hidden.values()) hidden += s.size();
            for (Set<Long> s : v.revealed.values()) revealed += s.size();
        }
        for (PacketGuard g : guards.values()) { rewritten += g.rewritten; dropped += g.dropped; }
        return "storage ESP " + (settings.storageEsp() ? "on" : "off") + " (" + nms.storageStates() + " block states): hidden="
                + hidden + ", revealed=" + revealed + ", reveals=" + reveals + ", rehides=" + rehides + ", probes refused=" + probes + ", chunks rewritten=" + rewritten
                + ", chunks unreadable/ambiguous/rejected=" + ChunkFilter.UNPARSED.sum() + "/" + ChunkFilter.AMBIGUOUS.sum() + "/" + ChunkFilter.REJECTED.sum()
                + ", packets dropped=" + dropped + ", connections=" + injected + (failedInjections > 0 ? ", injection failures=" + failedInjections : "")
                + ", rays=" + rays;
    }
}
