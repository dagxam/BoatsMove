package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

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
        if (ship.containsBlock(local.x(), local.y(), local.z())) return;
        if (!hasAdjacentHull(ship, local)) return;

        ShipBlock repaired = new ShipBlock(local.x(), local.y(), local.z(),
                placed.getBlockData().clone(), snapshot(placed));
        if (!ship.addBlock(repaired)) return;

        event.setCancelled(true);
        displays.addBlock(ship, repaired, registry.position(ship), ship.yaw(),
                registry.runtime(ship.id()) == null ? 0f : registry.runtime(ship.id()).pitch(),
                registry.runtime(ship.id()) == null ? 0f : registry.runtime(ship.id()).roll());
        player.sendActionBar("§aКорпус восстановлен §7(блоков: " + ship.blockCount() + ")");
    }

    private ShipModel nearestShip(Player player, Location target) {
        ShipModel result = null;
        double best = maxDistanceSquared;
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE || !ship.worldId().equals(target.getWorld().getUID())) continue;
            double distance = registry.position(ship).distanceSquared(target);
            if (distance < best) { best = distance; result = ship; }
        }
        return result;
    }

    private LocalPosition toLocal(ShipModel ship, Location world) {
        Location origin = registry.position(ship);
        double dx = world.getX() + 0.5 - origin.getX();
        double dz = world.getZ() + 0.5 - origin.getZ();
        double yaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        int x = (int) Math.round(dx * cos + dz * sin);
        int z = (int) Math.round(-dx * sin + dz * cos);
        int y = (int) Math.floor(world.getY() - origin.getY());
        return new LocalPosition(x, y, z);
    }

    private boolean hasAdjacentHull(ShipModel ship, LocalPosition p) {
        int adjacent = 0;
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] d : dirs) if (ship.containsBlock(p.x()+d[0], p.y()+d[1], p.z()+d[2])) adjacent++;
        return adjacent > 0;
    }

    private ShipBlockState snapshot(Block block) {
        var state = block.getState();
        if (state instanceof org.bukkit.inventory.InventoryHolder holder) {
            var inventory = holder.getInventory().getContents();
            return new ShipBlockState(state.getType().name(), state, inventory);
        }
        return new ShipBlockState(state.getType().name(), state, new org.bukkit.inventory.ItemStack[0]);
    }

    private record LocalPosition(int x, int y, int z) { }
}
