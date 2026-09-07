package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/** Simulates compartment-based water ingress through hull breaches. */
public final class ShipFloodingManager {
    private static final int MAX_CELLS = 4096;
    private static final int MAX_LEAKS_PER_TICK = 64;
    private static final double FLOOD_PER_LEAK = 0.010;
    private static final double DRAIN_PER_TICK = 0.0008;
    private static final double SINK_THRESHOLD = 0.72;

    private final ShipRegistry registry;
    private final Map<java.util.UUID, TopologyCache> topology = new HashMap<>();
    private final Map<java.util.UUID, FloodState> floodStates = new HashMap<>();

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

        TopologyCache cache = topology.compute(ship.id(), (id, old) -> {
            long signature = signature(ship);
            if (old == null || old.signature != signature) return buildTopology(ship, signature);
            return old;
        });

        if (cache.compartments.isEmpty()) {
            ship.flooding(Math.max(0.0, ship.flooding() - DRAIN_PER_TICK));
            ship.floodSides(0.0, 0.0, 0.0, 0.0);
            return;
        }

        FloodState state = floodStates.computeIfAbsent(ship.id(), ignored -> new FloodState());
        Set<Pos> hull = cache.hull;
        int totalVolume = 0;
        double weightedFlood = 0.0;
        int frontLeaks = 0, rearLeaks = 0, leftLeaks = 0, rightLeaks = 0;

        for (Compartment compartment : cache.compartments) {
            totalVolume += compartment.cells.size();
            int leaks = 0;
            int front = 0, rear = 0, left = 0, right = 0;

            for (Pos cell : compartment.cells) {
                for (Pos direction : DIRECTIONS) {
                    Pos opening = cell.add(direction);
                    if (hull.contains(opening)) continue;
                    if (!isExternalWater(world, position, ship, opening)) continue;
                    leaks++;
                    if (opening.z > cell.z) front++;
                    else if (opening.z < cell.z) rear++;
                    else if (opening.x < cell.x) left++;
                    else if (opening.x > cell.x) right++;
                    if (leaks >= MAX_LEAKS_PER_TICK) break;
                }
                if (leaks >= MAX_LEAKS_PER_TICK) break;
            }

            double old = state.levels.getOrDefault(compartment.seed, 0.0);
            double next = old;
            if (leaks > 0) {
                double volumeFactor = 1.0 / Math.max(1.0, Math.sqrt(compartment.cells.size()));
                next = Math.min(1.0, old + Math.min(0.08, leaks * FLOOD_PER_LEAK * volumeFactor));
            } else if (old > 0.0) {
                next = Math.max(0.0, old - DRAIN_PER_TICK);
            }
            state.levels.put(compartment.seed, next);
            weightedFlood += next * compartment.cells.size();

            frontLeaks += front;
            rearLeaks += rear;
            leftLeaks += left;
            rightLeaks += right;
        }

        double scalar = totalVolume == 0 ? 0.0 : weightedFlood / totalVolume;
        ship.flooding(Math.max(scalar, ship.flooding() * 0.995));

        int sideTotal = Math.max(1, frontLeaks + rearLeaks + leftLeaks + rightLeaks);
        state.front = (double) frontLeaks / sideTotal;
        state.rear = (double) rearLeaks / sideTotal;
        state.left = (double) leftLeaks / sideTotal;
        state.right = (double) rightLeaks / sideTotal;
        ship.floodSides(state.front, state.rear, state.left, state.right);

