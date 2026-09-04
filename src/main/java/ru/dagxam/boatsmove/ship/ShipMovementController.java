package ru.dagxam.boatsmove.ship;

import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** Boat-like W/S/A/D controller for active block ships. */
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
            if (Math.abs(speed) >= 0.0001) {
                Vector direction = new Vector(
                        -Math.sin(Math.toRadians(ship.yaw())),
                        0,
                        Math.cos(Math.toRadians(ship.yaw()))
                );

                double dx = direction.getX() * speed;
                double dz = direction.getZ() * speed;
                Location current = runtime.position();
                Location next = current.clone().add(dx, 0, dz);

                if (canMove(ship, next)) {
                    displays.translate(ship, dx, 0, dz);
                    runtime.position(next);
                } else {
                    runtime.speed(0.0);
                }
            }

            passengers.tick(ship);
        }
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
