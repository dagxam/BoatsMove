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
import java.util.UUID;

/** Simulates compartment-based water ingress, transfer and sinking. */
public final class ShipFloodingManager {
    private static final int MAX_CELLS = 4096;
    private static final int MAX_LEAKS_PER_TICK = 64;
    private static final double FLOW_RATE = 0.018;
    private static final double DRAIN_RATE = 0.0010;
    private static final double SINK_THRESHOLD = 0.72;
    private static final double MAX_WATER_LEVEL_STEP = 0.035;

    private final ShipRegistry registry;
    private final Map<UUID, TopologyCache> topology = new HashMap<>();
    private final Map<UUID, FloodState> floodStates = new HashMap<>();

    public ShipFloodingManager(ShipRegistry registry, ShipDisplayManager ignoredDisplays) {
        this.registry = registry;
    }

    public void tick() {
        for (ShipModel ship : registry.all()) {
            if (ship.state() == ShipState.ACTIVE && ship.blockCount() > 0) {
                update(ship);
            }
        }
    }

    private void update(ShipModel ship) {
        Location position = registry.position(ship);
        World world = position.getWorld();
        if (world == null) {
            return;
        }

        long signature = signature(ship);
        TopologyCache cache = topology.get(ship.id());
        FloodState state = floodStates.computeIfAbsent(ship.id(), ignored -> new FloodState());

        if (cache == null) {
            cache = buildTopology(ship, signature);
            topology.put(ship.id(), cache);
            state.reconcile(cache);
        } else if (cache.signature() != signature) {
            TopologyCache previous = cache;
            cache = buildTopology(ship, signature);
            migrateWater(previous, cache, state);
            topology.put(ship.id(), cache);
            state.reconcile(cache);
        }

        if (cache.compartments().isEmpty()) {
            ship.flooding(Math.max(0.0, ship.flooding() - DRAIN_RATE));
            ship.floodSides(0, 0, 0, 0);
            return;
        }

        int totalVolume = 0;
        int frontLeaks = 0;
        int rearLeaks = 0;
        int leftLeaks = 0;
        int rightLeaks = 0;

        for (Compartment compartment : cache.compartments()) {
            totalVolume += compartment.cells().size();

            CompartmentState compartmentState = state.compartments.computeIfAbsent(
                    compartment.seed(), ignored -> new CompartmentState());

            LeakInfo leaks = findLeaks(world, position, ship, cache.hull(), compartment);
            double current = compartmentState.level;

            if (leaks.count() > 0) {
                double headroom = Math.max(
                        0.0,
                        leaks.surface() - compartment.bottomY() - current * compartment.height()
                );
                double pressure = Math.max(
                        0.0,
                        Math.min(1.0, headroom / Math.max(1, compartment.height()))
                );
                double volumeFactor = 1.0 / Math.max(1.0, Math.sqrt(compartment.cells().size()));
                double flow = Math.min(
                        MAX_WATER_LEVEL_STEP,
                        leaks.count() * FLOW_RATE * volumeFactor * (0.25 + pressure)
                );
                compartmentState.level = Math.min(1.0, current + flow);
            } else if (current > 0.0) {
                compartmentState.level = Math.max(0.0, current - DRAIN_RATE);
            }

            frontLeaks += leaks.front();
            rearLeaks += leaks.rear();
            leftLeaks += leaks.left();
            rightLeaks += leaks.right();
        }

        double weightedFlood = 0.0;
        for (Compartment compartment : cache.compartments()) {
            CompartmentState compartmentState = state.compartments.get(compartment.seed());
            if (compartmentState != null) {
                weightedFlood += compartmentState.level * compartment.cells().size();
            }
        }

        double scalar = totalVolume == 0 ? 0.0 : weightedFlood / totalVolume;
        ship.flooding(Math.max(scalar, ship.flooding() * 0.995));

        int sideTotal = Math.max(1, frontLeaks + rearLeaks + leftLeaks + rightLeaks);
        ship.floodSides(
                (double) frontLeaks / sideTotal,
                (double) rearLeaks / sideTotal,
                (double) leftLeaks / sideTotal,
                (double) rightLeaks / sideTotal
        );

        applySinking(ship, ship.floodFront(), ship.floodRear(), ship.floodLeft(), ship.floodRight());
    }

