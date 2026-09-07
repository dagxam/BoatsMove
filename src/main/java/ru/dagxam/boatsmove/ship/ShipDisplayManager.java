package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Renders an active ship as its original blocks using display entities. */
public final class ShipDisplayManager {
    private final JavaPlugin plugin;
    private final Map<UUID, Map<BlockKey, BlockDisplay>> displays = new HashMap<>();
    private final int interpolationTicks;

    public ShipDisplayManager(JavaPlugin plugin, int interpolationTicks) {
        this.plugin = plugin;
        this.interpolationTicks = Math.max(0, interpolationTicks);
    }

    public void spawn(ShipModel ship) {
        remove(ship.id());
        World world = plugin.getServer().getWorld(ship.worldId());
        if (world == null) throw new IllegalStateException("Мир корабля не найден.");
        Location origin = ship.origin();
        Map<BlockKey, BlockDisplay> created = new HashMap<>();
        try {
            for (ShipBlock block : ship.blocks()) {
                Location location = origin.clone().add(block.x(), block.y(), block.z());
                BlockDisplay display = world.spawn(location, BlockDisplay.class, entity -> {
                    entity.setBlock(block.blockData().clone());
                    entity.setInterpolationDelay(0);
                    entity.setInterpolationDuration(interpolationTicks);
                    entity.setTeleportDuration(interpolationTicks);
                    entity.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                    entity.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "ship_id"),
                            org.bukkit.persistence.PersistentDataType.STRING, ship.id().toString());
                });
                created.put(new BlockKey(block.x(), block.y(), block.z()), display);
            }
            displays.put(ship.id(), created);
        } catch (RuntimeException ex) {
            for (BlockDisplay display : created.values()) display.remove();
            throw ex;
        }
    }

    public void updatePose(ShipModel ship, Location position, float yaw) {
        updatePose(ship, position, yaw, 0f, 0f);
    }

    /** Updates every display to the exact rigid-body pose, including buoyancy tilt. */
    public void updatePose(ShipModel ship, Location position, float yaw, float pitch, float roll) {
        Map<BlockKey, BlockDisplay> map = displays.get(ship.id());
        if (map == null || position == null) return;
        double relativeYaw = Math.toRadians(yaw - ship.origin().getYaw());
        double sin = Math.sin(relativeYaw), cos = Math.cos(relativeYaw);
        double p = Math.toRadians(pitch), r = Math.toRadians(roll);
        for (ShipBlock block : ship.blocks()) {
            BlockDisplay display = map.get(new BlockKey(block.x(), block.y(), block.z()));
            if (display == null || !display.isValid()) continue;

            double x = block.x(), y = block.y(), z = block.z();
            double yawX = x * cos - z * sin;
            double yawZ = x * sin + z * cos;
            double pitchY = y * Math.cos(p) - yawZ * Math.sin(p);
            double pitchZ = y * Math.sin(p) + yawZ * Math.cos(p);
            double rollX = yawX * Math.cos(r) - pitchY * Math.sin(r);
            double rollY = yawX * Math.sin(r) + pitchY * Math.cos(r);

            display.teleport(position.clone().add(rollX, rollY, pitchZ));
            Transformation current = display.getTransformation();
            Quaternionf rotation = new Quaternionf().rotateY((float) relativeYaw)
                    .rotateX((float) p).rotateZ((float) r);
            display.setTransformation(new Transformation(current.getTranslation(), rotation,
                    current.getScale(), current.getRightRotation()));
        }
    }

    public void translate(ShipModel ship, double dx, double dy, double dz) {
        Map<BlockKey, BlockDisplay> map = displays.get(ship.id());
        if (map == null) return;
        for (BlockDisplay display : map.values()) if (display.isValid()) display.teleport(display.getLocation().add(dx, dy, dz));
    }

    /** Removes the display belonging to one exact local ship block. */
    public boolean removeBlock(UUID shipId, int x, int y, int z) {
        Map<BlockKey, BlockDisplay> map = displays.get(shipId);
        if (map == null) return false;
        BlockDisplay display = map.remove(new BlockKey(x, y, z));
        if (display == null) return false;
        if (display.isValid()) display.remove();
        return true;
    }

    public void remove(UUID shipId) {
        Map<BlockKey, BlockDisplay> map = displays.remove(shipId);
        if (map == null) return;
        for (BlockDisplay display : map.values()) if (display.isValid()) display.remove();
    }

    public void removeAll() {
        for (UUID id : new ArrayList<>(displays.keySet())) remove(id);
    }

    public int displayCount(UUID shipId) { return displays.getOrDefault(shipId, Map.of()).size(); }

    private record BlockKey(int x, int y, int z) { }
}
