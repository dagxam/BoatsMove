package ru.dagxam.boatsmove.ship;

import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** Boat-like movement controller with multi-point buoyancy and water attitude. */
public final class ShipMovementController {
    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final ShipDisplayManager displays;
    private final ShipPassengerManager passengers;
    private final double maxSpeed, acceleration, reverseSpeed, turnSpeed, drag;
    private final boolean waterOnly;
    private final double buoyancyStrength = 0.12;
    private final double verticalDamping = 0.70;
    private final double maxVerticalStep = 0.10;
    private final double maxTilt = 7.0;
    private final double shallowSpeedMultiplier = 0.55;
    private int taskId = -1;

    public ShipMovementController(JavaPlugin plugin, ShipRegistry registry, ShipDisplayManager displays,
                                   ShipPassengerManager passengers, double maxSpeed, double acceleration,
                                   double reverseSpeed, double turnSpeed, double drag, boolean waterOnly) {
        this.plugin = plugin;
        this.registry = registry;
        this.displays = displays;
        this.passengers = passengers;
        this.maxSpeed = Math.max(0.01, maxSpeed);
        this.acceleration = Math.max(0.001, acceleration);
        this.reverseSpeed = Math.max(0.01, reverseSpeed);
        this.turnSpeed = Math.max(0.01, turnSpeed);
        this.drag = Math.max(0.0, Math.min(0.999, drag));
        this.waterOnly = waterOnly;
    }

