package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/** Simulates water entering holes and spreading through the ship's open interior. */
public final class ShipFloodingManager {
    private final ShipRegistry registry;
    private final ShipDisplayManager displays;

    private final int maxCellsPerTick = 256;
    private final double leakRate = 0.012;
    private final double pumpOutRate = 0.002;

    public ShipFloodingManager(ShipRegistry registry, ShipDisplayManager displays) {
        this.registry = registry;
        this.displays = displays;
    }

    public void tick() {
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE || ship.blockCount() == 0) continue;
            update(ship);
        }
    }

    private void update(ShipModel ship) {
        Location origin = registry.position(ship);
        World world = origin.getWorld();
        if (world == null) return;

        Set<Pos> occupied = new HashSet<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ShipBlock block : ship.blocks()) {
            Pos p = new Pos(block.x(), block.y(), block.z());
            occupied.add(p);
            minX = Math.min(minX, block.x()); maxX = Math.max(maxX, block.x());
            minY = Math.min(minY, block.y()); maxY = Math.max(maxY, block.y());
            minZ = Math.min(minZ, block.z()); maxZ = Math.max(maxZ, block.z());
        }

        Set<Pos> interior = findInterior(occupied, minX, maxX, minY, maxY, minZ, maxZ);
        if (interior.isEmpty()) {
            // An open-bottom / skeletal hull cannot retain a meaningful internal volume.
            ship.flooding(Math.max(0.0, ship.flooding() - pumpOutRate));
            return;
        }

        int leakingHoles = 0;
        for (Pos cell : interior) {
            for (Pos direction : DIRECTIONS) {
                Pos wall = cell.add(direction);
                if (!occupied.contains(wall) && isWaterNear(world, origin, wall)) {
                    leakingHoles++;
                    break;
                }
            }
            if (leakingHoles >= 16) break;
        }

        double target = Math.min(1.0, leakingHoles * leakRate);
        double current = ship.flooding();
        if (target > current) current = Math.min(target, current + leakRate);
        else current = Math.max(target, current - pumpOutRate);
        ship.flooding(current);

        // A fully flooded ship becomes unstable and effectively loses buoyancy.
        if (current >= 0.98) {
            ship.flooding(1.0);
        }
    }

    private boolean isWaterNear(World world, Location origin, Pos local) {
        double yaw = Math.toRadians(0.0);
        double wx = origin.getX() + local.x + 0.5;
        double wz = origin.getZ() + local.z + 0.5;
        int x = (int) Math.floor(wx);
        int z = (int) Math.floor(wz);
        int y = (int) Math.floor(origin.getY() + local.y);
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
        Material material = world.getBlockAt(x, y, z).getType();
        if (material == Material.WATER) return true;
        return world.getBlockAt(x, y + 1, z).getType() == Material.WATER;
    }

    private Set<Pos> findInterior(Set<Pos> occupied, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        // Expand the search box by one cell. Flood-fill from outside; unvisited cells are enclosed volume.
        minX--; maxX++; minY--; maxY++; minZ--; maxZ++;
        Queue<Pos> queue = new ArrayDeque<>();
        Set<Pos> outside = new HashSet<>();
        Pos start = new Pos(minX, minY, minZ);
        queue.add(start);
        outside.add(start);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < maxCellsPerTick * 8) {
            Pos current = queue.poll();
            for (Pos direction : DIRECTIONS) {
                Pos next = current.add(direction);
                if (next.x < minX || next.x > maxX || next.y < minY || next.y > maxY || next.z < minZ || next.z > maxZ) continue;
                if (occupied.contains(next) || !outside.add(next)) continue;
                queue.add(next);
            }
        }
        Set<Pos> interior = new HashSet<>();
        for (int x = minX + 1; x < maxX; x++) for (int y = minY + 1; y < maxY; y++) for (int z = minZ + 1; z < maxZ; z++) {
            Pos p = new Pos(x, y, z);
            if (!occupied.contains(p) && !outside.contains(p)) {
                interior.add(p);
                if (interior.size() >= maxCellsPerTick) return interior;
            }
        }
        return interior;
    }

    private static final List<Pos> DIRECTIONS = List.of(
            new Pos(1, 0, 0), new Pos(-1, 0, 0),
            new Pos(0, 1, 0), new Pos(0, -1, 0),
            new Pos(0, 0, 1), new Pos(0, 0, -1)
    );

    private record Pos(int x, int y, int z) {
        Pos add(Pos other) { return new Pos(x + other.x, y + other.y, z + other.z); }
    }
}
