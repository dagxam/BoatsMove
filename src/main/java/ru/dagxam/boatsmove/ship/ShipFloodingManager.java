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

/** Simulates compartment-based water ingress through hull breaches. */
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

    public ShipFloodingManager(ShipRegistry registry, ShipDisplayManager ignoredDisplays) { this.registry = registry; }

    public void tick() {
        for (ShipModel ship : registry.all()) {
            if (ship.state() == ShipState.ACTIVE && ship.blockCount() > 0) update(ship);
        }
    }

    public List<CompartmentWater> compartmentWater(ShipModel ship) {
        FloodState state = floodStates.get(ship.id());
        TopologyCache cache = topology.get(ship.id());
        if (state == null || cache == null) return List.of();
        List<CompartmentWater> result = new ArrayList<>();
        for (Compartment compartment : cache.compartments) {
            CompartmentState value = state.compartments.get(compartment.seed);
            if (value != null && value.level > 0.001) {
                result.add(new CompartmentWater(compartment.seed.x, compartment.seed.y, compartment.seed.z,
                        value.level, compartment.cells.size(), compartment.bottomY(), compartment.topY()));
            }
        }
        return List.copyOf(result);
    }

    public record CompartmentWater(int seedX, int seedY, int seedZ, double level, int volume, int bottomY, int topY) { }

    private void update(ShipModel ship) {
        Location position = registry.position(ship);
        World world = position.getWorld();
        if (world == null) return;
        long signature = signature(ship);
        TopologyCache cache = topology.compute(ship.id(), (id, old) -> old == null || old.signature != signature ? buildTopology(ship, signature) : old);
        if (cache.compartments.isEmpty()) {
            ship.flooding(Math.max(0.0, ship.flooding() - DRAIN_RATE));
            ship.floodSides(0, 0, 0, 0);
            return;
        }
        FloodState state = floodStates.computeIfAbsent(ship.id(), ignored -> new FloodState());
        state.reconcile(cache);
        int totalVolume = 0;
        double weightedFlood = 0;
        int frontLeaks = 0, rearLeaks = 0, leftLeaks = 0, rightLeaks = 0;
        for (Compartment compartment : cache.compartments) {
            totalVolume += compartment.cells.size();
            CompartmentState compartmentState = state.compartments.computeIfAbsent(compartment.seed, ignored -> new CompartmentState());
            LeakInfo leaks = findLeaks(world, position, ship, cache.hull, compartment);
            double currentLevel = compartmentState.level;
            if (leaks.count > 0) {
                double headroom = Math.max(0, leaks.surface - compartment.bottomY() - currentLevel * compartment.height());
                double pressure = Math.max(0, Math.min(1, headroom / Math.max(1, compartment.height())));
                double volumeFactor = 1.0 / Math.max(1, Math.sqrt(compartment.cells.size()));
                double flow = Math.min(MAX_WATER_LEVEL_STEP, leaks.count * FLOW_RATE * volumeFactor * (0.25 + pressure));
                compartmentState.level = Math.min(1, currentLevel + flow);
            } else if (currentLevel > 0) compartmentState.level = Math.max(0, currentLevel - DRAIN_RATE);
            weightedFlood += compartmentState.level * compartment.cells.size();
            frontLeaks += leaks.front; rearLeaks += leaks.rear; leftLeaks += leaks.left; rightLeaks += leaks.right;
        }
        double scalar = totalVolume == 0 ? 0 : weightedFlood / totalVolume;
        ship.flooding(Math.max(scalar, ship.flooding() * 0.995));
        int sideTotal = Math.max(1, frontLeaks + rearLeaks + leftLeaks + rightLeaks);
        ship.floodSides((double) frontLeaks / sideTotal, (double) rearLeaks / sideTotal,
                (double) leftLeaks / sideTotal, (double) rightLeaks / sideTotal);
        applySinking(ship, ship.floodFront(), ship.floodRear(), ship.floodLeft(), ship.floodRight());
    }

    private LeakInfo findLeaks(World world, Location position, ShipModel ship, Set<Pos> hull, Compartment compartment) {
        int leaks = 0, front = 0, rear = 0, left = 0, right = 0; double surfaceSum = 0; int samples = 0;
        for (Pos cell : compartment.cells) {
            for (Pos direction : DIRECTIONS) {
                Pos opening = cell.add(direction);
                if (hull.contains(opening)) continue;
                WaterSample sample = externalWater(world, position, ship, opening);
                if (!sample.water) continue;
                leaks++; surfaceSum += sample.surface; samples++;
                if (direction.z > 0) front++; else if (direction.z < 0) rear++; else if (direction.x < 0) left++; else if (direction.x > 0) right++;
                if (leaks >= MAX_LEAKS_PER_TICK) break;
            }
            if (leaks >= MAX_LEAKS_PER_TICK) break;
        }
        return new LeakInfo(leaks, front, rear, left, right, samples == 0 ? Double.NEGATIVE_INFINITY : surfaceSum / samples);
    }

    private void applySinking(ShipModel ship, double front, double rear, double left, double right) {
        ShipRuntimeState runtime = registry.runtime(ship.id());
        if (runtime == null) return;
        double flood = ship.flooding();
        if (flood <= SINK_THRESHOLD) { if (runtime.verticalSpeed() < 0) runtime.verticalSpeed(runtime.verticalSpeed() * 0.90); return; }
        double severity = (flood - SINK_THRESHOLD) / (1 - SINK_THRESHOLD);
        runtime.verticalSpeed(Math.min(runtime.verticalSpeed(), -Math.min(0.085, 0.012 + severity * 0.073)));
        runtime.pitch(approach(runtime.pitch(), (float) ((rear - front) * 10 * severity), 0.12f));
        runtime.roll(approach(runtime.roll(), (float) ((left - right) * 10 * severity), 0.12f));
    }

    private TopologyCache buildTopology(ShipModel ship, long signature) {
        Set<Pos> hull = new HashSet<>(); int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ShipBlock block : ship.blocks()) { Pos p = new Pos(block.x(), block.y(), block.z()); hull.add(p); minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x); minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y); minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z); }
        Set<Pos> outside = floodOutside(hull, minX, maxX, minY, maxY, minZ, maxZ);
        Set<Pos> unvisited = new HashSet<>(); int cellCount = 0;
        for (int x = minX + 1; x < maxX && cellCount < MAX_CELLS; x++) for (int y = minY + 1; y < maxY && cellCount < MAX_CELLS; y++) for (int z = minZ + 1; z < maxZ && cellCount < MAX_CELLS; z++) { Pos p = new Pos(x, y, z); if (!hull.contains(p) && !outside.contains(p)) { unvisited.add(p); cellCount++; } }
        List<Compartment> compartments = new ArrayList<>();
        while (!unvisited.isEmpty()) {
            Pos seed = unvisited.stream().min(Comparator.comparingInt((Pos p) -> p.y).thenComparingInt(p -> p.x).thenComparingInt(p -> p.z)).orElseThrow();
            Set<Pos> cells = new HashSet<>(); Queue<Pos> queue = new ArrayDeque<>(); queue.add(seed); unvisited.remove(seed);
            while (!queue.isEmpty()) { Pos current = queue.poll(); cells.add(current); for (Pos direction : DIRECTIONS) { Pos next = current.add(direction); if (unvisited.remove(next)) queue.add(next); } }
            compartments.add(new Compartment(seed, cells));
        }
        return new TopologyCache(signature, hull, compartments);
    }

    private Set<Pos> floodOutside(Set<Pos> hull, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int loX = minX - 1, hiX = maxX + 1, loY = minY - 1, hiY = maxY + 1, loZ = minZ - 1, hiZ = maxZ + 1;
        Set<Pos> outside = new HashSet<>(); Queue<Pos> queue = new ArrayDeque<>(); Pos start = new Pos(loX, loY, loZ); outside.add(start); queue.add(start);
        while (!queue.isEmpty() && outside.size() < MAX_CELLS * 2) { Pos current = queue.poll(); for (Pos direction : DIRECTIONS) { Pos next = current.add(direction); if (next.x < loX || next.x > hiX || next.y < loY || next.y > hiY || next.z < loZ || next.z > hiZ) continue; if (hull.contains(next) || !outside.add(next)) continue; queue.add(next); } }
        return outside;
    }

    private WaterSample externalWater(World world, Location position, ShipModel ship, Pos local) {
        Pos transformed = rotateLocal(local, ship.yaw() - ship.origin().getYaw());
        int x = (int) Math.floor(position.getX() + transformed.x + 0.5), y = (int) Math.floor(position.getY() + local.y), z = (int) Math.floor(position.getZ() + transformed.z + 0.5);
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return WaterSample.NONE;
        for (int scanY = y + 2; scanY >= y - 2; scanY--) if (world.getBlockAt(x, scanY, z).getType() == Material.WATER) return new WaterSample(true, scanY + 1.0);
        return WaterSample.NONE;
    }

    private Pos rotateLocal(Pos p, double degrees) { return switch (Math.floorMod((int) Math.round(degrees / 90.0), 4)) { case 1 -> new Pos(-p.z, p.y, p.x); case 2 -> new Pos(-p.x, p.y, -p.z); case 3 -> new Pos(p.z, p.y, -p.x); default -> p; }; }
    private long signature(ShipModel ship) { long result = 1125899906842597L; for (ShipBlock block : ship.blocks()) { result = 31 * result + block.x(); result = 31 * result + block.y(); result = 31 * result + block.z(); } return result; }
    public double frontLeak(ShipModel ship) { return ship.floodFront(); }
    public double rearLeak(ShipModel ship) { return ship.floodRear(); }
    public double leftLeak(ShipModel ship) { return ship.floodLeft(); }
    public double rightLeak(ShipModel ship) { return ship.floodRight(); }
    private static float approach(float current, float target, float amount) { return current < target ? Math.min(target, current + amount) : Math.max(target, current - amount); }
    private static final List<Pos> DIRECTIONS = List.of(new Pos(1,0,0), new Pos(-1,0,0), new Pos(0,1,0), new Pos(0,-1,0), new Pos(0,0,1), new Pos(0,0,-1));
    private record Pos(int x, int y, int z) { Pos add(Pos o) { return new Pos(x + o.x, y + o.y, z + o.z); } }
    private record Compartment(Pos seed, Set<Pos> cells) { int bottomY() { return cells.stream().mapToInt(Pos::y).min().orElse(seed.y); } int topY() { return cells.stream().mapToInt(Pos::y).max().orElse(seed.y); } int height() { return Math.max(1, topY() - bottomY() + 1); } }
    private record TopologyCache(long signature, Set<Pos> hull, List<Compartment> compartments) { }
    private record LeakInfo(int count, int front, int rear, int left, int right, double surface) { }
    private record WaterSample(boolean water, double surface) { private static final WaterSample NONE = new WaterSample(false, Double.NEGATIVE_INFINITY); }
    private static final class CompartmentState { private double level; }
    private static final class FloodState { private final Map<Pos, CompartmentState> compartments = new HashMap<>(); private void reconcile(TopologyCache cache) { Set<Pos> active = new HashSet<>(); for (Compartment c : cache.compartments) active.add(c.seed); compartments.keySet().removeIf(seed -> !active.contains(seed)); } }
}
