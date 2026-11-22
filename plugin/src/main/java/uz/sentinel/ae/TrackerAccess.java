package uz.sentinel.ae;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Direct access to Paper's entity tracker ({@code ChunkMap.TrackedEntity}), using the Mojang names that every
 * supported Paper version runs with.
 *
 * <p>Needed because Paper adds the viewer to the tracker's {@code seenBy} set <em>before</em> firing
 * {@code PlayerTrackEntityEvent}; cancelling only skips the spawn packet, so the viewer would still receive every
 * movement and metadata packet of the "hidden" entity. This class removes the viewer from {@code seenBy}, and can
 * untrack or re-offer an entity without touching the tab list. When the internals are not found, {@link #create}
 * returns null and the engine falls back to the public hide/show API.</p>
 */
final class TrackerAccess {
    private final MethodHandle worldHandle;      // CraftWorld -> ServerLevel
    private final MethodHandle chunkSource;      // ServerLevel -> ServerChunkCache
    private final MethodHandle chunkMap;         // ServerChunkCache.chunkMap
    private final MethodHandle entityMap;        // ChunkMap.entityMap
    private final MethodHandle intMapGet;        // Int2ObjectMap.get(int)
    private final MethodHandle seenBy;           // TrackedEntity.seenBy
    private final MethodHandle updatePlayer;     // TrackedEntity.updatePlayer(ServerPlayer)
    private final MethodHandle removePlayer;     // TrackedEntity.removePlayer(ServerPlayer)
    private final MethodHandle playerHandle;     // CraftPlayer -> ServerPlayer
    private final MethodHandle connection;       // ServerPlayer.connection
    private final Map<UUID, Object> maps = new HashMap<>();

    private TrackerAccess(MethodHandle worldHandle, MethodHandle chunkSource, MethodHandle chunkMap, MethodHandle entityMap,
                          MethodHandle intMapGet, MethodHandle seenBy, MethodHandle updatePlayer, MethodHandle removePlayer,
                          MethodHandle playerHandle, MethodHandle connection) {
        this.worldHandle = worldHandle; this.chunkSource = chunkSource; this.chunkMap = chunkMap; this.entityMap = entityMap;
        this.intMapGet = intMapGet; this.seenBy = seenBy; this.updatePlayer = updatePlayer; this.removePlayer = removePlayer;
        this.playerHandle = playerHandle; this.connection = connection;
    }

    /** Resolves everything once, using a live world and player class. Returns null when anything is missing. */
    static TrackerAccess create(World world, Class<?> craftPlayerClass, java.util.function.Consumer<String> warn) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Method getHandle = world.getClass().getMethod("getHandle");
            Object level = getHandle.invoke(world);
            Method getChunkSource = level.getClass().getMethod("getChunkSource");
            Object cache = getChunkSource.invoke(level);
            Field chunkMapField = cache.getClass().getField("chunkMap");
            Object map = chunkMapField.get(cache);
            Field entityMapField = map.getClass().getField("entityMap");
            Class<?> intMap = Class.forName("it.unimi.dsi.fastutil.ints.Int2ObjectMap", false, map.getClass().getClassLoader());
            Method get = intMap.getMethod("get", int.class);
            Class<?> tracked = Class.forName("net.minecraft.server.level.ChunkMap$TrackedEntity", false, map.getClass().getClassLoader());
            Field seen = tracked.getField("seenBy");
            Method craftGetHandle = craftPlayerClass.getMethod("getHandle");
            Class<?> serverPlayer = craftGetHandle.getReturnType();
            Method update = tracked.getMethod("updatePlayer", serverPlayer);
            Method remove = tracked.getMethod("removePlayer", serverPlayer);
            Field conn = serverPlayer.getField("connection");
            if (!Collection.class.isAssignableFrom(seen.getType())) throw new NoSuchFieldException("seenBy is not a collection");
            return new TrackerAccess(
                    lookup.unreflect(getHandle).asType(MethodType.methodType(Object.class, Object.class)),
                    lookup.unreflect(getChunkSource).asType(MethodType.methodType(Object.class, Object.class)),
                    lookup.unreflectGetter(chunkMapField).asType(MethodType.methodType(Object.class, Object.class)),
                    lookup.unreflectGetter(entityMapField).asType(MethodType.methodType(Object.class, Object.class)),
                    lookup.unreflect(get).asType(MethodType.methodType(Object.class, Object.class, int.class)),
                    lookup.unreflectGetter(seen).asType(MethodType.methodType(Collection.class, Object.class)),
                    lookup.unreflect(update).asType(MethodType.methodType(void.class, Object.class, Object.class)),
                    lookup.unreflect(remove).asType(MethodType.methodType(void.class, Object.class, Object.class)),
                    lookup.unreflect(craftGetHandle).asType(MethodType.methodType(Object.class, Object.class)),
                    lookup.unreflectGetter(conn).asType(MethodType.methodType(Object.class, Object.class)));
        } catch (Throwable t) {
            warn.accept("Paper tracker internals not available (" + t + "); using the public hide/show API instead. "
                    + "Hidden players then disappear from the tab list while concealed.");
            return null;
        }
    }

    void forgetWorld(World world) { maps.remove(world.getUID()); }

    private Object entry(Entity target) throws Throwable {
        World w = target.getWorld();
        Object map = maps.get(w.getUID());
        if (map == null) {
            map = entityMap.invoke(chunkMap.invoke(chunkSource.invoke(worldHandle.invoke(w))));
            maps.put(w.getUID(), map);
        }
        return intMapGet.invoke(map, target.getEntityId());
    }

    /** True when Paper currently sends this entity to the viewer. */
    boolean isSeen(Player viewer, Entity target) {
        try {
            Object e = entry(target);
            return e != null && ((Collection<?>) seenBy.invoke(e)).contains(connection.invoke(playerHandle.invoke(viewer)));
        } catch (Throwable t) {
            return target.getTrackedBy().contains(viewer);
        }
    }

    /** Removes the viewer from seenBy after a cancelled track event (no spawn was sent, so no packet is needed). */
    void purge(Player viewer, Entity target) throws Throwable {
        Object e = entry(target);
        if (e != null) ((Collection<?>) seenBy.invoke(e)).remove(connection.invoke(playerHandle.invoke(viewer)));
    }

    /** Stops sending the entity: Paper sends the destroy packet and fires the untrack event. */
    void untrack(Player viewer, Entity target) throws Throwable {
        Object e = entry(target);
        if (e != null) removePlayer.invoke(e, playerHandle.invoke(viewer));
    }

    /** Offers the entity to the viewer again; Paper fires the track event and spawns it when allowed and in range. */
    void retrack(Player viewer, Entity target) throws Throwable {
        Object e = entry(target);
        if (e != null) updatePlayer.invoke(e, playerHandle.invoke(viewer));
    }
}