        applySinking(ship, state);
    }

    private void applySinking(ShipModel ship, FloodState state) {
        ShipRuntimeState runtime = registry.runtime(ship.id());
        if (runtime == null) return;

        double flood = ship.flooding();
        if (flood <= SINK_THRESHOLD) {
            if (runtime.verticalSpeed() < 0.0) runtime.verticalSpeed(runtime.verticalSpeed() * 0.90);
            return;
        }

        double severity = (flood - SINK_THRESHOLD) / (1.0 - SINK_THRESHOLD);
        double sinkingSpeed = -Math.min(0.085, 0.012 + severity * 0.073);
        runtime.verticalSpeed(Math.min(runtime.verticalSpeed(), sinkingSpeed));

        float targetPitch = (float) ((state.rear - state.front) * 10.0 * severity);
        float targetRoll = (float) ((state.left - state.right) * 10.0 * severity);
        runtime.pitch(approach(runtime.pitch(), targetPitch, 0.12f));
        runtime.roll(approach(runtime.roll(), targetRoll, 0.12f));
    }

    private TopologyCache buildTopology(ShipModel ship, long signature) {
        Set<Pos> hull = new HashSet<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ShipBlock block : ship.blocks()) {
            Pos pos = new Pos(block.x(), block.y(), block.z());
            hull.add(pos);
            minX = Math.min(minX, pos.x); maxX = Math.max(maxX, pos.x);
            minY = Math.min(minY, pos.y); maxY = Math.max(maxY, pos.y);
            minZ = Math.min(minZ, pos.z); maxZ = Math.max(maxZ, pos.z);
        }

        Set<Pos> outside = floodOutside(hull, minX, maxX, minY, maxY, minZ, maxZ);
        Set<Pos> unvisited = new HashSet<>();
        int cellCount = 0;
        for (int x = minX + 1; x < maxX; x++) {
            for (int y = minY + 1; y < maxY; y++) {
                for (int z = minZ + 1; z < maxZ; z++) {
                    Pos p = new Pos(x, y, z);
                    if (!hull.contains(p) && !outside.contains(p) && cellCount < MAX_CELLS) {
                        unvisited.add(p);
                        cellCount++;
                    }
                }
            }
        }

        List<Compartment> compartments = new ArrayList<>();
        while (!unvisited.isEmpty()) {
            Pos seed = unvisited.stream().min(Comparator.comparingInt((Pos p) -> p.y).thenComparingInt(p -> p.x).thenComparingInt(p -> p.z)).orElseThrow();
            Set<Pos> cells = new HashSet<>();
            Queue<Pos> queue = new ArrayDeque<>();
            queue.add(seed);
            unvisited.remove(seed);
            while (!queue.isEmpty()) {
                Pos current = queue.poll();
                cells.add(current);
                for (Pos direction : DIRECTIONS) {
                    Pos next = current.add(direction);
                    if (unvisited.remove(next)) queue.add(next);
                }
            }
            compartments.add(new Compartment(seed, cells));
        }
        return new TopologyCache(signature, hull, compartments);
    }

    private Set<Pos> floodOutside(Set<Pos> hull, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int loX = minX - 1, hiX = maxX + 1;
        int loY = minY - 1, hiY = maxY + 1;
        int loZ = minZ - 1, hiZ = maxZ + 1;
        Set<Pos> outside = new HashSet<>();
        Queue<Pos> queue = new ArrayDeque<>();
        Pos start = new Pos(loX, loY, loZ);
        outside.add(start);
        queue.add(start);
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

    private boolean isExternalWater(World world, Location position, ShipModel ship, Pos local) {
        Pos transformed = rotateLocal(local, ship.yaw() - ship.origin().getYaw());
        int x = (int) Math.floor(position.getX() + transformed.x + 0.5);
        int y = (int) Math.floor(position.getY() + local.y);
        int z = (int) Math.floor(position.getZ() + transformed.z + 0.5);
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
        Material at = world.getBlockAt(x, y, z).getType();
        if (at == Material.WATER) return true;
        return world.getBlockAt(x, y + 1, z).getType() == Material.WATER;
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

    private long signature(ShipModel ship) {
        long result = 1125899906842597L;
        for (ShipBlock block : ship.blocks()) {
            result = 31 * result + block.x();
            result = 31 * result + block.y();
            result = 31 * result + block.z();
        }
        return result;
    }

    public double frontLeak(ShipModel ship) { return floodStates.getOrDefault(ship.id(), new FloodState()).front; }
    public double rearLeak(ShipModel ship) { return floodStates.getOrDefault(ship.id(), new FloodState()).rear; }
    public double leftLeak(ShipModel ship) { return floodStates.getOrDefault(ship.id(), new FloodState()).left; }
    public double rightLeak(ShipModel ship) { return floodStates.getOrDefault(ship.id(), new FloodState()).right; }

    private static float approach(float current, float target, float amount) {
        if (current < target) return Math.min(target, current + amount);
        return Math.max(target, current - amount);
    }

    private static final List<Pos> DIRECTIONS = List.of(
            new Pos(1, 0, 0), new Pos(-1, 0, 0),
            new Pos(0, 1, 0), new Pos(0, -1, 0),
            new Pos(0, 0, 1), new Pos(0, 0, -1)
    );

    private record Pos(int x, int y, int z) {
        Pos add(Pos other) { return new Pos(x + other.x, y + other.y, z + other.z); }
    }

    private record Compartment(Pos seed, Set<Pos> cells) { }

    private record TopologyCache(long signature, Set<Pos> hull, List<Compartment> compartments) { }

    private static final class FloodState {
        private final Map<Pos, Double> levels = new HashMap<>();
        private double front;
        private double rear;
        private double left;
        private double right;
    }
}
