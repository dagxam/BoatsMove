package ru.dagxam.boatsmove.ship;

import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** Boat-like movement controller with simple stable buoyancy for block ships. */
public final class ShipMovementController {
    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final ShipDisplayManager displays;
    private final ShipPassengerManager passengers;
    private final double maxSpeed;
    private final double acceleration;
    private final double reverseSpeed;
    private final double turnSpeed;
    private final double drag;
    private final boolean waterOnly;
    private final double buoyancyStrength = 0.16;
    private final double verticalDamping = 0.72;
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

    public Location position(ShipModel ship) {
        return registry.position(ship);
    }

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

            Player pilot = plugin.getServer().getPlayer(ship.ownerId());
            if (pilot == null || !pilot.isOnline() || !pilot.getWorld().getUID().equals(ship.worldId())) {
                applyDrag(runtime);
                applyBuoyancy(ship, runtime);
                displays.updatePose(ship, runtime.position(), ship.yaw());
                passengers.tick(ship);
                continue;
            }

            Input input = pilot.getCurrentInput();
            double speed = runtime.speed();

            if (input.isLeft()) ship.yaw(ship.yaw() - (float) turnSpeed);
            if (input.isRight()) ship.yaw(ship.yaw() + (float) turnSpeed);

            if (input.isForward()) {
                speed = Math.min(maxSpeed, speed + acceleration);
            } else if (input.isBackward()) {
                speed = Math.max(-reverseSpeed, speed - acceleration);
            } else {
                speed *= drag;
                if (Math.abs(speed) < 0.001) speed = 0.0;
            }

            runtime.speed(speed);

            Location current = runtime.position();
            Vector direction = new Vector(
                    -Math.sin(Math.toRadians(ship.yaw())),
                    0,
                    Math.cos(Math.toRadians(ship.yaw()))
            );
            double dx = direction.getX() * speed;
            double dz = direction.getZ() * speed;
            Location next = current.clone().add(dx, 0, dz);

            if (Math.abs(speed) >= 0.0001 && canMove(ship, next)) {
                runtime.position(next);
            } else if (Math.abs(speed) >= 0.0001) {
                runtime.speed(0.0);
            }

            applyBuoyancy(ship, runtime);
            displays.updatePose(ship, runtime.position(), ship.yaw());
            passengers.tick(ship);
        }
    }

    /**
     * Keeps the ship at the local water surface instead of simply translating
     * horizontally through water. The target is derived from the lowest solid
     * part of the ship and nearby water blocks, making larger ships float too.
     */
    private void applyBuoyancy(ShipModel ship, ShipRuntimeState runtime) {
        Location position = runtime.position();
        World world = position.getWorld();
        if (world == null) return;

        double waterSurface = findWaterSurface(ship, position, world);
        if (Double.isNaN(waterSurface)) return;

        double bottom = lowestBlockCenter(ship, position);
        double error = waterSurface - bottom;

        // Small proportional correction with damping. This avoids the violent
        // vertical oscillation that a full physics simulation would introduce
        // before collision and mass systems are implemented.
        double verticalStep = Math.max(-0.12, Math.min(0.12, error * buoyancyStrength));
        if (Math.abs(verticalStep) > 0.0005) {
            runtime.position(position.clone().add(0, verticalStep, 0));
        }
    }

    private double lowestBlockCenter(ShipModel ship, Location position) {
        int minY = Integer.MAX_VALUE;
        for (ShipBlock block : ship.blocks()) minY = Math.min(minY, block.y());
        return position.getY() + minY + 0.5;
    }

    private double findWaterSurface(ShipModel ship, Location position, World world) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (ShipBlock block : ship.blocks()) {
            minX = Math.min(minX, block.x());
            maxX = Math.max(maxX, block.x());
            minZ = Math.min(minZ, block.z());
            maxZ = Math.max(maxZ, block.z());
            minY = Math.min(minY, block.y());
            maxY = Math.max(maxY, block.y());
        }

        double sum = 0;
        int samples = 0;
        int sampleStep = Math.max(1, (int) Math.sqrt(Math.max(1, ship.blockCount()) / 16.0));

        for (int lx = minX; lx <= maxX; lx += sampleStep) {
            for (int lz = minZ; lz <= maxZ; lz += sampleStep) {
                double wx = position.getX() + lx;
                double wz = position.getZ() + lz;
                int bx = (int) Math.floor(wx);
                int bz = (int) Math.floor(wz);

                for (int y = (int) Math.floor(position.getY() + minY) - 1;
                     y <= (int) Math.ceil(position.getY() + maxY) + 2; y++) {
                    Material type = world.getBlockAt(bx, y, bz).getType();
                    if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
                        sum += y + 1.0;
                        samples++;
                        break;
                    }
                }
            }
        }

        return samples == 0 ? Double.NaN : sum / samples;
    }

    private void applyDrag(ShipRuntimeState runtime) {
        double speed = runtime.speed() * drag;
        if (Math.abs(speed) < 0.001) speed = 0.0;
        runtime.speed(speed);
    }

    private boolean canMove(ShipModel ship, Location next) {
        World world = next.getWorld();
        if (world == null) return false;

        boolean hasWater = false;
        for (ShipBlock block : ship.blocks()) {
            int x = next.getBlockX() + block.x();
            int y = next.getBlockY() + block.y();
            int z = next.getBlockZ() + block.z();
            Material type = world.getBlockAt(x, y, z).getType();
            if (type == Material.WATER || type == Material.BUBBLE_COLUMN) hasWater = true;
            if (!type.isAir() && type != Material.WATER && type != Material.BUBBLE_COLUMN) return false;
        }
        return !waterOnly || hasWater;
    }
}
