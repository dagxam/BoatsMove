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

/** Keeps the pilot attached to a fixed local point on the ship without forcing camera rotation. */
public final class ShipPassengerManager implements Listener {
    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final Map<UUID, UUID> passengers = new HashMap<>();
    private final Map<UUID, ArmorStand> seats = new HashMap<>();
    private final Map<UUID, SeatOffset> offsets = new HashMap<>();
    private final Map<UUID, Float> lastPilotYaw = new HashMap<>();

    public ShipPassengerManager(JavaPlugin plugin, ShipRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public boolean board(ShipModel ship, Player player) {
        if (ship == null || player == null || ship.state() != ShipState.ACTIVE) return false;
        if (!player.getWorld().getUID().equals(ship.worldId())) return false;
        if (passengers.containsKey(ship.id())) return false;

        SeatOffset offset = captureOffset(ship, player.getLocation());
        Location seatLocation = seatLocation(ship, offset);
        ArmorStand seat = spawnSeat(seatLocation);
        if (!seat.addPassenger(player)) {
            seat.remove();
            return false;
        }
        passengers.put(ship.id(), player.getUniqueId());
        seats.put(ship.id(), seat);
        offsets.put(ship.id(), offset);
        lastPilotYaw.put(ship.id(), player.getYaw());
        return true;
    }

    /** Returns the mouse yaw delta since the previous movement tick. */
    public float consumeMouseYawDelta(ShipModel ship, Player player) {
        if (ship == null || player == null || !hasPassenger(ship)) return 0.0f;
        float current = player.getYaw();
        Float previous = lastPilotYaw.put(ship.id(), current);
        if (previous == null) return 0.0f;
        float delta = normalizeDelta(current - previous);
        return clamp(delta, -10.0f, 10.0f);
    }

    /**
     * Releases the pilot when the ship is being materialized back into blocks.
     * The player's current position is intentionally preserved: it is the exact
     * local boarding point, so teleporting to the ship center could put the player
     * inside the restored hull and leave them stuck.
     */
    public void releaseForDeactivation(ShipModel ship) {
        if (ship == null) return;
        UUID playerId = passengers.remove(ship.id());
        ArmorStand seat = seats.remove(ship.id());
        offsets.remove(ship.id());
        lastPilotYaw.remove(ship.id());

        Player player = playerId == null ? null : plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline() && player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        if (seat != null && seat.isValid()) seat.remove();
    }

    /** Legacy/manual dismount: moves the pilot outside the moving ship. */
    public void dismount(ShipModel ship) {
        if (ship == null) return;
        UUID playerId = passengers.remove(ship.id());
        ArmorStand seat = seats.remove(ship.id());
        offsets.remove(ship.id());
        lastPilotYaw.remove(ship.id());
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

    /** Keeps the seat at the exact local point where the pilot boarded. */
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
            removeSeat(ship);
            return false;
        }

        float mouseDelta = consumeMouseYawDelta(ship, player);
        if (Math.abs(mouseDelta) > 0.001f) {
            ship.yaw(ship.yaw() + mouseDelta);
        }

        seat.teleport(seatLocation(ship, offsets.getOrDefault(ship.id(), new SeatOffset(0.5, 1.15, 0.5))));
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
        offsets.remove(shipId);
        lastPilotYaw.remove(shipId);
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
        offsets.remove(ship.id());
        lastPilotYaw.remove(ship.id());
        removeSeat(ship);
    }

    public void clearAll() {
        for (ArmorStand seat : seats.values()) if (seat != null && seat.isValid()) seat.remove();
        passengers.clear();
        seats.clear();
        offsets.clear();
        lastPilotYaw.clear();
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

    private SeatOffset captureOffset(ShipModel ship, Location playerLocation) {
        Location p = registry.position(ship);
        double relativeYaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double dx = playerLocation.getX() - p.getX();
        double dz = playerLocation.getZ() - p.getZ();
        double localX = dx * Math.cos(relativeYaw) + dz * Math.sin(relativeYaw);
        double localZ = -dx * Math.sin(relativeYaw) + dz * Math.cos(relativeYaw);
        double localY = playerLocation.getY() - p.getY();
        return new SeatOffset(clamp(localX, -32.0, 32.0), clamp(localY, -8.0, 8.0), clamp(localZ, -32.0, 32.0));
    }

    private Location seatLocation(ShipModel ship, SeatOffset offset) {
        Location shipPosition = registry.position(ship);
        double relativeYaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double worldX = offset.x() * Math.cos(relativeYaw) - offset.z() * Math.sin(relativeYaw);
        double worldZ = offset.x() * Math.sin(relativeYaw) + offset.z() * Math.cos(relativeYaw);
        return shipPosition.clone().add(worldX, offset.y(), worldZ);
    }

    private void removeSeat(ShipModel ship) {
        ArmorStand seat = seats.remove(ship.id());
        if (seat != null && seat.isValid()) seat.remove();
    }

    private void dismountSilently(ShipModel ship) {
        passengers.remove(ship.id());
        offsets.remove(ship.id());
        lastPilotYaw.remove(ship.id());
        removeSeat(ship);
    }

    private static float normalizeDelta(float delta) {
        while (delta > 180.0f) delta -= 360.0f;
        while (delta < -180.0f) delta += 360.0f;
        return delta;
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    private record SeatOffset(double x, double y, double z) { }
}
