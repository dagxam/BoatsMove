package ru.dagxam.boatsmove.ship;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Detects projectile impacts against virtual ship hulls and applies projectile-specific damage. */
public final class ShipProjectileDamageManager {
    private static final double MAX_PROJECTILE_SEGMENT = 4.0;
    private static final double HIT_EPSILON = 0.03;
    private static final double MAX_PENETRATION_DISTANCE = 4.5;

    private final ShipRegistry registry;
    private final ShipDamageManager damage;
    private final ShipCannonManager cannons;
    private final Map<UUID, Location> previousPositions = new HashMap<>();

    public ShipProjectileDamageManager(ShipRegistry registry, ShipDamageManager damage, ShipCannonManager cannons) {
        this.registry = registry;
        this.damage = damage;
        this.cannons = cannons;
    }

    public void tick() {
        cleanupMissingProjectiles();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(Projectile.class)) {
                if (!entity.isValid() || entity.isDead()) continue;

                Location current = entity.getLocation();
                Location previous = previousPositions.put(entity.getUniqueId(), current.clone());
                if (previous == null) previous = previousFromVelocity(current, entity.getVelocity());
                if (previous.getWorld() == null || !previous.getWorld().equals(current.getWorld())) previous = current.clone();
                if (previous.distanceSquared(current) > MAX_PROJECTILE_SEGMENT * MAX_PROJECTILE_SEGMENT) {
                    previous = previousFromVelocity(current, entity.getVelocity());
                }

                ProjectileType type = projectileType(entity);
                if (type == null) continue;

                Impact impact = findImpact(previous, current, world);
                if (impact == null) continue;

                ProjectileSource shooter = ((Projectile) entity).getShooter();
                org.bukkit.entity.Player source = shooter instanceof org.bukkit.entity.Player player ? player : null;
                boolean changed = type.explosive()
                        ? applyExplosion(impact, type, source)
                        : applyPenetration(impact, previous, current, type, source);

                if (changed) {
                    previousPositions.remove(entity.getUniqueId());
                    entity.remove();
                }
            }
        }
    }

    /** Direct projectiles can pass through a limited number of logical hull blocks. */
    private boolean applyPenetration(Impact impact, Location start, Location end, ProjectileType type,
                                     org.bukkit.entity.Player source) {
        ShipModel ship = impact.ship();
        ShipRuntimeState runtime = registry.runtime(ship.id());
        Location origin = registry.position(ship);
        Vector direction = end.toVector().subtract(start.toVector());
        if (direction.lengthSquared() < 1.0E-8) {
            direction = impact.location().toVector().subtract(start.toVector());
        }
        if (direction.lengthSquared() < 1.0E-8) return false;
        direction.normalize();

        List<BlockHit> hits = new ArrayList<>();
        double impactDistance = impact.t();
        for (ShipBlock block : ship.blocks()) {
            Vector center = new Vector(block.x() + 0.5, block.y() + 0.5, block.z() + 0.5);
            double distance = origin.clone().add(forwardTransform(center, ship, runtime)).distance(impact.location());
            if (distance > MAX_PENETRATION_DISTANCE) continue;

            // Keep only blocks that lie close to the actual projectile line after the first hit.
            Location blockCenter = origin.clone().add(forwardTransform(center, ship, runtime));
            Vector toBlock = blockCenter.toVector().subtract(impact.location().toVector());
            double along = toBlock.dot(direction);
            if (along < -HIT_EPSILON || along > MAX_PENETRATION_DISTANCE) continue;
            double perpendicularSquared = toBlock.clone().subtract(direction.clone().multiply(along)).lengthSquared();
            if (perpendicularSquared > 0.30 * 0.30) continue;

            hits.add(new BlockHit(block, Math.max(0.0, along), blockCenter));
        }

        // The impact block must always be first, even when the line-center test misses its center.
        hits.removeIf(hit -> hit.block().x() == impact.block().x()
                && hit.block().y() == impact.block().y()
                && hit.block().z() == impact.block().z());
        hits.add(new BlockHit(impact.block(), 0.0, impact.location()));
        hits.sort(Comparator.comparingDouble(BlockHit::distance));

        boolean changed = false;
        int affected = 0;
        for (BlockHit hit : hits) {
            if (affected >= type.maxBlocks()) break;
            double factor = affected == 0 ? 1.0 : Math.max(type.minimumFalloff(), Math.pow(type.penetrationFalloff(), affected));
            double hitDamage = Math.max(0.5, type.damage() * factor);
            if (damage.damageBlock(ship, hit.block(), hit.location(), hitDamage, source)) {
                changed = true;
                affected++;
            }
        }
        return changed;
    }

    private boolean applyExplosion(Impact impact, ProjectileType type, org.bukkit.entity.Player source) {
        ShipModel ship = impact.ship();
        ShipRuntimeState runtime = registry.runtime(ship.id());
        Location center = impact.location();
        Location origin = registry.position(ship);
        List<BlockHit> hits = new ArrayList<>();

        if (type.radius() <= 0.0) {
            return damage.damageBlock(ship, impact.block(), center, type.damage(), source);
        }

        for (ShipBlock block : ship.blocks()) {
            Vector local = new Vector(block.x() + 0.5, block.y() + 0.5, block.z() + 0.5);
            Location blockCenter = origin.clone().add(forwardTransform(local, ship, runtime));
            double distance = blockCenter.distance(center);
            if (distance <= type.radius()) hits.add(new BlockHit(block, distance, blockCenter));
        }

        hits.sort(Comparator.comparingDouble(BlockHit::distance));
        boolean changed = false;
        int affected = 0;
        for (BlockHit hit : hits) {
            double factor = Math.max(type.minimumFalloff(), 1.0 - hit.distance() / type.radius());
            if (damage.damageBlock(ship, hit.block(), hit.location(), Math.max(0.5, type.damage() * factor), source)) {
                changed = true;
                if (++affected >= type.maxBlocks()) break;
            }
        }
        return changed;
    }

    private ProjectileType projectileType(Entity entity) {
        if (cannons != null && cannons.isCannonball(entity)) {
            return new ProjectileType(cannons.damage(), cannons.explosionRadius(), cannons.maxAffectedBlocks(),
                    true, 1.0, 0.25);
        }
        return switch (entity.getType().name()) {
            case "FIREBALL", "DRAGON_FIREBALL" -> new ProjectileType(14.0, 2.5, 8, true, 1.0, 0.20);
            case "WITHER_SKULL" -> new ProjectileType(12.0, 2.0, 6, true, 1.0, 0.20);
            case "SMALL_FIREBALL" -> new ProjectileType(8.0, 1.5, 4, true, 1.0, 0.20);
            case "WIND_CHARGE", "BREEZE_WIND_CHARGE" -> new ProjectileType(6.0, 1.25, 3, true, 1.0, 0.20);
            case "TRIDENT" -> new ProjectileType(10.0, 0.0, 1, false, 0.0, 0.20);
            case "SPECTRAL_ARROW" -> new ProjectileType(5.0, 0.0, 1, false, 0.0, 0.20);
            case "ARROW" -> new ProjectileType(4.0, 0.0, 1, false, 0.0, 0.20);
            default -> null;
        };
    }

    private Location previousFromVelocity(Location current, Vector velocity) {
        return current.clone().subtract(velocity);
    }

    private Impact findImpact(Location start, Location end, World world) {
        Impact best = null;
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE || !ship.worldId().equals(world.getUID())) continue;
            Location origin = registry.position(ship);
            if (origin.getWorld() == null || !origin.getWorld().equals(world)) continue;

            Vector startLocal = inverseTransform(start.toVector().subtract(origin.toVector()), ship);
            Vector endLocal = inverseTransform(end.toVector().subtract(origin.toVector()), ship);
            if (!broadPhase(startLocal, endLocal, ship)) continue;

            for (ShipBlock block : ship.blocks()) {
                Vector center = new Vector(block.x() + 0.5, block.y() + 0.5, block.z() + 0.5);
                double t = segmentAabb(startLocal, endLocal,
                        center.getX() - 0.5 - HIT_EPSILON, center.getX() + 0.5 + HIT_EPSILON,
                        center.getY() - 0.5 - HIT_EPSILON, center.getY() + 0.5 + HIT_EPSILON,
                        center.getZ() - 0.5 - HIT_EPSILON, center.getZ() + 0.5 + HIT_EPSILON);
                if (t >= 0.0 && (best == null || t < best.t())) {
                    best = new Impact(ship, block, lerp(start, end, t), t);
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
        return minX != Integer.MAX_VALUE && segmentAabb(start, end,
                minX - 1.0, maxX + 2.0, minY - 1.0, maxY + 2.0, minZ - 1.0, maxZ + 2.0) >= 0.0;
    }

    private Vector inverseTransform(Vector vector, ShipModel ship) {
        ShipRuntimeState runtime = registry.runtime(ship.id());
        float yaw = registry.position(ship).getYaw();
        float pitch = runtime == null ? ship.pitch() : runtime.pitch();
        float roll = runtime == null ? 0.0f : runtime.roll();
        Vector v = rotateY(vector, -Math.toRadians(yaw - ship.origin().getYaw()));
        v = rotateX(v, -Math.toRadians(pitch));
        return rotateZ(v, -Math.toRadians(roll));
    }

    private Vector forwardTransform(Vector local, ShipModel ship, ShipRuntimeState runtime) {
        float yaw = registry.position(ship).getYaw();
        float pitch = runtime == null ? ship.pitch() : runtime.pitch();
        float roll = runtime == null ? 0.0f : runtime.roll();
        Vector v = rotateZ(local, Math.toRadians(roll));
        v = rotateX(v, Math.toRadians(pitch));
        return rotateY(v, Math.toRadians(yaw - ship.origin().getYaw()));
    }

    private Vector rotateY(Vector v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Vector(v.getX() * c - v.getZ() * s, v.getY(), v.getX() * s + v.getZ() * c);
    }

    private Vector rotateX(Vector v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Vector(v.getX(), v.getY() * c - v.getZ() * s, v.getY() * s + v.getZ() * c);
    }

    private Vector rotateZ(Vector v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Vector(v.getX() * c - v.getY() * s, v.getX() * s + v.getY() * c, v.getZ());
    }

    private double segmentAabb(Vector a, Vector b, double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        double tMin = 0.0, tMax = 1.0;
        double[] origin = {a.getX(), a.getY(), a.getZ()};
        double[] delta = {b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ()};
        double[] min = {minX, minY, minZ}, max = {maxX, maxY, maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(delta[i]) < 1.0E-9) {
                if (origin[i] < min[i] || origin[i] > max[i]) return -1.0;
                continue;
            }
            double inv = 1.0 / delta[i];
            double t1 = (min[i] - origin[i]) * inv, t2 = (max[i] - origin[i]) * inv;
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
        while (it.hasNext()) if (Bukkit.getEntity(it.next().getKey()) == null) it.remove();
    }

    private record Impact(ShipModel ship, ShipBlock block, Location location, double t) {}
    private record BlockHit(ShipBlock block, double distance, Location location) {}
    private record ProjectileType(double damage, double radius, int maxBlocks, boolean explosive,
                                  double penetrationFalloff, double minimumFalloff) {}
}
