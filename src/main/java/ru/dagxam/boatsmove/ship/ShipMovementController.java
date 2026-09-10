package ru.dagxam.boatsmove.ship;

import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** Boat-like movement controller with rigid ship displays, collision and damaged systems. */
public final class ShipMovementController {
    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final ShipDisplayManager displays;
    private final ShipPassengerManager passengers;
    private final ShipCollisionManager collision;
    private final double maxSpeed, acceleration, reverseSpeed, turnSpeed, drag;
    private final boolean waterOnly;
    private ShipFloodingManager floodingManager;
    private ShipSystemsManager systemsManager;
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
        this.collision = new ShipCollisionManager();
        this.maxSpeed = Math.max(0.01, maxSpeed);
        this.acceleration = Math.max(0.001, acceleration);
        this.reverseSpeed = Math.max(0.01, reverseSpeed);
        this.turnSpeed = Math.max(0.01, turnSpeed);
        this.drag = Math.max(0.0, Math.min(0.999, drag));
        this.waterOnly = waterOnly;
    }

    public void floodingManager(ShipFloodingManager floodingManager) { this.floodingManager = floodingManager; }
    public void systemsManager(ShipSystemsManager systemsManager) { this.systemsManager = systemsManager; }

    public void start() {
        if (taskId != -1) return;
        taskId = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L).getTaskId();
    }

    public Location position(ShipModel ship) { return registry.position(ship); }

    public void remove(ShipModel ship) {
        passengers.clear(ship);
        if (systemsManager != null) systemsManager.forget(ship);
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

            Location beforeBuoyancy = runtime.position();
            WaterState water = sampleWater(ship, beforeBuoyancy);
            applyBuoyancy(ship, runtime, water);
            if (!collision.canMove(ship, beforeBuoyancy, runtime.position())) {
                runtime.position(beforeBuoyancy);
                runtime.verticalSpeed(0.0);
            }

            // The passenger is the control seat. Never use ownerId as the pilot
            // because the owner may already have left the seat.
            Player pilot = activePilot(ship);
            if (pilot == null) {
                boolean stillControlled = passengers.tick(ship);
                if (!stillControlled) {
                    // A Shift dismount or lost pilot immediately kills momentum.
                    runtime.speed(0.0);
                    runtime.verticalSpeed(0.0);
                    runtime.pitch(approach(runtime.pitch(), 0f, 0.35f));
                    runtime.roll(approach(runtime.roll(), 0f, 0.35f));
                } else {
                    applyDrag(runtime);
                }
                displays.updatePose(ship, runtime.position(), ship.yaw(), runtime.pitch(), runtime.roll());
                continue;
            }

            Input input = pilot.getCurrentInput();
            double speed = runtime.speed();
            ShipFloodingManager.BuoyancyState flood = floodingManager == null
                    ? new ShipFloodingManager.BuoyancyState(ship.flooding(), 0.0, 0.0)
                    : floodingManager.buoyancyState(ship);
            double floodedMass = clamp(Math.max(ship.flooding(), flood.floodedFraction()), 0.0, 1.0);
            double controlMultiplier = clamp(1.0 - floodedMass * 0.70, 0.30, 1.0);
            double steeringMultiplier = systemsManager == null ? 1.0 : systemsManager.steeringMultiplier(ship);
            double propulsionMultiplier = systemsManager == null ? 1.0 : systemsManager.propulsionMultiplier(ship);
            controlMultiplier *= steeringMultiplier;

            if (input.isLeft()) ship.yaw(ship.yaw() - (float) (turnSpeed * controlMultiplier));
            if (input.isRight()) ship.yaw(ship.yaw() + (float) (turnSpeed * controlMultiplier));

            double classSpeed = ship.shipClass().speedMultiplier();
            double floodSpeed = Math.max(0.18, 1.0 - floodedMass * 0.72);
            double terrainMultiplier = (water.shallow ? shallowSpeedMultiplier : 1.0) * classSpeed * floodSpeed;
            double forwardLimit = maxSpeed * terrainMultiplier * propulsionMultiplier;
            double reverseLimit = reverseSpeed * terrainMultiplier * propulsionMultiplier;
            if (propulsionMultiplier <= 0.001) {
                speed = 0.0;
            } else if (input.isForward()) {
                if (speed < 0.0) speed = Math.min(0.0, speed + acceleration * 1.75);
                speed = Math.min(forwardLimit, speed + acceleration * terrainMultiplier * propulsionMultiplier);
            } else if (input.isBackward()) {
                if (speed > 0.0) speed = Math.max(0.0, speed - acceleration * 1.75);
                speed = Math.max(-reverseLimit, speed - acceleration * terrainMultiplier * propulsionMultiplier);
            } else {
                speed *= drag;
                if (Math.abs(speed) < 0.001) speed = 0.0;
            }

            if (propulsionMultiplier < 0.999) speed *= 0.985 + propulsionMultiplier * 0.015;
            runtime.speed(speed);
            if (Math.abs(speed) >= 0.0001) {
                Vector direction = forwardDirection(ship.yaw());
                Location current = runtime.position();
                Location next = current.clone().add(direction.getX() * speed, 0, direction.getZ() * speed);
                if (collision.canMove(ship, current, next)) runtime.position(next);
                else runtime.speed(0.0);
            }

            displays.updatePose(ship, runtime.position(), ship.yaw(), runtime.pitch(), runtime.roll());
            // If Shift was pressed during this same tick, tick() dismounts and
            // the next tick is guaranteed to have zero momentum.
            passengers.tick(ship);
            if (!passengers.hasPassenger(ship)) {
                runtime.speed(0.0);
                runtime.verticalSpeed(0.0);
            }
        }
    }

    /** The current passenger is the only player allowed to drive the ship. */
    private Player activePilot(ShipModel ship) {
        java.util.UUID id = passengers.passengerId(ship);
        if (id == null) return null;
        Player pilot = plugin.getServer().getPlayer(id);
        if (pilot == null || !pilot.isOnline()) return null;
        if (!pilot.getWorld().getUID().equals(ship.worldId())) return null;
        return pilot;
    }

    private Vector forwardDirection(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0, Math.cos(radians));
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
        int minX = ship.blocks().stream().mapToInt(ShipBlock::x).min().orElse(0);
        int maxX = ship.blocks().stream().mapToInt(ShipBlock::x).max().orElse(0);
        int minZ = ship.blocks().stream().mapToInt(ShipBlock::z).min().orElse(0);
        int maxZ = ship.blocks().stream().mapToInt(ShipBlock::z).max().orElse(0);

        double bottom = pos.getY() + minY;
        double height = Math.max(1.0, maxY - minY + 1.0);
        double footprint = Math.max(1.0, (maxX - minX + 1.0) * (maxZ - minZ + 1.0));

        ShipFloodingManager.BuoyancyState flood = floodingManager == null
                ? new ShipFloodingManager.BuoyancyState(ship.flooding(), 0.0, 0.0)
                : floodingManager.buoyancyState(ship);
        double floodedMass = clamp(Math.max(ship.flooding(), flood.floodedFraction()), 0.0, 1.0);
        double dryMass = ship.blockCount() * 0.62;
        double estimatedInternalVolume = Math.max(1.0, ship.blockCount() * 0.30);
        double floodMass = estimatedInternalVolume * floodedMass * 0.95;
        double effectiveBuoyancy = Math.max(0.55, ship.shipClass().buoyancyMultiplier());
        double requiredDisplacement = (dryMass + floodMass) / (footprint * effectiveBuoyancy);
        double desiredDraft = clamp(requiredDisplacement, 0.16, height * 0.82);

        double immersion = clamp((water.averageSurface - bottom) / height, 0.0, 1.0);
        double targetBottom = water.averageSurface - desiredDraft;
        double critical = clamp((floodedMass - 0.72) / 0.28, 0.0, 1.0);
        double sinkDepth = height * (0.50 * critical * critical);
        targetBottom -= sinkDepth;

        double error = targetBottom - bottom;
        double vertical = runtime.verticalSpeed() + error * buoyancyStrength * effectiveBuoyancy;
        vertical *= verticalDamping;
        vertical = clamp(vertical, -maxVerticalStep, maxVerticalStep);
        if (Math.abs(error) < 0.02) vertical *= 0.45;
        if (critical > 0.0) vertical = Math.min(vertical, -0.010 - critical * 0.075);
        runtime.verticalSpeed(vertical);
        runtime.position(pos.clone().add(0, vertical, 0));

        double front = water.frontSurface - water.averageSurface;
        double rear = water.rearSurface - water.averageSurface;
        double left = water.leftSurface - water.averageSurface;
        double right = water.rightSurface - water.averageSurface;
        float targetPitch = (float) clamp((front - rear) * -4.5, -maxTilt, maxTilt);
        float targetRoll = (float) clamp((right - left) * 4.5, -maxTilt, maxTilt);
        targetPitch += (float) ((ship.floodRear() - ship.floodFront()) * 5.0);
        targetRoll += (float) ((ship.floodRight() - ship.floodLeft()) * 5.0);
        targetRoll += (float) (flood.lateralCenter() * floodedMass * 4.0);
        targetPitch += (float) (-flood.longitudinalCenter() * floodedMass * 4.0);
        targetPitch = (float) clamp(targetPitch, -maxTilt, maxTilt);
        targetRoll = (float) clamp(targetRoll, -maxTilt, maxTilt);
        runtime.pitch(approach(runtime.pitch(), targetPitch, 0.18f));
        runtime.roll(approach(runtime.roll(), targetRoll, 0.18f));
        if (immersion < 0.05 && floodedMass < 0.10) runtime.verticalSpeed(runtime.verticalSpeed() * 0.80);
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
        double[][] points = {{(minX + maxX) * 0.5, minZ}, {(minX + maxX) * 0.5, maxZ},
                {minX, (minZ + maxZ) * 0.5}, {maxX, (minZ + maxZ) * 0.5},
                {(minX + maxX) * 0.5, (minZ + maxZ) * 0.5}};
        double[] surfaces = new double[5]; boolean[] valid = new boolean[5]; int count = 0;
        for (int i = 0; i < points.length; i++) {
            double wx = position.getX() + points[i][0], wz = position.getZ() + points[i][1];
            int x = (int) Math.floor(wx), z = (int) Math.floor(wz);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            double surface = findSurface(world, x, z, (int) Math.floor(position.getY() + minY) - 2,
                    (int) Math.ceil(position.getY() + maxY) + 2);
            if (!Double.isNaN(surface)) { surfaces[i] = surface; valid[i] = true; count++; }
        }
        if (count == 0) return WaterState.empty();
        double average = 0;
        for (int i = 0; i < surfaces.length; i++) if (valid[i]) average += surfaces[i];
        average /= count;
        double front = valid[0] ? surfaces[0] : average, rear = valid[1] ? surfaces[1] : average;
        double left = valid[2] ? surfaces[2] : average, right = valid[3] ? surfaces[3] : average;
        return new WaterState(count, average, front, rear, left, right, isShallow(world, position, minX, maxX, minZ, maxZ));
    }

    private double findSurface(World world, int x, int z, int minY, int maxY) {
        for (int y = maxY; y >= minY; y--) if (world.getBlockAt(x, y, z).getType() == Material.WATER) return y + 1.0;
        return Double.NaN;
    }

    private boolean isShallow(World world, Location pos, int minX, int maxX, int minZ, int maxZ) {
        int y = (int) Math.floor(pos.getY());
        for (int x = minX; x <= maxX; x += Math.max(1, (maxX - minX) / 3))
            for (int z = minZ; z <= maxZ; z += Math.max(1, (maxZ - minZ) / 3))
                if (world.getBlockAt((int) Math.floor(pos.getX() + x), y - 2, (int) Math.floor(pos.getZ() + z)).getType().isSolid()) return true;
        return false;
    }

    private void applyDrag(ShipRuntimeState runtime) {
        runtime.speed(runtime.speed() * drag);
        if (Math.abs(runtime.speed()) < 0.001) runtime.speed(0.0);
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static float approach(float current, float target, float amount) {
        if (current < target) return Math.min(target, current + amount);
        return Math.max(target, current - amount);
    }

    private record WaterState(int samples, double averageSurface, double frontSurface, double rearSurface,
                              double leftSurface, double rightSurface, boolean shallow) {
        static WaterState empty() { return new WaterState(0, 0, 0, 0, 0, 0, false); }
    }
}