    /** Rebuilds compartment levels from cell overlap so destroying a wall conserves water volume. */
    private void migrateWater(TopologyCache oldCache, TopologyCache newCache, FloodState state) {
        if (oldCache.compartments().isEmpty() || newCache.compartments().isEmpty()) {
            state.compartments.clear();
            return;
        }

        Map<Pos, Double> oldWater = new HashMap<>();
        for (Compartment old : oldCache.compartments()) {
            CompartmentState oldState = state.compartments.get(old.seed());
            double level = oldState == null ? 0.0 : Math.max(0.0, Math.min(1.0, oldState.level));
            for (Pos cell : old.cells()) {
                oldWater.put(cell, level);
            }
        }

        Map<Pos, CompartmentState> migrated = new HashMap<>();
        for (Compartment next : newCache.compartments()) {
            double water = 0.0;
            for (Pos cell : next.cells()) {
                water += oldWater.getOrDefault(cell, 0.0);
            }

            CompartmentState result = new CompartmentState();
            result.level = Math.max(0.0, Math.min(1.0, water / Math.max(1, next.cells().size())));
            migrated.put(next.seed(), result);
        }

        state.compartments.clear();
        state.compartments.putAll(migrated);
    }

    private LeakInfo findLeaks(World world, Location position, ShipModel ship, Set<Pos> hull, Compartment compartment) {
        int leaks = 0;
        int front = 0;
        int rear = 0;
        int left = 0;
        int right = 0;
        double surfaceSum = 0.0;
        int samples = 0;
        List<Pos> points = new ArrayList<>();

        outer:
        for (Pos cell : compartment.cells()) {
            for (Pos direction : DIRECTIONS) {
                Pos outside = cell.add(direction);
                if (hull.contains(outside)) {
                    continue;
                }

                WaterSample sample = externalWater(world, position, ship, outside);
                if (!sample.water()) {
                    continue;
                }

                leaks++;
                surfaceSum += sample.surface();
                samples++;
                points.add(outside);

                if (direction.z() > 0) {
                    front++;
                } else if (direction.z() < 0) {
                    rear++;
                } else if (direction.x() < 0) {
                    left++;
                } else if (direction.x() > 0) {
                    right++;
                }

                if (leaks >= MAX_LEAKS_PER_TICK) {
                    break outer;
                }
            }
        }

        double surface = samples == 0 ? Double.NEGATIVE_INFINITY : surfaceSum / samples;
        return new LeakInfo(leaks, front, rear, left, right, surface, points);
    }

    private void applySinking(ShipModel ship, double front, double rear, double left, double right) {
        ShipRuntimeState runtime = registry.runtime(ship.id());
        if (runtime == null) {
            return;
        }

        double flood = ship.flooding();
        if (flood <= SINK_THRESHOLD) {
            if (runtime.verticalSpeed() < 0.0) {
                runtime.verticalSpeed(runtime.verticalSpeed() * 0.90);
            }
            return;
        }

        double severity = (flood - SINK_THRESHOLD) / (1.0 - SINK_THRESHOLD);
        runtime.verticalSpeed(Math.min(
                runtime.verticalSpeed(),
                -Math.min(0.085, 0.012 + severity * 0.073)
        ));
        runtime.pitch(approach(runtime.pitch(), (float) ((rear - front) * 10.0 * severity), 0.12f));
        runtime.roll(approach(runtime.roll(), (float) ((left - right) * 10.0 * severity), 0.12f));
    }

