package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * Resolves clicks against the logical blocks of an active ship.
 *
 * Active ships are rendered by BlockDisplay entities and therefore have no
 * normal block hitboxes. This listener performs a ray/AABB intersection
 * against the stored local ship geometry instead. It intentionally does not
 * change the world: later storage/block-entity handlers can consume the
 * resolved ShipBlock without depending on display entities.
 */
public final class VirtualBlockInteraction implements Listener {
    private static final double MAX_DISTANCE = 6.0;
    private static final double EPSILON = 1.0E-7;

    private final ShipRegistry registry;

    public VirtualBlockInteraction(ShipRegistry registry) {
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK
                && action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        VirtualHit hit = findHit(player);
        if (hit == null) return;

        // The real ship blocks no longer exist in the world while active.
        // Consume the click so vanilla interaction cannot act on an unrelated
        // world block at the same location.
        event.setCancelled(true);
        player.sendActionBar("§7Корабль: §f" + hit.block().blockData().getMaterial().name());
    }

    /** Finds the nearest logical ship block intersected by the player's view ray. */
    public VirtualHit findHit(Player player) {
        Location eye = player.getEyeLocation();
        World world = eye.getWorld();
        if (world == null) return null;

        Vector direction = eye.getDirection().normalize();
        VirtualHit nearest = null;

        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE) continue;
            if (!ship.worldId().equals(world.getUID())) continue;

            ShipRuntimeState runtime = registry.runtime(ship.id());
            if (runtime == null) continue;

            Location position = runtime.position();
            for (ShipBlock block : ship.blocks()) {
                Vector center = transformLocal(block.x() + 0.5, block.y() + 0.5, block.z() + 0.5,
                        ship, runtime);
                Vector min = transformLocal(block.x(), block.y(), block.z(), ship, runtime);
                Vector max = transformLocal(block.x() + 1.0, block.y() + 1.0, block.z() + 1.0, ship, runtime);

                // For the rigid-body rotation, local cube corners can no longer
                // be represented by min/max directly. Build a conservative AABB
                // from all eight transformed corners.
                double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
                for (int x = 0; x <= 1; x++) for (int y = 0; y <= 1; y++) for (int z = 0; z <= 1; z++) {
                    Vector corner = transformLocal(block.x() + x, block.y() + y, block.z() + z, ship, runtime);
                    minX = Math.min(minX, corner.getX()); maxX = Math.max(maxX, corner.getX());
                    minY = Math.min(minY, corner.getY()); maxY = Math.max(maxY, corner.getY());
                    minZ = Math.min(minZ, corner.getZ()); maxZ = Math.max(maxZ, corner.getZ());
                }

                double distance = rayAabbDistance(eye.toVector(), direction,
                        minX, minY, minZ, maxX, maxY, maxZ);
                if (distance < 0 || distance > MAX_DISTANCE) continue;
                if (nearest == null || distance < nearest.distance()) {
                    nearest = new VirtualHit(ship, block, distance, center, faceFromRay(eye.toVector(), direction, distance,
                            minX, minY, minZ, maxX, maxY, maxZ));
                }
            }
        }
        return nearest;
    }

    private Vector transformLocal(double x, double y, double z, ShipModel ship, ShipRuntimeState runtime) {
        double yaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double pitch = Math.toRadians(runtime.pitch());
        double roll = Math.toRadians(runtime.roll());

        double yawX = x * Math.cos(yaw) - z * Math.sin(yaw);
        double yawZ = x * Math.sin(yaw) + z * Math.cos(yaw);
        double pitchY = y * Math.cos(pitch) - yawZ * Math.sin(pitch);
        double pitchZ = y * Math.sin(pitch) + yawZ * Math.cos(pitch);
        double rollX = yawX * Math.cos(roll) - pitchY * Math.sin(roll);
        double rollY = yawX * Math.sin(roll) + pitchY * Math.cos(roll);

        Location position = runtime.position();
        return new Vector(position.getX() + rollX, position.getY() + rollY, position.getZ() + pitchZ);
    }

    private double rayAabbDistance(Vector origin, Vector direction,
                                   double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ) {
        double tMin = 0.0;
        double tMax = MAX_DISTANCE;

        double[] o = {origin.getX(), origin.getY(), origin.getZ()};
        double[] d = {direction.getX(), direction.getY(), direction.getZ()};
        double[] min = {minX, minY, minZ};
        double[] max = {maxX, maxY, maxZ};

        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < EPSILON) {
                if (o[i] < min[i] || o[i] > max[i]) return -1;
                continue;
            }
            double inv = 1.0 / d[i];
            double t1 = (min[i] - o[i]) * inv;
            double t2 = (max[i] - o[i]) * inv;
            if (t1 > t2) {
                double temp = t1; t1 = t2; t2 = temp;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }
        return tMin;
    }

    private BlockFace faceFromRay(Vector origin, Vector direction, double distance,
                                  double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        Vector hit = origin.clone().add(direction.clone().multiply(distance));
        double dx = Math.min(Math.abs(hit.getX() - minX), Math.abs(hit.getX() - maxX));
        double dy = Math.min(Math.abs(hit.getY() - minY), Math.abs(hit.getY() - maxY));
        double dz = Math.min(Math.abs(hit.getZ() - minZ), Math.abs(hit.getZ() - maxZ));
        if (dx <= dy && dx <= dz) return Math.abs(hit.getX() - minX) <= Math.abs(hit.getX() - maxX) ? BlockFace.WEST : BlockFace.EAST;
        if (dy <= dz) return Math.abs(hit.getY() - minY) <= Math.abs(hit.getY() - maxY) ? BlockFace.DOWN : BlockFace.UP;
        return Math.abs(hit.getZ() - minZ) <= Math.abs(hit.getZ() - maxZ) ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    public record VirtualHit(ShipModel ship, ShipBlock block, double distance, Vector hitCenter, BlockFace face) {
        public UUID shipId() { return ship.id(); }
    }
}
