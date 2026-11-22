package uz.sentinel.ae;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Anti-ESP: protected entities are only sent to a player while that player could actually see them. */
public final class SentinelAE extends JavaPlugin implements Listener {
    private VisibilityEngine engine;
    private BukkitTask task;
    private long ticks;
    private boolean released;

    @Override public void onEnable() {
        saveDefaultConfig();
        Settings settings = load();
        TrackerAccess access = settings.apiOnly() || getServer().getWorlds().isEmpty() ? null
                : TrackerAccess.create(getServer().getWorlds().get(0), craftPlayerClass(), getLogger()::warning);
        if (settings.apiOnly()) getLogger().info("tracker-mode: api (hidden players leave the tab list while concealed).");
        engine = new VisibilityEngine(this, settings, access);
        released = false;
        getServer().getPluginManager().registerEvents(this, this);
        // The plugin may start while players are online and entities are already being sent to them.
        for (Player viewer : getServer().getOnlinePlayers()) {
            for (Entity entity : viewer.getWorld().getEntities()) {
                if (entity.getTrackedBy().contains(viewer)) engine.adopt(viewer, entity);
            }
        }
        task = getServer().getScheduler().runTaskTimer(this, () -> engine.tick(++ticks), 1, 1);
        getLogger().info("SentinelAE " + getPluginMeta().getVersion() + " enabled.");
    }

    private Class<?> craftPlayerClass() {
        try {
            String pkg = getServer().getClass().getPackageName();
            return Class.forName(pkg + ".entity.CraftPlayer");
        } catch (ClassNotFoundException e) {
            return Object.class; // TrackerAccess.create then reports the problem and the fallback is used
        }
    }

    private Settings load() {
        reloadConfig();
        return Settings.from(getConfig(), getLogger()::warning);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void track(PlayerTrackEntityEvent event) {
        if (engine == null || released || engine.allowTrack(event.getPlayer(), event.getEntity())) return;
        event.setCancelled(true);
        engine.refused(event.getPlayer(), event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { engine.forget(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void removed(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof Player)) engine.forget(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void worldUnload(org.bukkit.event.world.WorldUnloadEvent event) { engine.forgetWorld(event.getWorld()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { engine.resetMotion(event.getPlayer()); later(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityTeleport(org.bukkit.event.entity.EntityTeleportEvent event) {
        engine.resetMotion(event.getEntity());
        later(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void world(PlayerChangedWorldEvent event) { engine.resetMotion(event.getPlayer()); later(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent event) { engine.resetMotion(event.getPlayer()); later(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void gameMode(PlayerGameModeChangeEvent event) { later(event.getPlayer()); }

    private void later(Entity entity) {
        Bukkit.getScheduler().runTask(this, () -> { if (engine != null) engine.touch(entity); });
    }

    /** Fired while this plugin is still enabled, so concealed entities can be handed back to the tracker. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void disabling(PluginDisableEvent event) {
        if (event.getPlugin() == this) release();
    }

    private void release() {
        if (released || engine == null) return;
        released = true;
        engine.releaseAll();
    }

    @Override public void onDisable() {
        if (task != null) task.cancel();
        release();
    }

    // ------------------------------------------------------------------ commands

    /** Read-only status for SentinelGuard. */
    public String getProtectionStatus() {
        return engine == null ? "disabled" : engine.status();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> sender.sendMessage(getProtectionStatus());
            case "reload" -> {
                engine.apply(load());
                sender.sendMessage("SentinelAE configuration reloaded; every pair is re-checked on the next tick.");
            }
            case "check" -> {
                if (args.length != 3) { sender.sendMessage("Usage: /" + label + " check <viewer> <subject>"); return true; }
                Player viewer = Bukkit.getPlayerExact(args[1]);
                Entity subject = Bukkit.getPlayerExact(args[2]);
                if (subject == null && viewer != null) {
                    for (Entity e : viewer.getWorld().getEntities()) if (e.getUniqueId().toString().equals(args[2])) subject = e;
                }
                if (viewer == null || subject == null) { sender.sendMessage("Viewer or subject not found."); return true; }
                sender.sendMessage(engine.explain(viewer, subject));
            }
            default -> { return false; }
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("status", "reload", "check")) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            return out;
        }
        return null;
    }
}
