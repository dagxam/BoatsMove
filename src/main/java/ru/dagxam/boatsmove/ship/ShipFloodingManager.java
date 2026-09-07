package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/** Simulates water entering structural holes and spreading through enclosed ship volume. */
public final class ShipFloodingManager {
    private static final int MAX_CELLS = 2048;
    private static final double FLOOD_PER_HOLE = 0.018;
    private static final double DRAIN_RATE = 0.0015;
    private static final double MAX_FLOOD_GROWTH_PER_TICK = 0.045;

    private final ShipRegistry registry;

    public ShipFloodingManager(ShipRegistry registry, ShipDisplayManager ignoredDisplays) {
        this.registry = registry;
    }

    public void tick() {
        for (ShipModel ship : registry.all()) {
            if (ship.state() == ShipState.ACTIVE && ship.blockCount() > 0) update(ship);
        }
    }

    private void update(ShipModel ship) {
        Location position = registry.position(ship);
        World world = position.getWorld();
        if (world == null) return;

        Set<Pos> hull = new HashSet<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ShipBlock block : ship.blocks()) {
            hull.add(new Pos(block.x(), block.y(), block.z()));
            minX = Math.min(minX, block.x()); maxX = Math.max(maxX, block.x());
            minY = Math.min(minY, block.y()); maxY = Math.max(maxY, block.y());
            minZ = Math.min(minZ, block.z()); maxZ = Math.max(maxZ, block.z());
        }

        Set<Pos> outside = floodOutside(hull, minX, maxX, minY, maxY, minZ, maxZ);
        Set<Pos> interior = new HashSet<>();
        int enclosedCells = 0;
        for (int x = minX + 1; x < maxX && enclosedCells < MAX_CELLS; x++) {
            for (int y = minY + 1; y < maxY && enclosedCells < MAX_CELLS; y++) {
                for (int z = minZ + 1; z < maxZ; z++) {
                    Pos p = new Pos(x, y, z);
                    if (!hull.contains(p) && !outside.contains(p)) {
                        interior.add(p);
                        if (++enclosedCells >= MAX_CELLS) break;
                    }
                }
            }
        }

        if (interior.isEmpty()) {
            ship.flooding(Math.max(0.0, ship.flooding() - DRAIN_RATE));
            return;
        }

        int leaks = 0;
        for (Pos cell : interior) {
            for (Pos direction : DIRECTIONS) {
                Pos adjacent = cell.add(direction);
                if (!hull.contains(adjacent) && isExternalWater(world, position, ship, adjacent)) {
                    leaks++;
                    break;
                }
            }
            if (leaks >= 32) break;
        }

        double current = ship.flooding();
        if (leaks > 0) {
            current = Math.min(1.0, current + Math.min(MAX_FLOOD_GROWTH_PER_TICK, leaks * FLOOD_PER_HOLE));
        } else if (current > 0.0) {
            current = Math.max(0.0, current - DRAIN_RATE);
        }
        ship.flooding(current);
    }

    private boolean isExternalWater(World world, Location position, ShipModel ship, Pos local) {
        Pos transformed = rotateLocal(local, ship.yaw() - ship.origin().getYaw());
        int x = (int) Math.floor(position.getX() + transformed.x + 0.5);
        int y = (int) Math.floor(position.getY() + local.y);
        int z = (int) Math.floor(position.getZ() + transformed.z + 0.5);
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
        Material at = world.getBlockAt(x, y, z).getType();
        return at == Material.WATER || world.getBlockAt(x, y + 1, z).getType() == Material.WATER;
    }

    private Pos rotateLocal(Pos p, double degrees) {
        int quarterTurns = Math.floorMod((int) Math.round(degrees / 90.0), 4);
        return switch (quarterTurns) {
            case 1 -> new Pos(-p.z, p.y, p.x);
            case 2 -> new Pos(-p.x, p.y, -p.z);
            case 3 -> new Pos(p.z, p.y, -p.x);
            default -> p;
        };
    }

    private Set<Pos> floodOutside(Set<Pos> hull, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int loX = minX - 1, hiX = maxX + 1;
        int loY = minY - 1, hiY = maxY + 1;
        int loZ = minZ - 1, hiZ = maxZ + 1;
        Pos start = new Pos(loX, loY, loZ);
        Queue<Pos> queue = new ArrayDeque<>();
        Set<Pos> outside = new HashSet<>();
        queue.add(start);
        outside.add(start);
        while (!queue.isEmpty() && outside.size() < MAX_CELLS * 2) {
            Pos current = queue.poll();
            for (Pos direction : DIRECTIONS) {
                Pos next = current.add(direction);
                if (next.x < loX || next.x > hiX || next.y < loY || next.y > hiY || next.z < loZ || next.z > hiZ) continue;
                if (hull.contains(next) || !outside.add(next)) continue;
                queue.add(next);
            }
        }
        return outside;
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
