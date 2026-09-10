package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Keeps the pilot attached to an invisible seat without forcing the player's camera rotation. */
public final class ShipPassengerManager implements Listener {
    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final Map<UUID, UUID> passengers = new HashMap<>();
    private final Map<UUID, ArmorStand> seats = new HashMap<>();

    public ShipPassengerManager(JavaPlugin plugin, ShipRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public boolean board(ShipModel ship, Player player) {
        if (ship == null || player == null || ship.state() != ShipState.ACTIVE) return false;
        if (!player.getWorld().getUID().equals(ship.worldId())) return false;
        if (passengers.containsKey(ship.id())) return false;

        Location seatLocation = seatLocation(ship);
        ArmorStand seat = spawnSeat(seatLocation);
        if (!seat.addPassenger(player)) {
            seat.remove();
            return false;
        }
        passengers.put(ship.id(), player.getUniqueId());
        seats.put(ship.id(), seat);
        return true;
    }

    public void dismount(ShipModel ship) {
        if (ship == null) return;
        UUID playerId = passengers.remove(ship.id());
        ArmorStand seat = seats.remove(ship.id());
        if (seat != null && seat.isValid()) seat.remove();
        if (playerId == null) return;

        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) return;
        Location shipPosition = registry.position(ship);
        double yaw = Math.toRadians(ship.yaw());
        Location exit = shipPosition.clone().add(-1.5 * Math.sin(yaw), 1.0, 1.5 * Math.cos(yaw));
        exit.setYaw(player.getYaw());
        exit.setPitch(player.getPitch());
        player.teleport(exit);
    }

    public boolean hasPassenger(ShipModel ship) {
        return ship != null && passengers.containsKey(ship.id());
    }

    public UUID passengerId(ShipModel ship) {
        return ship == null ? null : passengers.get(ship.id());
    }

    /** Keeps the seat attached to the ship; the player camera is never teleported. */
    public boolean tick(ShipModel ship) {
        if (ship == null || !hasPassenger(ship)) return false;
        UUID playerId = passengers.get(ship.id());
        Player player = plugin.getServer().getPlayer(playerId);
        ArmorStand seat = seats.get(ship.id());

        if (player == null || !player.isOnline()) {
            clear(ship);
            return false;
        }
        if (!player.getWorld().getUID().equals(ship.worldId())) {
            dismountSilently(ship);
            return false;
        }
        if (player.isSneaking()) {
            dismount(ship);
            return false;
        }
        if (seat == null || !seat.isValid()) {
            clear(ship);
            return false;
        }
        if (!seat.getPassengers().contains(player)) {
            // Minecraft may already have processed the vehicle exit. Treat it
            // as a real dismount instead of leaving a stale pilot behind.
            removeSeat(ship);
            return false;
        }

        Location target = seatLocation(ship);
        seat.teleport(target);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleExit(VehicleExitEvent event) {
        if (!(event.getVehicle() instanceof ArmorStand seat)) return;
        UUID shipId = null;
        for (Map.Entry<UUID, ArmorStand> entry : seats.entrySet()) {
            if (entry.getValue() == seat) {
                shipId = entry.getKey();
                break;
            }
        }
        if (shipId == null) return;

        passengers.remove(shipId);
        seats.remove(shipId);
        if (seat.isValid()) seat.remove();

        ShipModel ship = registry.get(shipId);
        if (ship != null) {
            ShipRuntimeState runtime = registry.runtime(ship.id());
            if (runtime != null) {
                runtime.speed(0.0);
                runtime.verticalSpeed(0.0);
            }
        }
    }

    public void clear(ShipModel ship) {
        if (ship == null) return;
        passengers.remove(ship.id());
        removeSeat(ship);
    }

    public void clearAll() {
        for (ArmorStand seat : seats.values()) if (seat != null && seat.isValid()) seat.remove();
        passengers.clear();
        seats.clear();
    }

    private ArmorStand spawnSeat(Location location) {
        return location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setCollidable(false);
            stand.setSilent(true);
            stand.setBasePlate(false);
            stand.setArms(false);
        });
    }

    private Location seatLocation(ShipModel ship) {
        Location shipPosition = registry.position(ship);
        double relativeYaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double offsetX = 0.5;
        double offsetY = 1.15;
        double offsetZ = 0.5;
        double worldX = offsetX * Math.cos(relativeYaw) - offsetZ * Math.sin(relativeYaw);
        double worldZ = offsetX * Math.sin(relativeYaw) + offsetZ * Math.cos(relativeYaw);
        return shipPosition.clone().add(worldX, offsetY, worldZ);
    }

    private void removeSeat(ShipModel ship) {
        ArmorStand seat = seats.remove(ship.id());
        if (seat != null && seat.isValid()) seat.remove();
    }

    private void dismountSilently(ShipModel ship) {
        passengers.remove(ship.id());
        removeSeat(ship);
    }
}
