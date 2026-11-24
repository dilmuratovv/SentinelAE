package uz.sentinel.ae;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Reflection over the server internals (Mojang names, which every supported Paper version runs with). Lookups are
 * cached per class and name; records (26.x packets) and classes (1.21.x) are both handled by trying the accessor
 * names of each. Everything here may be called from network threads.
 */
final class Nms {
    private static final Map<String, Method> methods = new ConcurrentHashMap<>();
    private static final Map<String, Field> fields = new ConcurrentHashMap<>();

    private final Method blockGetId;        // static int Block.getId(BlockState)
    private final Object airState;
    private final int airId;
    private final BitSet storageIds = new BitSet();
    private final BitSet fullBlockIds = new BitSet();
    private int stoneId;
    private final Constructor<?> bundleCtor;

    private Nms(Method blockGetId, Object airState, int airId, Constructor<?> bundleCtor) {
        this.blockGetId = blockGetId; this.airState = airState; this.airId = airId; this.bundleCtor = bundleCtor;
    }

    /** Resolves the block registry; returns null (with a reason) when the internals are not what we expect. */
    static Nms create(Collection<Material> storage, java.util.function.Consumer<String> warn) {
        try {
            String craft = Bukkit.getServer().getClass().getPackageName();
            Class<?> magic = Class.forName(craft + ".util.CraftMagicNumbers");
            Method getBlock = magic.getMethod("getBlock", Material.class);
            Class<?> blockClass = getBlock.getReturnType();
            ClassLoader cl = blockClass.getClassLoader();
            Class<?> stateClass = Class.forName("net.minecraft.world.level.block.state.BlockState", false, cl);
            Method getId = blockClass.getMethod("getId", stateClass);
            Object airBlock = getBlock.invoke(null, Material.AIR);
            Object air = blockClass.getMethod("defaultBlockState").invoke(airBlock);
            int airId = (int) getId.invoke(null, air);
            Class<?> bundle = Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket", false, cl);
            Nms nms = new Nms(getId, air, airId, bundle.getConstructor(Iterable.class));
            Method definition = blockClass.getMethod("getStateDefinition");
            nms.stoneId = (int) getId.invoke(null, blockClass.getMethod("defaultBlockState").invoke(getBlock.invoke(null, Material.STONE)));
            for (Material m : storage) {
                Object block = getBlock.invoke(null, m);
                if (block == null || block == airBlock) continue;
                Object def = definition.invoke(block);
                List<?> states = (List<?>) def.getClass().getMethod("getPossibleStates").invoke(def);
                boolean full = m.isOccluding();
                for (Object s : states) {
                    int id = (int) getId.invoke(null, s);
                    nms.storageIds.set(id);
                    if (full) nms.fullBlockIds.set(id);
                }
            }
            return nms;
        } catch (Throwable t) {
            warn.accept("Storage ESP protection is off: server internals not found (" + t + ").");
            return null;
        }
    }

    int airId() { return airId; }
    Object airState() { return airState; }
    boolean isStorage(int id) { return id >= 0 && storageIds.get(id); }

    /** What a hidden storage state is sent as: stone for full blocks (no hole in a wall), air otherwise; -1 keeps it. */
    int replacement(int id) { return !isStorage(id) ? -1 : fullBlockIds.get(id) ? stoneId : airId; }
    int storageStates() { return storageIds.cardinality(); }

    int stateId(Object blockState) {
        try { return (int) blockGetId.invoke(null, blockState); } catch (ReflectiveOperationException e) { return -1; }
    }

    Object bundle(List<Object> packets) throws ReflectiveOperationException { return bundleCtor.newInstance(packets); }

    // ------------------------------------------------------------------ generic access

    /** Calls the first existing no-argument method of the given names (getX for classes, x for records). */
    static Object call(Object o, String... names) {
        Class<?> c = o.getClass();
        for (String n : names) {
            String key = c.getName() + "#" + n;
            Method m = methods.get(key);
            if (m == null) {
                m = find(c, n);
                if (m == null) continue;
                methods.put(key, m);
            }
            try { return m.invoke(o); } catch (ReflectiveOperationException e) { return null; }
        }
        return null;
    }

    private static Method find(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException | RuntimeException ignored) { }
        }
        try { return c.getMethod(name); } catch (NoSuchMethodException e) { return null; }
    }

    /** Reads a (possibly private) field by name, walking up the class hierarchy. */
    static Object get(Object o, String name) {
        Class<?> c = o.getClass();
        String key = c.getName() + "." + name;
        Field f = fields.get(key);
        if (f == null) {
            for (Class<?> k = c; k != null && f == null; k = k.getSuperclass()) {
                try { f = k.getDeclaredField(name); f.setAccessible(true); } catch (NoSuchFieldException | RuntimeException ignored) { f = null; }
            }
            if (f == null) return null;
            fields.put(key, f);
        }
        try { return f.get(o); } catch (IllegalAccessException e) { return null; }
    }

    static int i(Object o, String... names) {
        Object v = call(o, names);
        return v instanceof Number n ? n.intValue() : Integer.MIN_VALUE;
    }

    /** BlockPos / Vec3i coordinates. */
    static long blockKey(Object pos) {
        return key(i(pos, "getX"), i(pos, "getY"), i(pos, "getZ"));
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int keyX(long k) { return (int) (k >> 38); }
    static int keyY(long k) { return (int) (k << 52 >> 52); }
    static int keyZ(long k) { return (int) (k << 26 >> 38); }
    static long chunkKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    /** The Netty channel of a player: ServerPlayer.connection (game listener).connection (Connection).channel. */
    static Object channel(Player p) {
        Object handle = call(p, "getHandle");
        if (handle == null) return null;
        Object listener = get(handle, "connection");
        if (listener == null) return null;
        Object connection = get(listener, "connection");
        return connection == null ? null : get(connection, "channel");
    }
}
