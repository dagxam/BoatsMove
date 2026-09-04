package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/** Prevents physical world edits from corrupting an active ship's occupied area. */
public final class ShipProtectionListener implements Listener {
    private final ShipRegistry registry;

    public ShipProtectionListener(ShipRegistry registry) { this.registry = registry; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (contains(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (contains(event.getBlockPlaced().getLocation())) event.setCancelled(true);
    }

    private boolean contains(Location location) {
        if (location.getWorld() == null) return false;
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE || !ship.worldId().equals(location.getWorld().getUID())) continue;
            Location p = registry.position(ship);
            double yaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
            double dx = location.getX() + 0.5 - p.getX();
            double dz = location.getZ() + 0.5 - p.getZ();
            double localX = dx * Math.cos(yaw) + dz * Math.sin(yaw);
            double localZ = -dx * Math.sin(yaw) + dz * Math.cos(yaw);
            for (ShipBlock block : ship.blocks()) {
                if (Math.abs(localX - (block.x() + 0.5)) <= 0.51
                        && Math.abs(location.getY() + 0.5 - (p.getY() + block.y() + 0.5)) <= 0.51
                        && Math.abs(localZ - (block.z() + 0.5)) <= 0.51) return true;
            }
        }
        return false;
    }
}
