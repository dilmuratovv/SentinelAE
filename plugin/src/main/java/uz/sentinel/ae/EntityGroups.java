package uz.sentinel.ae;

import java.util.Set;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Vehicle;

/** Named groups of entity types, resolved against the running server's entity classes. */
final class EntityGroups {
    private EntityGroups() {}

    static boolean add(String group, Set<EntityType> out) {
        Class<? extends Entity> kind = switch (group) {
            case "players" -> Player.class;
            case "armor-stands" -> ArmorStand.class;
            case "item-frames" -> ItemFrame.class;
            case "hanging" -> Hanging.class;           // paintings, frames, leash knots
            case "vehicles" -> Vehicle.class;          // boats, minecarts (with chests too), horses, pigs, striders
            case "projectiles" -> Projectile.class;    // arrows, tridents, pearls, fireworks, fishing hooks
            case "pets" -> Tameable.class;
            case "items" -> Item.class;
            case "villagers" -> AbstractVillager.class;
            case "mobs" -> Mob.class;
            default -> null;
        };
        if (kind == null) return false;
        for (EntityType t : EntityType.values()) {
            Class<? extends Entity> c = t.getEntityClass();
            if (c != null && kind.isAssignableFrom(c)) out.add(t);
        }
        return true;
    }
}
