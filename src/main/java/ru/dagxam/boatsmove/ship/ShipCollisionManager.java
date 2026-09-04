package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.WorldBorder;

import java.util.HashSet;
import java.util.Set;

/** Collision for the complete block-built hull, including world/chunk safety. */
public final class ShipCollisionManager {
    private static final double EPSILON = 1.0e-7;
    private static final double MAX_SWEEP_STEP = 0.20;

    public boolean canMove(ShipModel ship, Location from, Location to) {
        if (from.getWorld() == null || to.getWorld() == null) return false;
        if (!from.getWorld().getUID().equals(to.getWorld().getUID())) return false;

        double distance = from.distance(to);
        int steps = Math.max(1, (int) Math.ceil(distance / MAX_SWEEP_STEP));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            Location sample = interpolate(from, to, t);
            if (!insideWorldHeight(ship, sample)) return false;
            if (!insideWorldBorder(ship, sample)) return false;
            if (!chunksLoadedForHull(ship, sample)) return false;
            if (collidesAt(ship, sample)) return false;
        }
        return true;
    }

    private boolean collidesAt(ShipModel ship, Location position) {
        World world = position.getWorld();
        if (world == null) return true;
        double yaw = Math.toRadians(ship.yaw());
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double half = 0.5 * (Math.abs(cos) + Math.abs(sin));
        Set<Long> checked = new HashSet<>();

        for (ShipBlock block : ship.blocks()) {
            double localX = block.x() + 0.5;
            double localZ = block.z() + 0.5;
            double cx = position.getX() + localX * cos - localZ * sin;
            double cz = position.getZ() + localX * sin + localZ * cos;
            double cy = position.getY() + block.y() + 0.5;

            int minX = floor(cx - half), maxX = floor(cx + half - EPSILON);
            int minY = floor(cy - 0.5), maxY = floor(cy + 0.5 - EPSILON);
            int minZ = floor(cz - half), maxZ = floor(cz + half - EPSILON);

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (!checked.add(pack(x, y, z))) continue;
                        Block obstacle = world.getBlockAt(x, y, z);
                        if (!isSolidObstacle(obstacle)) continue;
                        if (overlaps(obstacle, cx, cy, cz, half)) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean overlaps(Block block, double cx, double cy, double cz, double half) {
        return cx + half > block.getX() + EPSILON
                && cx - half < block.getX() + 1.0 - EPSILON
                && cy + 0.5 > block.getY() + EPSILON
                && cy - 0.5 < block.getY() + 1.0 - EPSILON
                && cz + half > block.getZ() + EPSILON
                && cz - half < block.getZ() + 1.0 - EPSILON;
    }

    private boolean isSolidObstacle(Block block) {
        Material type = block.getType();
        if (type.isAir() || type == Material.WATER || type == Material.BUBBLE_COLUMN) return false;
        return !block.getCollisionShape().getBoundingBoxes().isEmpty();
    }

    private boolean chunksLoadedForHull(ShipModel ship, Location position) {
        World world = position.getWorld();
        if (world == null) return false;

        double yaw = Math.toRadians(ship.yaw());
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        for (ShipBlock block : ship.blocks()) {
            double localX = block.x() + 0.5;
            double localZ = block.z() + 0.5;
            double x = position.getX() + localX * cos - localZ * sin;
            double z = position.getZ() + localX * sin + localZ * cos;
            if (!world.isChunkLoaded(floor(x) >> 4, floor(z) >> 4)) return false;
        }
        return true;
    }

    private boolean insideWorldBorder(ShipModel ship, Location position) {
        World world = position.getWorld();
        if (world == null) return false;
        WorldBorder border = world.getWorldBorder();
        double yaw = Math.toRadians(ship.yaw());
        double cos = Math.cos(yaw), sin = Math.sin(yaw);

        for (ShipBlock block : ship.blocks()) {
            double localX = block.x() + 0.5;
            double localZ = block.z() + 0.5;
            double x = position.getX() + localX * cos - localZ * sin;
            double z = position.getZ() + localX * sin + localZ * cos;
            if (!border.isInside(new Location(world, x, position.getY(), z))) return false;
        }
        return true;
    }

    private boolean insideWorldHeight(ShipModel ship, Location position) {
        World world = position.getWorld();
        if (world == null) return false;
        int minY = ship.blocks().stream().mapToInt(ShipBlock::y).min().orElse(0);
        int maxY = ship.blocks().stream().mapToInt(ShipBlock::y).max().orElse(0);
        return position.getY() + minY >= world.getMinHeight()
                && position.getY() + maxY + 1.0 <= world.getMaxHeight();
    }

    private Location interpolate(Location from, Location to, double t) {
        return new Location(from.getWorld(),
                from.getX() + (to.getX() - from.getX()) * t,
                from.getY() + (to.getY() - from.getY()) * t,
                from.getZ() + (to.getZ() - from.getZ()) * t,
                to.getYaw(), to.getPitch());
    }

    private int floor(double value) { return (int) Math.floor(value); }

    private long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (y & 0xFFF) << 26)
                | (long) (z & 0x3FFFFFF);
    }
}
