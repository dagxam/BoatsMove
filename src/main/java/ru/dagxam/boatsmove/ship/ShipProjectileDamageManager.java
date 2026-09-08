package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.util.Vector;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Detects projectile impacts against virtual ship hulls. BlockDisplays have no hitbox, so impacts are resolved here. */
public final class ShipProjectileDamageManager {
    private static final double MAX_PROJECTILE_SEGMENT = 4.0;
    private static final double HIT_EPSILON = 0.03;
    private final ShipRegistry registry;
    private final ShipDamageManager damage;
    private final Map<UUID, Location> previousPositions = new HashMap<>();

    public ShipProjectileDamageManager(ShipRegistry registry, ShipDamageManager damage) {
        this.registry = registry;
        this.damage = damage;
    }

    public void tick() {
        cleanupMissingProjectiles();
        for (World world : org.bukkit.Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(Projectile.class)) {
                if (!entity.isValid() || entity.isDead()) continue;
                Location current = entity.getLocation();
                Location previous = previousPositions.put(entity.getUniqueId(), current.clone());
                if (previous == null) previous = previousFromVelocity(current, entity.getVelocity());
                if (previous.getWorld() == null || !previous.getWorld().equals(current.getWorld())) previous = current.clone();
                if (previous.distanceSquared(current) > MAX_PROJECTILE_SEGMENT * MAX_PROJECTILE_SEGMENT) {
                    previous = previousFromVelocity(current, entity.getVelocity());
                }

                Impact impact = findImpact(previous, current, world);
                if (impact == null) continue;
                if (damage.damageBlock(impact.ship(), impact.block(), impact.location(), null)) {
                    previousPositions.remove(entity.getUniqueId());
                    entity.remove();
                }
            }
        }
    }

    private Location previousFromVelocity(Location current, Vector velocity) {
        return current.clone().subtract(velocity);
    }

    private Impact findImpact(Location start, Location end, World world) {
        Impact best = null;
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE || !ship.worldId().equals(world.getUID())) continue;
            Location origin = registry.position(ship);
            if (!origin.getWorld().equals(world)) continue;

            Vector startLocal = inverseTransform(start.toVector().subtract(origin.toVector()), ship);
            Vector endLocal = inverseTransform(end.toVector().subtract(origin.toVector()), ship);
            if (!broadPhase(startLocal, endLocal, ship)) continue;

            for (ShipBlock block : ship.blocks()) {
                Vector center = new Vector(block.x() + 0.5, block.y() + 0.5, block.z() + 0.5);
                double t = segmentAabb(startLocal, endLocal,
                        center.getX() - 0.5 - HIT_EPSILON, center.getX() + 0.5 + HIT_EPSILON,
                        center.getY() - 0.5 - HIT_EPSILON, center.getY() + 0.5 + HIT_EPSILON,
                        center.getZ() - 0.5 - HIT_EPSILON, center.getZ() + 0.5 + HIT_EPSILON);
                if (t < 0.0) continue;
                if (best == null || t < best.t()) {
                    Location hit = lerp(start, end, t);
                    best = new Impact(ship, block, hit, t);
                }
            }
        }
        return best;
    }

    private boolean broadPhase(Vector start, Vector end, ShipModel ship) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (ShipBlock block : ship.blocks()) {
            minX = Math.min(minX, block.x()); maxX = Math.max(maxX, block.x());
            minY = Math.min(minY, block.y()); maxY = Math.max(maxY, block.y());
            minZ = Math.min(minZ, block.z()); maxZ = Math.max(maxZ, block.z());
        }
        if (minX == Integer.MAX_VALUE) return false;
        double margin = 1.0;
        double hullMinX = minX - margin, hullMaxX = maxX + 1.0 + margin;
        double hullMinY = minY - margin, hullMaxY = maxY + 1.0 + margin;
        double hullMinZ = minZ - margin, hullMaxZ = maxZ + 1.0 + margin;
        return segmentAabb(start, end, hullMinX, hullMaxX, hullMinY, hullMaxY, hullMinZ, hullMaxZ) >= 0.0;
    }

    private Vector inverseTransform(Vector vector, ShipModel ship) {
        double relativeYaw = Math.toRadians(registry.position(ship).getYaw() - ship.origin().getYaw());
        Vector v = rotateY(vector, -relativeYaw);
        v = rotateX(v, -Math.toRadians(ship.pitch()));
        return rotateZ(v, -Math.toRadians(shipRuntimeRoll(ship)));
    }

    private float shipRuntimeRoll(ShipModel ship) {
        ShipRuntimeState state = registry.runtime(ship.id());
        return state == null ? 0.0f : state.roll();
    }

    private Vector rotateY(Vector v, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new Vector(v.getX() * c - v.getZ() * s, v.getY(), v.getX() * s + v.getZ() * c);
    }

    private Vector rotateX(Vector v, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new Vector(v.getX(), v.getY() * c - v.getZ() * s, v.getY() * s + v.getZ() * c);
    }

    private Vector rotateZ(Vector v, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new Vector(v.getX() * c - v.getY() * s, v.getX() * s + v.getY() * c, v.getZ());
    }

    private double segmentAabb(Vector a, Vector b, double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        double tMin = 0.0, tMax = 1.0;
        double[] origin = {a.getX(), a.getY(), a.getZ()};
        double[] delta = {b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ()};
        double[] min = {minX, minY, minZ};
        double[] max = {maxX, maxY, maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(delta[i]) < 1.0E-9) {
                if (origin[i] < min[i] || origin[i] > max[i]) return -1.0;
                continue;
            }
            double inv = 1.0 / delta[i];
            double t1 = (min[i] - origin[i]) * inv;
            double t2 = (max[i] - origin[i]) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1.0;
        }
        return tMin;
    }

    private Location lerp(Location a, Location b, double t) {
        return a.clone().add(b.toVector().subtract(a.toVector()).multiply(t));
    }

    private void cleanupMissingProjectiles() {
        Iterator<Map.Entry<UUID, Location>> it = previousPositions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Location> entry = it.next();
            if (org.bukkit.Bukkit.getEntity(entry.getKey()) == null) it.remove();
        }
    }

    private record Impact(ShipModel ship, ShipBlock block, Location location, double t) {}
}
