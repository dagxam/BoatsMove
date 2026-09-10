package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/** Converts player block placement into logical repair blocks while a ship is active. */
public final class ShipRepairListener implements Listener {
    private final ShipRegistry registry;
    private final ShipDisplayManager displays;
    private final double maxDistanceSquared;

    public ShipRepairListener(ShipRegistry registry, ShipDisplayManager displays, double maxDistance) {
        this.registry = registry;
        this.displays = displays;
        this.maxDistanceSquared = Math.max(4.0, maxDistance * maxDistance);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block placed = event.getBlockPlaced();
        ShipModel ship = nearestShip(player, placed.getLocation());
        if (ship == null) return;
        LocalPosition local = toLocal(ship, placed.getLocation());
        if (ship.containsBlock(local.x(), local.y(), local.z()) || !hasAdjacentHull(ship, local)) return;

        ShipBlock repaired = new ShipBlock(local.x(), local.y(), local.z(), placed.getBlockData().clone(), snapshot(placed));
        if (!ship.addBlock(repaired)) return;
        event.setCancelled(true);

        ShipRuntimeState runtime = registry.runtime(ship.id());
        displays.spawn(ship);
        if (runtime != null) displays.updatePose(ship, runtime.position(), ship.yaw(), runtime.pitch(), runtime.roll());
        player.sendActionBar("§aКорпус восстановлен §7(блоков: " + ship.blockCount() + ")");
    }

    private ShipModel nearestShip(Player player, Location target) {
        ShipModel result = null; double best = maxDistanceSquared;
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE || !ship.worldId().equals(target.getWorld().getUID())) continue;
            double distance = registry.position(ship).distanceSquared(target);
            if (distance < best) { best = distance; result = ship; }
        }
        return result;
    }

    private LocalPosition toLocal(ShipModel ship, Location world) {
        Location origin = registry.position(ship);
        double dx = world.getBlockX() - origin.getX();
        double dz = world.getBlockZ() - origin.getZ();
        double yaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        int x = (int) Math.round(dx * cos + dz * sin);
        int z = (int) Math.round(-dx * sin + dz * cos);
        int y = (int) Math.round(world.getBlockY() - origin.getY());
        return new LocalPosition(x, y, z);
    }

    private boolean hasAdjacentHull(ShipModel ship, LocalPosition p) {
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] d : dirs) if (ship.containsBlock(p.x()+d[0], p.y()+d[1], p.z()+d[2])) return true;
        return false;
    }

    private ShipBlockState snapshot(Block block) {
        var state = block.getState();
        if (state instanceof org.bukkit.inventory.InventoryHolder holder) return new ShipBlockState(state.getType().name(), state, holder.getInventory().getContents());
        return new ShipBlockState(state.getType().name(), state, new org.bukkit.inventory.ItemStack[0]);
    }

    private record LocalPosition(int x, int y, int z) { }
}
