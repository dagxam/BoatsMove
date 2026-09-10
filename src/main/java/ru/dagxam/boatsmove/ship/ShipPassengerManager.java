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

/** Keeps the pilot anchored to the exact hull block where they boarded. */
public final class ShipPassengerManager implements Listener {
    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final Map<UUID, UUID> passengers = new HashMap<>();
    private final Map<UUID, ArmorStand> seats = new HashMap<>();
    private final Map<UUID, SeatAnchor> anchors = new HashMap<>();

    public ShipPassengerManager(JavaPlugin plugin, ShipRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public boolean board(ShipModel ship, Player player) {
        if (ship == null || player == null || ship.state() != ShipState.ACTIVE) return false;
        if (!player.getWorld().getUID().equals(ship.worldId())) return false;
        if (passengers.containsKey(ship.id())) return false;

        SeatAnchor anchor = captureAnchor(ship, player.getLocation());
        ArmorStand seat = spawnSeat(seatLocation(ship, anchor));
        if (!seat.addPassenger(player)) {
            seat.remove();
            return false;
        }
        passengers.put(ship.id(), player.getUniqueId());
        seats.put(ship.id(), seat);
        anchors.put(ship.id(), anchor);
        return true;
    }

    /** Releases the pilot without teleporting them into the restored hull. */
    public void releaseForDeactivation(ShipModel ship) {
        if (ship == null) return;
        UUID playerId = passengers.remove(ship.id());
        ArmorStand seat = seats.remove(ship.id());
        anchors.remove(ship.id());
        Player player = playerId == null ? null : plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline() && player.isInsideVehicle()) player.leaveVehicle();
        if (seat != null && seat.isValid()) seat.remove();
    }

    /** Manual dismount moves the pilot just outside the ship. */
    public void dismount(ShipModel ship) {
        if (ship == null) return;
        UUID playerId = passengers.remove(ship.id());
        ArmorStand seat = seats.remove(ship.id());
        anchors.remove(ship.id());
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

    public boolean hasPassenger(ShipModel ship) { return ship != null && passengers.containsKey(ship.id()); }
    public UUID passengerId(ShipModel ship) { return ship == null ? null : passengers.get(ship.id()); }

    /** Keeps the invisible seat on the same logical hull block; the player controls only their view. */
    public boolean tick(ShipModel ship) {
        if (ship == null || !hasPassenger(ship)) return false;
        UUID playerId = passengers.get(ship.id());
        Player player = plugin.getServer().getPlayer(playerId);
        ArmorStand seat = seats.get(ship.id());
        SeatAnchor anchor = anchors.get(ship.id());
        if (player == null || !player.isOnline()) { clear(ship); return false; }
        if (!player.getWorld().getUID().equals(ship.worldId())) { dismountSilently(ship); return false; }
        if (player.isSneaking()) { dismount(ship); return false; }
        if (seat == null || !seat.isValid() || anchor == null) { clear(ship); return false; }
        if (!seat.getPassengers().contains(player)) { removeSeat(ship); return false; }
        seat.teleport(seatLocation(ship, anchor));
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleExit(VehicleExitEvent event) {
        if (!(event.getVehicle() instanceof ArmorStand seat)) return;
        UUID shipId = null;
        for (Map.Entry<UUID, ArmorStand> entry : seats.entrySet()) {
            if (entry.getValue() == seat) { shipId = entry.getKey(); break; }
        }
        if (shipId == null) return;
        passengers.remove(shipId);
        seats.remove(shipId);
        anchors.remove(shipId);
        if (seat.isValid()) seat.remove();
        ShipModel ship = registry.get(shipId);
        if (ship != null) {
            ShipRuntimeState runtime = registry.runtime(ship.id());
            if (runtime != null) { runtime.speed(0.0); runtime.verticalSpeed(0.0); }
        }
    }

    public void clear(ShipModel ship) {
        if (ship == null) return;
        passengers.remove(ship.id());
        anchors.remove(ship.id());
        removeSeat(ship);
    }

    public void clearAll() {
        for (ArmorStand seat : seats.values()) if (seat != null && seat.isValid()) seat.remove();
        passengers.clear(); seats.clear(); anchors.clear();
    }

    private ArmorStand spawnSeat(Location location) {
        return location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setInvisible(true); stand.setMarker(true); stand.setGravity(false);
            stand.setInvulnerable(true); stand.setCollidable(false); stand.setSilent(true);
            stand.setBasePlate(false); stand.setArms(false);
        });
    }

    private SeatAnchor captureAnchor(ShipModel ship, Location playerLocation) {
        Location origin = registry.position(ship);
        double yaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double dx = playerLocation.getX() - origin.getX();
        double dz = playerLocation.getZ() - origin.getZ();
        double localX = dx * Math.cos(yaw) + dz * Math.sin(yaw);
        double localZ = -dx * Math.sin(yaw) + dz * Math.cos(yaw);
        double localY = playerLocation.getY() - origin.getY();

        ShipBlock best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ShipBlock block : ship.blocks()) {
            double bx = block.x() + 0.5, bz = block.z() + 0.5;
            double horizontal = Math.pow(localX - bx, 2) + Math.pow(localZ - bz, 2);
            double vertical = Math.abs(localY - (block.y() + 1.0));
            double score = horizontal + vertical * vertical * 0.5;
            if (score < bestDistance) { bestDistance = score; best = block; }
        }
        if (best == null) return new SeatAnchor(localX, localY, localZ);

        double anchoredX = best.x() + 0.5 + clamp(localX - (best.x() + 0.5), -0.49, 0.49);
        double anchoredZ = best.z() + 0.5 + clamp(localZ - (best.z() + 0.5), -0.49, 0.49);
        double anchoredY = best.y() + 1.0 + clamp(localY - (best.y() + 1.0), -0.10, 0.10);
        return new SeatAnchor(anchoredX, anchoredY, anchoredZ);
    }

    private Location seatLocation(ShipModel ship, SeatAnchor anchor) {
        Location origin = registry.position(ship);
        double yaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double worldX = anchor.x() * Math.cos(yaw) - anchor.z() * Math.sin(yaw);
        double worldZ = anchor.x() * Math.sin(yaw) + anchor.z() * Math.cos(yaw);
        Location result = origin.clone().add(worldX, anchor.y(), worldZ);
        result.setYaw(0.0f); result.setPitch(0.0f);
        return result;
    }

    private void removeSeat(ShipModel ship) {
        ArmorStand seat = seats.remove(ship.id());
        if (seat != null && seat.isValid()) seat.remove();
    }

    private void dismountSilently(ShipModel ship) {
        passengers.remove(ship.id()); anchors.remove(ship.id()); removeSeat(ship);
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private record SeatAnchor(double x, double y, double z) { }
}