    private TopologyCache buildTopology(ShipModel ship, long signature) {
        Set<Pos> hull = new HashSet<>();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (ShipBlock block : ship.blocks()) {
            Pos position = new Pos(block.x(), block.y(), block.z());
            hull.add(position);
            minX = Math.min(minX, position.x());
            maxX = Math.max(maxX, position.x());
            minY = Math.min(minY, position.y());
            maxY = Math.max(maxY, position.y());
            minZ = Math.min(minZ, position.z());
            maxZ = Math.max(maxZ, position.z());
        }

        if (hull.isEmpty()) {
            return new TopologyCache(signature, hull, List.of());
        }

        Set<Pos> outside = floodOutside(hull, minX, maxX, minY, maxY, minZ, maxZ);
        Set<Pos> unvisited = new HashSet<>();
        int cellCount = 0;

        for (int x = minX + 1; x < maxX && cellCount < MAX_CELLS; x++) {
            for (int y = minY + 1; y < maxY && cellCount < MAX_CELLS; y++) {
                for (int z = minZ + 1; z < maxZ && cellCount < MAX_CELLS; z++) {
                    Pos position = new Pos(x, y, z);
                    if (!hull.contains(position) && !outside.contains(position)) {
                        unvisited.add(position);
                        cellCount++;
                    }
                }
            }
        }

        List<Compartment> compartments = new ArrayList<>();
        while (!unvisited.isEmpty()) {
            Pos seed = unvisited.stream()
                    .min(Comparator.comparingInt(Pos::y)
                            .thenComparingInt(Pos::x)
                            .thenComparingInt(Pos::z))
                    .orElseThrow();

            Set<Pos> cells = new HashSet<>();
            Queue<Pos> queue = new ArrayDeque<>();
            queue.add(seed);
            unvisited.remove(seed);

            while (!queue.isEmpty()) {
                Pos current = queue.poll();
                cells.add(current);
                for (Pos direction : DIRECTIONS) {
                    Pos next = current.add(direction);
                    if (unvisited.remove(next)) {
                        queue.add(next);
                    }
                }
            }

            compartments.add(new Compartment(seed, cells));
        }

        return new TopologyCache(signature, hull, compartments);
    }

    private Set<Pos> floodOutside(Set<Pos> hull, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int loX = minX - 1;
        int hiX = maxX + 1;
        int loY = minY - 1;
        int hiY = maxY + 1;
        int loZ = minZ - 1;
        int hiZ = maxZ + 1;

        Set<Pos> outside = new HashSet<>();
        Queue<Pos> queue = new ArrayDeque<>();
        Pos start = new Pos(loX, loY, loZ);
        outside.add(start);
        queue.add(start);

        while (!queue.isEmpty() && outside.size() < MAX_CELLS * 2) {
            Pos current = queue.poll();
            for (Pos direction : DIRECTIONS) {
                Pos next = current.add(direction);
                if (next.x() < loX || next.x() > hiX
                        || next.y() < loY || next.y() > hiY
                        || next.z() < loZ || next.z() > hiZ) {
                    continue;
                }
                if (hull.contains(next) || !outside.add(next)) {
                    continue;
                }
                queue.add(next);
            }
        }

        return outside;
    }

    private WaterSample externalWater(World world, Location position, ShipModel ship, Pos local) {
        Pos transformed = rotateLocal(local, ship.yaw() - ship.origin().getYaw());
        int x = (int) Math.floor(position.getX() + transformed.x() + 0.5);
        int y = (int) Math.floor(position.getY() + local.y());
        int z = (int) Math.floor(position.getZ() + transformed.z() + 0.5);

        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return WaterSample.NONE;
        }

