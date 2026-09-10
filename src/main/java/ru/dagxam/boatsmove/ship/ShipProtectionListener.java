package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/** Protects the logical hull and gives the virtual ship a real player collision boundary. */
public final class ShipProtectionListener implements Listener {
    private static final double PLAYER_RADIUS = 0.30;
    private static final double PLAYER_HEIGHT = 1.80;
    private static final double EPSILON = 1.0E-5;
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

    /** Active ships use display entities, so vanilla has no solid blocks to collide with. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getWorld() == null || event.getTo().getWorld() == null) return;
        if (!event.getFrom().getWorld().getUID().equals(event.getTo().getWorld().getUID())) return;
        if (!wouldCollide(event.getTo())) return;

        Location safe = event.getFrom().clone();
        // Preserve the player's camera rotation even when horizontal movement is blocked.
        safe.setYaw(event.getTo().getYaw());
        safe.setPitch(event.getTo().getPitch());
        event.setTo(safe);
    }

    private boolean wouldCollide(Location location) {
        if (location.getWorld() == null) return false;
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE || !ship.worldId().equals(location.getWorld().getUID())) continue;
            ShipRuntimeState runtime = registry.runtime(ship.id());
            if (runtime != null && playerIntersectsHull(location, ship, runtime)) return true;
        }
        return false;
    }

    private boolean playerIntersectsHull(Location player, ShipModel ship, ShipRuntimeState runtime) {
        double yaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double pMinX = px - PLAYER_RADIUS, pMaxX = px + PLAYER_RADIUS;
        double pMinY = py, pMaxY = py + PLAYER_HEIGHT;
        double pMinZ = pz - PLAYER_RADIUS, pMaxZ = pz + PLAYER_RADIUS;

        for (ShipBlock block : ship.blocks()) {
            double centerX = runtime.position().getX()
                    + (block.x() + 0.5) * cos - (block.z() + 0.5) * sin;
            double centerZ = runtime.position().getZ()
                    + (block.x() + 0.5) * sin + (block.z() + 0.5) * cos;
            double halfExtent = (Math.abs(cos) + Math.abs(sin)) * 0.5;
            double hMinX = centerX - halfExtent, hMaxX = centerX + halfExtent;
            double hMinZ = centerZ - halfExtent, hMaxZ = centerZ + halfExtent;
            double hMinY = runtime.position().getY() + block.y();
            double hMaxY = hMinY + 1.0;

            if (pMaxX > hMinX + EPSILON && pMinX < hMaxX - EPSILON
                    && pMaxY > hMinY + EPSILON && pMinY < hMaxY - EPSILON
                    && pMaxZ > hMinZ + EPSILON && pMinZ < hMaxZ - EPSILON) return true;
        }
        return false;
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
