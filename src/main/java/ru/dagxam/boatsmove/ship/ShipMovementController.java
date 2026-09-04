package ru.dagxam.boatsmove.ship;

import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Boat-like W/S/A/D controller for active block ships. */
public final class ShipMovementController {
    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final ShipDisplayManager displays;
    private final double maxSpeed;
    private final double acceleration;
    private final double reverseSpeed;
    private final double turnSpeed;
    private final double drag;
    private final boolean waterOnly;
    private final Map<UUID, Double> speeds = new HashMap<>();
    private final Map<UUID, Location> positions = new HashMap<>();

    public ShipMovementController(JavaPlugin plugin, ShipRegistry registry, ShipDisplayManager displays,
                                   double maxSpeed, double acceleration, double reverseSpeed,
                                   double turnSpeed, double drag, boolean waterOnly) {
        this.plugin = plugin;
        this.registry = registry;
        this.displays = displays;
        this.maxSpeed = Math.max(0.01, maxSpeed);
        this.acceleration = Math.max(0.001, acceleration);
        this.reverseSpeed = Math.max(0.01, reverseSpeed);
        this.turnSpeed = Math.max(0.01, turnSpeed);
        this.drag = Math.max(0.0, Math.min(0.999, drag));
        this.waterOnly = waterOnly;
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public Location position(ShipModel ship) {
        return positions.computeIfAbsent(ship.id(), ignored -> ship.origin());
    }

    public void remove(ShipModel ship) {
        positions.remove(ship.id());
        speeds.remove(ship.id());
    }

    public void stop() {
        positions.clear();
        speeds.clear();
    }

    private void tick() {
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE) continue;

            Player pilot = plugin.getServer().getPlayer(ship.ownerId());
            if (pilot == null || !pilot.isOnline() || !pilot.getWorld().getUID().equals(ship.worldId())) {
                applyDrag(ship);
                continue;
            }

            Input input = pilot.getCurrentInput();
            double speed = speeds.getOrDefault(ship.id(), 0.0);

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

            if (Math.abs(speed) < 0.0001) {
                speeds.put(ship.id(), 0.0);
                continue;
            }

            Vector direction = new Vector(
                    -Math.sin(Math.toRadians(ship.yaw())),
                    0,
                    Math.cos(Math.toRadians(ship.yaw()))
            );

            double dx = direction.getX() * speed;
            double dz = direction.getZ() * speed;
            Location current = position(ship);
            Location next = current.clone().add(dx, 0, dz);

            if (!canMove(ship, next)) {
                speeds.put(ship.id(), 0.0);
                continue;
            }

            displays.translate(ship, dx, 0, dz);
            positions.put(ship.id(), next);
            speeds.put(ship.id(), speed);
        }
    }

    private void applyDrag(ShipModel ship) {
        double speed = speeds.getOrDefault(ship.id(), 0.0) * drag;
        if (Math.abs(speed) < 0.001) speed = 0.0;
        speeds.put(ship.id(), speed);
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