    public void start() {
        if (taskId != -1) return;
        taskId = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L).getTaskId();
    }

    public Location position(ShipModel ship) { return registry.position(ship); }

    public void remove(ShipModel ship) {
        passengers.clear(ship);
        registry.removeRuntime(ship.id());
    }

    public void stop() {
        if (taskId != -1) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void tick() {
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE) continue;
            ShipRuntimeState runtime = registry.runtime(ship.id());
            if (runtime == null) continue;

            WaterState water = sampleWater(ship, runtime.position());
            applyBuoyancy(ship, runtime, water);

            Player pilot = plugin.getServer().getPlayer(ship.ownerId());
            if (pilot == null || !pilot.isOnline() || !pilot.getWorld().getUID().equals(ship.worldId())) {
                applyDrag(runtime);
                displays.updatePose(ship, runtime.position(), ship.yaw(), runtime.pitch(), runtime.roll());
                passengers.tick(ship);
                continue;
            }

            Input input = pilot.getCurrentInput();
            double speed = runtime.speed();
            if (input.isLeft()) ship.yaw(ship.yaw() - (float) turnSpeed);
            if (input.isRight()) ship.yaw(ship.yaw() + (float) turnSpeed);

            double terrainMultiplier = water.shallow ? shallowSpeedMultiplier : 1.0;
            double forwardLimit = maxSpeed * terrainMultiplier;
            double reverseLimit = reverseSpeed * terrainMultiplier;
            if (input.isForward()) speed = Math.min(forwardLimit, speed + acceleration * terrainMultiplier);
            else if (input.isBackward()) speed = Math.max(-reverseLimit, speed - acceleration * terrainMultiplier);
            else {
                speed *= drag;
                if (Math.abs(speed) < 0.001) speed = 0.0;
            }

            runtime.speed(speed);
            if (Math.abs(speed) >= 0.0001) {
                Vector direction = new Vector(-Math.sin(Math.toRadians(ship.yaw())), 0,
                        Math.cos(Math.toRadians(ship.yaw())));
                Location next = runtime.position().clone().add(direction.getX() * speed, 0, direction.getZ() * speed);
                if (canMove(ship, next, water)) runtime.position(next);
                else runtime.speed(0.0);
            }

            displays.updatePose(ship, runtime.position(), ship.yaw(), runtime.pitch(), runtime.roll());
            passengers.tick(ship);
        }
    }

    private void applyBuoyancy(ShipModel ship, ShipRuntimeState runtime, WaterState water) {
        if (water.samples == 0) {
            runtime.verticalSpeed(runtime.verticalSpeed() * 0.80 - 0.035);
            runtime.position(runtime.position().clone().add(0, Math.max(-0.08, runtime.verticalSpeed()), 0));
            runtime.pitch(approach(runtime.pitch(), 0f, 0.25f));
            runtime.roll(approach(runtime.roll(), 0f, 0.25f));
            return;
        }

        Location pos = runtime.position();
        int minY = ship.blocks().stream().mapToInt(ShipBlock::y).min().orElse(0);
        int maxY = ship.blocks().stream().mapToInt(ShipBlock::y).max().orElse(0);
        double bottom = pos.getY() + minY;
        double height = Math.max(1.0, maxY - minY + 1.0);
        double immersion = clamp((water.averageSurface - bottom) / height, 0.0, 1.0);

        // Larger hulls displace more water and settle slightly deeper.
        double sizeFactor = clamp(Math.cbrt(ship.blockCount() / 16.0), 0.75, 1.75);
        double desiredImmersion = clamp(0.34 + 0.10 * (sizeFactor - 0.75), 0.30, 0.50);
        double targetBottom = water.averageSurface - desiredImmersion * height;
        double error = targetBottom - bottom;
        double vertical = runtime.verticalSpeed() + error * buoyancyStrength;
        vertical *= verticalDamping;
        vertical = clamp(vertical, -maxVerticalStep, maxVerticalStep);
        if (Math.abs(error) < 0.02) vertical *= 0.45;
        runtime.verticalSpeed(vertical);
        runtime.position(pos.clone().add(0, vertical, 0));

        // Five-point water sampling creates a natural pitch/roll response when
        // bow, stern, port and starboard see different water levels.
        double front = water.frontSurface - water.averageSurface;
        double rear = water.rearSurface - water.averageSurface;
        double left = water.leftSurface - water.averageSurface;
        double right = water.rightSurface - water.averageSurface;
        float targetPitch = (float) clamp((front - rear) * -4.5, -maxTilt, maxTilt);
        float targetRoll = (float) clamp((right - left) * 4.5, -maxTilt, maxTilt);
        runtime.pitch(approach(runtime.pitch(), targetPitch, 0.18f));
        runtime.roll(approach(runtime.roll(), targetRoll, 0.18f));
    }

    private WaterState sampleWater(ShipModel ship, Location position) {
        World world = position.getWorld();
        if (world == null) return WaterState.empty();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (ShipBlock b : ship.blocks()) {
            minX = Math.min(minX, b.x()); maxX = Math.max(maxX, b.x());
            minZ = Math.min(minZ, b.z()); maxZ = Math.max(maxZ, b.z());
            minY = Math.min(minY, b.y()); maxY = Math.max(maxY, b.y());
        }

        double[][] points = {
                {(minX + maxX) * 0.5, minZ},
                {(minX + maxX) * 0.5, maxZ},
                {minX, (minZ + maxZ) * 0.5},
                {maxX, (minZ + maxZ) * 0.5},
                {(minX + maxX) * 0.5, (minZ + maxZ) * 0.5}
        };
        double[] surfaces = new double[5];
        int count = 0;
        for (int i = 0; i < points.length; i++) {
            double wx = position.getX() + points[i][0];
            double wz = position.getZ() + points[i][1];
            int x = (int) Math.floor(wx), z = (int) Math.floor(wz);
            double surface = findSurface(world, x, z, (int) Math.floor(position.getY() + minY) - 2,
                    (int) Math.ceil(position.getY() + maxY) + 2);
            if (!Double.isNaN(surface)) { surfaces[i] = surface; count++; }
        }
        if (count == 0) return WaterState.empty();
        double avg = 0;
        for (int i = 0; i < surfaces.length; i++) if (surfaces[i] != 0) avg += surfaces[i];
        avg /= count;
        boolean shallow = false;
        double bottom = position.getY() + minY;
        int depthSamples = 0;
        for (int i = 0; i < surfaces.length; i++) {
            if (surfaces[i] == 0) continue;
            double depth = surfaces[i] - bottom;
            if (depth < 1.15) shallow = true;
            depthSamples++;
        }
        return new WaterState(count, avg, surfaces[0], surfaces[1], surfaces[2], surfaces[3], shallow);
    }

    private double findSurface(World world, int x, int z, int fromY, int toY) {
        for (int y = toY; y >= fromY; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type == Material.WATER || type == Material.BUBBLE_COLUMN) return y + 1.0;
        }
        return Double.NaN;
    }

    private boolean canMove(ShipModel ship, Location next, WaterState water) {
        World world = next.getWorld();
        if (world == null) return false;
        boolean hasWater = false;
        int minY = ship.blocks().stream().mapToInt(ShipBlock::y).min().orElse(0);
        for (ShipBlock block : ship.blocks()) {
            int x = next.getBlockX() + block.x(), y = next.getBlockY() + block.y(), z = next.getBlockZ() + block.z();
            Material type = world.getBlockAt(x, y, z).getType();
            if (type == Material.WATER || type == Material.BUBBLE_COLUMN) { hasWater = true; continue; }
            if (type.isAir()) continue;
            return false;
        }
        if (waterOnly && !hasWater) return false;

        // Do not allow the lower hull to pass through seabed/shore blocks.
        int bottomY = next.getBlockY() + minY;
        for (ShipBlock block : ship.blocks()) {
            if (block.y() != minY) continue;
            Material below = world.getBlockAt(next.getBlockX() + block.x(), bottomY - 1,
                    next.getBlockZ() + block.z()).getType();
            if (!below.isAir() && below != Material.WATER && below != Material.BUBBLE_COLUMN) return false;
        }
        return true;
    }

    private void applyDrag(ShipRuntimeState runtime) {
        double speed = runtime.speed() * drag;
        if (Math.abs(speed) < 0.001) speed = 0.0;
        runtime.speed(speed);
    }

    private float approach(float current, float target, float factor) { return current + (target - current) * factor; }
    private double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    private record WaterState(int samples, double averageSurface, double frontSurface, double rearSurface,
                              double leftSurface, double rightSurface, boolean shallow) {
        static WaterState empty() { return new WaterState(0, 0, 0, 0, 0, 0, false); }
    }
}