        for (int sy = y + 2; sy >= y - 2; sy--) {
            if (world.getBlockAt(x, sy, z).getType() == Material.WATER) {
                return new WaterSample(true, sy + 1.0);
            }
        }
        return WaterSample.NONE;
    }

    private Pos rotateLocal(Pos position, double degrees) {
        return switch (Math.floorMod((int) Math.round(degrees / 90.0), 4)) {
            case 1 -> new Pos(-position.z(), position.y(), position.x());
            case 2 -> new Pos(-position.x(), position.y(), -position.z());
            case 3 -> new Pos(position.z(), position.y(), -position.x());
            default -> position;
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

    public List<CompartmentWater> compartmentWater(ShipModel ship) {
        FloodState state = floodStates.get(ship.id());
        TopologyCache cache = topology.get(ship.id());
        if (state == null || cache == null) {
            return List.of();
        }

        List<CompartmentWater> result = new ArrayList<>();
        for (Compartment compartment : cache.compartments()) {
            CompartmentState compartmentState = state.compartments.get(compartment.seed());
            if (compartmentState == null || compartmentState.level <= 0.001) {
                continue;
            }

            List<LeakPoint> leaks = new ArrayList<>();
            for (Pos cell : compartment.cells()) {
                for (Pos direction : DIRECTIONS) {
                    Pos outside = cell.add(direction);
                    if (!cache.hull().contains(outside)) {
                        leaks.add(new LeakPoint(outside.x(), outside.y(), outside.z()));
                        if (leaks.size() >= 12) {
                            break;
                        }
                    }
                }
                if (leaks.size() >= 12) {
                    break;
                }
            }

            result.add(new CompartmentWater(
                    compartment.seed().x(),
                    compartment.seed().y(),
                    compartment.seed().z(),
                    compartmentState.level,
                    compartment.cells().size(),
                    compartment.bottomY(),
                    compartment.topY(),
                    compartment.minX(),
                    compartment.maxX(),
                    compartment.minZ(),
                    compartment.maxZ(),
                    List.copyOf(leaks)
            ));
        }
        return List.copyOf(result);
    }

    public record CompartmentWater(
            int seedX,
            int seedY,
            int seedZ,
            double level,
            int volume,
            int bottomY,
            int topY,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            List<LeakPoint> leaks
    ) {
        public int width() {
            return Math.max(1, maxX - minX + 1);
        }

        public int depth() {
            return Math.max(1, maxZ - minZ + 1);
        }
    }

    public record LeakPoint(int x, int y, int z) {
    }

    public double frontLeak(ShipModel ship) {
        return ship.floodFront();
    }

    public double rearLeak(ShipModel ship) {
        return ship.floodRear();
    }

    public double leftLeak(ShipModel ship) {
        return ship.floodLeft();
    }

    public double rightLeak(ShipModel ship) {
        return ship.floodRight();
    }

    private static float approach(float current, float target, float amount) {
        return current < target
                ? Math.min(target, current + amount)
                : Math.max(target, current - amount);
    }

    private static final List<Pos> DIRECTIONS = List.of(
            new Pos(1, 0, 0),
            new Pos(-1, 0, 0),
            new Pos(0, 1, 0),
            new Pos(0, -1, 0),
            new Pos(0, 0, 1),
            new Pos(0, 0, -1)
    );

    private record Pos(int x, int y, int z) {
        Pos add(Pos other) {
            return new Pos(x + other.x(), y + other.y(), z + other.z());
        }
    }

    private record Compartment(Pos seed, Set<Pos> cells) {
        int bottomY() {
            return cells.stream().mapToInt(Pos::y).min().orElse(seed.y());
        }

        int topY() {
            return cells.stream().mapToInt(Pos::y).max().orElse(seed.y());
        }

        int minX() {
            return cells.stream().mapToInt(Pos::x).min().orElse(seed.x());
        }

        int maxX() {
            return cells.stream().mapToInt(Pos::x).max().orElse(seed.x());
        }

        int minZ() {
            return cells.stream().mapToInt(Pos::z).min().orElse(seed.z());
        }

        int maxZ() {
            return cells.stream().mapToInt(Pos::z).max().orElse(seed.z());
        }

        int height() {
            return Math.max(1, topY() - bottomY() + 1);
        }
    }

    private record TopologyCache(long signature, Set<Pos> hull, List<Compartment> compartments) {
    }

    private record LeakInfo(
            int count,
            int front,
            int rear,
            int left,
            int right,
            double surface,
            List<Pos> points
    ) {
    }

    private record WaterSample(boolean water, double surface) {
        private static final WaterSample NONE = new WaterSample(false, Double.NEGATIVE_INFINITY);
    }

    private static final class CompartmentState {
        private double level;
    }

    private static final class FloodState {
        private final Map<Pos, CompartmentState> compartments = new HashMap<>();

        private void reconcile(TopologyCache cache) {
            Set<Pos> active = new HashSet<>();
            for (Compartment compartment : cache.compartments()) {
                active.add(compartment.seed());
            }
            compartments.keySet().removeIf(seed -> !active.contains(seed));
        }
    }
}
