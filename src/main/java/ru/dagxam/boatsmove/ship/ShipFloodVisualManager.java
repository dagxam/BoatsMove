package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Renders compartment-local water volumes and active leak effects. */
public final class ShipFloodVisualManager {
    private static final int MAX_WATER_DISPLAYS = 160;
    private static final int MAX_PER_COMPARTMENT = 32;
    private static final int LEAK_PARTICLE_INTERVAL = 3;
    private static final int MAX_LEAKS_PER_TICK = 12;
    private static final double LEAK_STREAM_LENGTH = 0.85;

    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final ShipFloodingManager flooding;
    private final Map<UUID, List<BlockDisplay>> displays = new HashMap<>();
    private long tick;

    public ShipFloodVisualManager(JavaPlugin plugin, ShipRegistry registry) {
        this(plugin, registry, null);
    }

    public ShipFloodVisualManager(JavaPlugin plugin, ShipRegistry registry, ShipFloodingManager flooding) {
        this.plugin = plugin;
        this.registry = registry;
        this.flooding = flooding;
    }

    public void tick() {
        tick++;
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE || ship.blockCount() == 0) {
                clear(ship.id());
                continue;
            }
            update(ship);
        }
    }

    private void update(ShipModel ship) {
        if (flooding == null) return;

        List<ShipFloodingManager.CompartmentWater> compartments = flooding.compartmentWater(ship);
        if (compartments.isEmpty()) {
            clear(ship.id());
            return;
        }

        Location base = registry.position(ship);
        World world = base.getWorld();
        if (world == null) return;

        List<BlockDisplay> list = displays.computeIfAbsent(ship.id(), ignored -> new ArrayList<>());
        int wanted = 0;
        for (ShipFloodingManager.CompartmentWater compartment : compartments) {
            wanted += waterDisplayCount(compartment);
        }
        wanted = Math.min(MAX_WATER_DISPLAYS, wanted);

        while (list.size() < wanted) list.add(spawn(world, base));
        while (list.size() > wanted) list.remove(list.size() - 1).remove();

        int index = 0;
        for (ShipFloodingManager.CompartmentWater compartment : compartments) {
            int count = waterDisplayCount(compartment);
            if (index >= list.size()) break;

            double level = Math.max(0.01, Math.min(1.0, compartment.level()));
            int cols = Math.max(1, (int) Math.ceil(Math.sqrt(compartment.volume())));
            int rows = Math.max(1, (int) Math.ceil((double) compartment.volume() / cols));

            for (int n = 0; n < count && index < list.size(); n++, index++) {
                double u = cols == 1 ? 0.5 : (n % cols) / (double) (cols - 1);
                double v = rows == 1 ? 0.5 : (n / cols) / (double) (rows - 1);
                double x = compartment.minX() + .15
                        + (compartment.maxX() - compartment.minX() + .7) * u;
                double z = compartment.minZ() + .15
                        + (compartment.maxZ() - compartment.minZ() + .7) * v;
                double y = compartment.bottomY()
                        + level * (compartment.topY() - compartment.bottomY() + 1) - .05;

                BlockDisplay display = list.get(index);
                display.teleport(transform(base, ship, x, y, z));
                float sx = (float) Math.max(.25,
                        Math.min(1.2, (compartment.maxX() - compartment.minX() + 1.0) / Math.max(1, cols) * 1.15));
                float sz = (float) Math.max(.25,
                        Math.min(1.2, (compartment.maxZ() - compartment.minZ() + 1.0) / Math.max(1, rows) * 1.15));
                display.setTransformation(new Transformation(
                        new Vector3f(-.5f, -.02f, -.5f),
                        new AxisAngle4f(),
                        new Vector3f(sx, .055f + .16f * (float) level, sz),
                        new AxisAngle4f()));
            }
        }

        if (tick % LEAK_PARTICLE_INTERVAL == 0) {
            emitLeaks(world, base, ship, compartments);
        }
    }

    private int waterDisplayCount(ShipFloodingManager.CompartmentWater compartment) {
        if (compartment.level() <= .001 || compartment.volume() <= 0) return 0;
        return Math.min(MAX_PER_COMPARTMENT,
                Math.max(1, (int) Math.ceil(Math.sqrt(compartment.volume()) * compartment.level())));
    }

    private BlockDisplay spawn(World world, Location base) {
        return world.spawn(base, BlockDisplay.class, entity -> {
            entity.setBlock(Material.WATER.createBlockData());
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setInterpolationDuration(2);
            entity.setTeleportDuration(2);
            entity.setPersistent(false);
            entity.setViewRange(64);
        });
    }

    private void emitLeaks(World world, Location base, ShipModel ship,
                           List<ShipFloodingManager.CompartmentWater> compartments) {
        int emitted = 0;
        for (ShipFloodingManager.CompartmentWater compartment : compartments) {
            if (emitted >= MAX_LEAKS_PER_TICK || compartment.leaks().isEmpty() || compartment.level() < .02) continue;

            int amount = Math.min(4, compartment.leaks().size());
            for (int i = 0; i < amount && emitted < MAX_LEAKS_PER_TICK; i++, emitted++) {
                ShipFloodingManager.LeakPoint point =
                        compartment.leaks().get((int) ((tick / LEAK_PARTICLE_INTERVAL + i)
                                % compartment.leaks().size()));

                Location leak = transform(base, ship,
                        point.x() + .5, point.y() + .5, point.z() + .5);

                double[] normal = leakNormal(compartment, point);
                double[] worldNormal = transformVector(ship, normal[0], normal[1], normal[2]);

                // If water is present outside the breach, pressure drives water inward.
                // Otherwise the already flooded compartment visibly drains outward.
                boolean outsideWater = isWaterAt(world, leak);
                double direction = outsideWater ? 1.0 : -1.0;

                for (int step = 0; step < 4; step++) {
                    double distance = .08 + step * (LEAK_STREAM_LENGTH / 4.0);
                    Location stream = leak.clone().add(
                            worldNormal[0] * direction * distance,
                            worldNormal[1] * direction * distance,
                            worldNormal[2] * direction * distance);
                    world.spawnParticle(Particle.BUBBLE, stream, 2,
                            .045, .045, .045, .01);
                }

                if (outsideWater || compartment.level() > .45) {
                    world.spawnParticle(Particle.SPLASH, leak, 2,
                            .10, .06, .10, .02);
                }
            }
        }
    }

    private boolean isWaterAt(World world, Location location) {
        var block = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (block.getType() == Material.WATER) return true;
        return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    /** Returns an approximate outward normal for the nearest compartment wall. */
    private double[] leakNormal(ShipFloodingManager.CompartmentWater compartment,
                                ShipFloodingManager.LeakPoint point) {
        double dxMin = Math.abs(point.x() - compartment.minX());
        double dxMax = Math.abs(point.x() - compartment.maxX());
        double dzMin = Math.abs(point.z() - compartment.minZ());
        double dzMax = Math.abs(point.z() - compartment.maxZ());

        double best = dxMin;
        double nx = -1, nz = 0;
        if (dxMax < best) { best = dxMax; nx = 1; nz = 0; }
        if (dzMin < best) { best = dzMin; nx = 0; nz = -1; }
        if (dzMax < best) { nx = 0; nz = 1; }
        return new double[]{nx, 0, nz};
    }

    /** Rotates a local direction with the same yaw/pitch/roll convention as display transforms. */
    private double[] transformVector(ShipModel ship, double x, double y, double z) {
        ShipRuntimeState runtime = registry.runtime(ship.id());
        float runtimePitch = runtime == null ? 0f : runtime.pitch();
        float runtimeRoll = runtime == null ? 0f : runtime.roll();

        double yaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double yawX = x * cos - z * sin;
        double yawZ = x * sin + z * cos;

        double pitch = Math.toRadians(runtimePitch);
        double pitchY = y * Math.cos(pitch) - yawZ * Math.sin(pitch);
        double pitchZ = y * Math.sin(pitch) + yawZ * Math.cos(pitch);

        double roll = Math.toRadians(runtimeRoll);
        double rollX = yawX * Math.cos(roll) - pitchY * Math.sin(roll);
        double rollY = yawX * Math.sin(roll) + pitchY * Math.cos(roll);
        return new double[]{rollX, rollY, pitchZ};
    }

    /** Applies the same rigid-body yaw/pitch/roll convention as the ship display renderer. */
    private Location transform(Location base, ShipModel ship, double x, double y, double z) {
        ShipRuntimeState runtime = registry.runtime(ship.id());
        float runtimePitch = runtime == null ? 0f : runtime.pitch();
        float runtimeRoll = runtime == null ? 0f : runtime.roll();

        double yaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);

        double yawX = x * cos - z * sin;
        double yawZ = x * sin + z * cos;

        double pitch = Math.toRadians(runtimePitch);
        double pitchY = y * Math.cos(pitch) - yawZ * Math.sin(pitch);
        double pitchZ = y * Math.sin(pitch) + yawZ * Math.cos(pitch);

        double roll = Math.toRadians(runtimeRoll);
        double rollX = yawX * Math.cos(roll) - pitchY * Math.sin(roll);
        double rollY = yawX * Math.sin(roll) + pitchY * Math.cos(roll);

        Location result = base.clone().add(rollX, rollY, pitchZ);
        result.setYaw(ship.yaw());
        result.setPitch(runtimePitch);
        return result;
    }

    public void clear(UUID id) {
        List<BlockDisplay> list = displays.remove(id);
        if (list != null) {
            for (BlockDisplay display : list) {
                if (display != null && !display.isDead()) display.remove();
            }
        }
    }

    public void clearAll() {
        Iterator<List<BlockDisplay>> iterator = displays.values().iterator();
        while (iterator.hasNext()) {
            for (BlockDisplay display : iterator.next()) {
                if (display != null && !display.isDead()) display.remove();
            }
            iterator.remove();
        }
    }
}
