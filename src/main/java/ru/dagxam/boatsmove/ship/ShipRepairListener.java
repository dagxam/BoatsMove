package ru.dagxam.boatsmove.ship;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Converts player block placement into logical repair blocks while a ship is active. */
public final class ShipRepairListener implements Listener {
    private final ShipRegistry registry;
    private final ShipDisplayManager displays;
    private final double maxBlockDistanceSquared;

    public ShipRepairListener(ShipRegistry registry, ShipDisplayManager displays, double maxBlockDistance) {
        this.registry = registry;
        this.displays = displays;
        this.maxBlockDistanceSquared = Math.max(4.0, maxBlockDistance * maxBlockDistance);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block placed = event.getBlockPlaced();
        ShipModel ship = nearestShip(placed.getLocation());
        if (ship == null) return;

        LocalPosition local = toLocal(ship, placed.getLocation());
        if (ship.containsBlock(local.x(), local.y(), local.z()) || !hasAdjacentHull(ship, local)) return;

        ShipBlock repaired = new ShipBlock(local.x(), local.y(), local.z(), placed.getBlockData().clone(), snapshot(placed));
        if (!ship.addBlock(repaired)) return;

        // The real world placement is cancelled because active ships are virtual.
        // Consume exactly the item used for the repair, except in Creative mode.
        event.setCancelled(true);
        consumePlacedItem(player, event.getHand());

        ShipRuntimeState runtime = registry.runtime(ship.id());
        displays.spawn(ship);
        if (runtime != null) displays.updatePose(ship, runtime.position(), ship.yaw(), runtime.pitch(), runtime.roll());
        player.sendActionBar("§aКорпус восстановлен §7(блоков: " + ship.blockCount() + ")");
    }

    private ShipModel nearestShip(Location target) {
        ShipModel result = null;
        double best = maxBlockDistanceSquared;
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE || !ship.worldId().equals(target.getWorld().getUID())) continue;
            ShipRuntimeState runtime = registry.runtime(ship.id());
            if (runtime == null) continue;
            for (ShipBlock block : ship.blocks()) {
                Location center = transformLocal(ship, runtime, block.x() + 0.5, block.y() + 0.5, block.z() + 0.5);
                double d = target.distanceSquared(center);
                if (d < best) { best = d; result = ship; }
            }
        }
        return result;
    }

    private LocalPosition toLocal(ShipModel ship, Location world) {
        ShipRuntimeState runtime = registry.runtime(ship.id());
        if (runtime == null) return new LocalPosition(0, 0, 0);

        Location origin = runtime.position();
        double wx = world.getBlockX() + 0.5 - origin.getX();
        double wy = world.getBlockY() + 0.5 - origin.getY();
        double wz = world.getBlockZ() + 0.5 - origin.getZ();

        // Inverse of display transform: undo roll, then pitch, then yaw.
        double yaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double pitch = Math.toRadians(runtime.pitch());
        double roll = Math.toRadians(runtime.roll());

        double rollX = wx * Math.cos(roll) + wy * Math.sin(roll);
        double rollY = -wx * Math.sin(roll) + wy * Math.cos(roll);
        double rollZ = wz;

        double pitchY = rollY * Math.cos(pitch) + rollZ * Math.sin(pitch);
        double pitchZ = -rollY * Math.sin(pitch) + rollZ * Math.cos(pitch);
        double pitchX = rollX;

        double localXCenter = pitchX * Math.cos(yaw) + pitchZ * Math.sin(yaw);
        double localZCenter = -pitchX * Math.sin(yaw) + pitchZ * Math.cos(yaw);
        double localYCenter = pitchY;

        int x = (int) Math.floor(localXCenter);
        int y = (int) Math.floor(localYCenter);
        int z = (int) Math.floor(localZCenter);
        return new LocalPosition(x, y, z);
    }

    private Location transformLocal(ShipModel ship, ShipRuntimeState runtime, double x, double y, double z) {
        double yaw = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double pitch = Math.toRadians(runtime.pitch());
        double roll = Math.toRadians(runtime.roll());

        double yawX = x * Math.cos(yaw) - z * Math.sin(yaw);
        double yawZ = x * Math.sin(yaw) + z * Math.cos(yaw);
        double pitchY = y * Math.cos(pitch) - yawZ * Math.sin(pitch);
        double pitchZ = y * Math.sin(pitch) + yawZ * Math.cos(pitch);
        double rollX = yawX * Math.cos(roll) - pitchY * Math.sin(roll);
        double rollY = yawX * Math.sin(roll) + pitchY * Math.cos(roll);
        return runtime.position().clone().add(rollX, rollY, pitchZ);
    }

    private boolean hasAdjacentHull(ShipModel ship, LocalPosition p) {
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] d : dirs) if (ship.containsBlock(p.x()+d[0], p.y()+d[1], p.z()+d[2])) return true;
        return false;
    }

    private void consumePlacedItem(Player player, EquipmentSlot hand) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack stack = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (stack.getType().isAir()) return;
        if (stack.getAmount() <= 1) stack.setAmount(0);
        else stack.setAmount(stack.getAmount() - 1);
    }

    private ShipBlockState snapshot(Block block) {
        var state = block.getState();
        if (state instanceof org.bukkit.inventory.InventoryHolder holder) {
            return new ShipBlockState(state.getType().name(), state, holder.getInventory().getContents());
        }
        return new ShipBlockState(state.getType().name(), state, new org.bukkit.inventory.ItemStack[0]);
    }

    private record LocalPosition(int x, int y, int z) { }
}
