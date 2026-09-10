package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Keeps the pilot attached to a logical ship without a vanilla Boat entity. */
public final class ShipPassengerManager {
    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final Map<UUID, UUID> passengers = new HashMap<>();
    private final Map<UUID, Location> seatOffsets = new HashMap<>();

    public ShipPassengerManager(JavaPlugin plugin, ShipRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public boolean board(ShipModel ship, Player player) {
        if (ship == null || player == null || ship.state() != ShipState.ACTIVE) return false;
        if (!player.getWorld().getUID().equals(ship.worldId())) return false;
        if (passengers.containsKey(ship.id())) return false;
        passengers.put(ship.id(), player.getUniqueId());
        seatOffsets.put(ship.id(), new Location(null, 0.5, 1.15, 0.5));
        movePassenger(ship);
        return true;
    }

    public void dismount(ShipModel ship) {
        if (ship == null) return;
        UUID playerId = passengers.remove(ship.id());
        seatOffsets.remove(ship.id());
        if (playerId == null) return;
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) return;
        Location shipPosition = registry.position(ship);
        double yaw = Math.toRadians(ship.yaw());
        Location exit = shipPosition.clone().add(-1.5 * Math.sin(yaw), 1.0, 1.5 * Math.cos(yaw));
        player.teleport(exit);
    }

    public boolean hasPassenger(ShipModel ship) {
        return ship != null && passengers.containsKey(ship.id());
    }

    public UUID passengerId(ShipModel ship) {
        return ship == null ? null : passengers.get(ship.id());
    }

    /** Keeps the pilot seated while leaving camera/head rotation completely free. */
    public boolean tick(ShipModel ship) {
        if (ship == null || !hasPassenger(ship)) return false;
        UUID playerId = passengers.get(ship.id());
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            clear(ship);
            return false;
        }
        if (player.getCurrentInput().isSneak()) {
            dismount(ship);
            return false;
        }
        if (!player.getWorld().getUID().equals(ship.worldId())) {
            dismountSilently(ship);
            return false;
        }
        movePassenger(ship);
        return true;
    }

    public void clear(ShipModel ship) {
        if (ship == null) return;
        passengers.remove(ship.id());
        seatOffsets.remove(ship.id());
    }

    public void clearAll() {
        passengers.clear();
        seatOffsets.clear();
    }

    private void movePassenger(ShipModel ship) {
        UUID playerId = passengers.get(ship.id());
        if (playerId == null) return;
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) return;
        Location shipPosition = registry.position(ship);
        Location offset = seatOffsets.getOrDefault(ship.id(), new Location(null, 0.5, 1.15, 0.5));
        double relativeYaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double worldX = offset.getX() * Math.cos(relativeYaw) - offset.getZ() * Math.sin(relativeYaw);
        double worldZ = offset.getX() * Math.sin(relativeYaw) + offset.getZ() * Math.cos(relativeYaw);
        Location seat = shipPosition.clone().add(worldX, offset.getY(), worldZ);
        // Do not set yaw/pitch: the player's camera/head must remain under player control.
        seat.setYaw(player.getYaw());
        seat.setPitch(player.getPitch());
        player.teleport(seat);
    }

    private void dismountSilently(ShipModel ship) {
        passengers.remove(ship.id());
        seatOffsets.remove(ship.id());
    }
}
