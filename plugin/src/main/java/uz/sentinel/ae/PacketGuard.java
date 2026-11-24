package uz.sentinel.ae;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Outbound packet filter for one player (Netty thread). Rewrites chunk data so storage blocks arrive as air, keeps
 * later block updates of hidden storage blocks away, and drops the sounds, particles and block-break animations that
 * would give away a concealed subject or a hidden chest. Every rule fails open: an unexpected packet shape is sent
 * unchanged.
 */
final class PacketGuard extends ChannelOutboundHandlerAdapter {
    static final String NAME = "sentinel_ae";

    private final ViewState view;
    private final Nms nms;
    private volatile Settings settings;
    private final Map<Long, Integer> breakers; // block key -> entity id of the player breaking it (main thread writes)
    volatile long rewritten, dropped;

    void apply(Settings s) { settings = s; }

    PacketGuard(ViewState view, Nms nms, Settings settings, Map<Long, Integer> breakers) {
        this.view = view; this.nms = nms; this.settings = settings; this.breakers = breakers;
    }

    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        Object out;
        try { out = filter(msg); } catch (Throwable t) { out = msg; }
        if (out == null) { dropped++; promise.trySuccess(); return; }
        super.write(ctx, out, promise);
    }

    /** Returns the packet to send (possibly a new one), or null to drop it. */
    private Object filter(Object msg) throws Exception {
        if (view.bypass) return msg;
        String name = msg.getClass().getSimpleName();
        switch (name) {
            case "ClientboundBundlePacket": {
                Object subs = Nms.call(msg, "subPackets");
                if (!(subs instanceof Iterable<?> it)) return msg;
                List<Object> kept = new ArrayList<>();
                boolean changed = false;
                for (Object p : it) {
                    Object f = filter(p);
                    if (f != p) changed = true;
                    if (f != null) kept.add(f);
                }
                return changed ? nms.bundle(kept) : msg;
            }
            case "ClientboundLevelChunkWithLightPacket": if (settings.storageEsp()) chunk(msg); return msg;
            case "ClientboundBlockUpdatePacket": return settings.storageEsp() ? blockUpdate(msg) : msg;
            case "ClientboundSectionBlocksUpdatePacket": return settings.storageEsp() ? sectionUpdate(msg) : msg;
            case "ClientboundBlockEventPacket":
            case "ClientboundBlockEntityDataPacket": {
                Object pos = Nms.call(msg, "getPos", "pos");
                return pos != null && view.isHidden(Nms.blockKey(pos)) ? null : msg;
            }
            case "ClientboundSoundPacket": {
                double[] p = position(msg, true);
                if (p == null) return msg;
                if (settings.hideSounds() && view.nearConcealed(p[0], p[1], p[2])) return null;
                return settings.storageEsp() && view.nearHidden(p[0], p[1], p[2]) ? null : msg;
            }
            case "ClientboundSoundEntityPacket": {
                int id = Nms.i(msg, "getId", "id");
                return settings.hideSounds() && view.concealedId(id) ? null : msg;
            }
            case "ClientboundLevelParticlesPacket": {
                if (!settings.hideSounds()) return msg;
                double[] p = position(msg, false);
                return p != null && view.nearConcealed(p[0], p[1], p[2]) ? null : msg;
            }
            case "ClientboundBlockDestructionPacket": {
                int id = Nms.i(msg, "getId", "id");
                return settings.hideSounds() && view.concealedId(id) ? null : msg;
            }
            case "ClientboundLevelEventPacket": {
                Object pos = Nms.call(msg, "getPos", "pos");
                if (pos == null) return msg;
                long key = Nms.blockKey(pos);
                if (settings.storageEsp() && view.isHidden(key)) return null;
                Integer breaker = breakers.get(key); // block broken by a concealed player: its particles and sound
                return settings.hideSounds() && breaker != null && view.concealedId(breaker) ? null : msg;
            }
            default: return msg;
        }
    }

    private static double[] position(Object msg, boolean fixedPoint) {
        Object x = Nms.call(msg, "getX", "x"), y = Nms.call(msg, "getY", "y"), z = Nms.call(msg, "getZ", "z");
        if (!(x instanceof Number nx) || !(y instanceof Number ny) || !(z instanceof Number nz)) return null;
        // the sound packet stores 1/8 blocks as ints; its class getters return blocks, a record accessor the raw int
        double f = fixedPoint && x instanceof Integer ? 8.0 : 1.0;
        return new double[]{nx.doubleValue() / f, ny.doubleValue() / f, nz.doubleValue() / f};
    }

    private void chunk(Object packet) {
        Object data = Nms.call(packet, "getChunkData", "chunkData");
        int cx = Nms.i(packet, "getX", "x"), cz = Nms.i(packet, "getZ", "z");
        if (data == null || cx == Integer.MIN_VALUE) return;
        Object raw = Nms.get(data, "buffer");
        if (!(raw instanceof byte[] buf)) return;
        long chunk = Nms.chunkKey(cx, cz);
        view.dropChunk(chunk); // a resend replaces everything the client knew about this chunk
        ChunkFilter.Result r = ChunkFilter.hide(buf, nms::replacement);
        if (r == null || r.hidden().length == 0) return;
        rewritten++;
        int minY = view.minY;
        long[] keys = new long[r.hidden().length];
        for (int i = 0; i < keys.length; i++) {
            int h = r.hidden()[i], section = h >>> 12, local = h & 0xFFF;
            keys[i] = Nms.key((cx << 4) + (local & 15), minY + (section << 4) + (local >>> 8), (cz << 4) + ((local >>> 4) & 15));
            view.hide(keys[i]);
        }
        // The block entity list would name every chest; drop the entries of hidden blocks.
        Object list = Nms.get(data, "blockEntitiesData");
        if (list instanceof List<?> l) {
            try {
                l.removeIf(info -> {
                    Object xz = Nms.get(info, "packedXZ"), y = Nms.get(info, "y");
                    if (!(xz instanceof Integer p) || !(y instanceof Integer yy)) return false;
                    int lx = (p >> 4) & 15, lz = p & 15;
                    return view.isHidden(Nms.key((cx << 4) + lx, yy, (cz << 4) + lz));
                });
            } catch (UnsupportedOperationException ignored) { /* an immutable list: the block entities stay, their blocks are air */ }
        }
        view.fresh.add(keys);
    }

    private Object blockUpdate(Object packet) {
        Object pos = Nms.call(packet, "getPos", "pos");
        Object state = Nms.call(packet, "getBlockState", "blockState");
        if (pos == null || state == null) return packet;
        long key = Nms.blockKey(pos);
        if (nms.isStorage(nms.stateId(state))) {
            if (view.isRevealed(key)) return packet;
            view.hide(key);
            view.fresh.add(new long[]{key});
            return null; // the client keeps what it has; the main thread reveals it once it is in sight
        }
        view.unhide(key);
        return packet;
    }

    /**
     * The same section update object goes to every player near the chunk, so it is never changed here. When it would
     * show this player a hidden or new storage block, it is dropped for this player and the main thread sends the
     * other blocks of it one by one.
     */
    private Object sectionUpdate(Object packet) {
        Object section = Nms.get(packet, "sectionPos");
        Object positions = Nms.get(packet, "positions");
        Object states = Nms.get(packet, "states");
        if (section == null || !(positions instanceof short[] ps) || !(states instanceof Object[] ss)) return packet;
        int sx = Nms.i(section, "getX", "x") << 4, sy = Nms.i(section, "getY", "y") << 4, sz = Nms.i(section, "getZ", "z") << 4;
        long[] keys = new long[ps.length];
        boolean[] storage = new boolean[ps.length];
        boolean drop = false;
        for (int i = 0; i < ps.length && i < ss.length; i++) {
            short s = ps[i];
            keys[i] = Nms.key(sx + ((s >>> 8) & 15), sy + (s & 15), sz + ((s >>> 4) & 15));
            storage[i] = nms.isStorage(nms.stateId(ss[i]));
            if (storage[i] && !view.isRevealed(keys[i])) drop = true;
        }
        if (!drop) {
            for (int i = 0; i < keys.length; i++) if (!storage[i]) view.unhide(keys[i]);
            return packet;
        }
        List<Long> fresh = new ArrayList<>(), others = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            if (storage[i] && !view.isRevealed(keys[i])) { view.hide(keys[i]); fresh.add(keys[i]); }
            else { if (!storage[i]) view.unhide(keys[i]); others.add(keys[i]); }
        }
        view.fresh.add(fresh.stream().mapToLong(Long::longValue).toArray());
        if (!others.isEmpty()) view.resend.add(others.stream().mapToLong(Long::longValue).toArray());
        return null;
    }
}
