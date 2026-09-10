package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Renders an active ship as one rigid visual structure using display entities. */
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

    /** Applies one identical rigid rotation to every display. */
    public void updatePose(ShipModel ship, Location position, float yaw, float pitch, float roll) {
        Map<BlockKey, BlockDisplay> map = displays.get(ship.id());
        if (map == null || position == null) return;
        float yawRad = (float) Math.toRadians(yaw - ship.origin().getYaw());
        float pitchRad = (float) Math.toRadians(pitch);
        float rollRad = (float) Math.toRadians(roll);
        Quaternionf rotation = new Quaternionf().rotateY(yawRad).rotateX(pitchRad).rotateZ(rollRad);
        double cy = Math.cos(yawRad), sy = Math.sin(yawRad);
        double cp = Math.cos(pitchRad), sp = Math.sin(pitchRad);
        double cr = Math.cos(rollRad), sr = Math.sin(rollRad);

        for (ShipBlock block : ship.blocks()) {
            BlockDisplay display = map.get(new BlockKey(block.x(), block.y(), block.z()));
            if (display == null || !display.isValid()) continue;
            double x = block.x() + 0.5, y = block.y() + 0.5, z = block.z() + 0.5;
            double yawX = x * cy - z * sy;
            double yawZ = x * sy + z * cy;
            double pitchY = y * cp - yawZ * sp;
            double pitchZ = y * sp + yawZ * cp;
            double worldX = yawX * cr - pitchY * sr;
            double worldY = yawX * sr + pitchY * cr;
            display.teleport(position.clone().add(worldX - 0.5, worldY - 0.5, pitchZ - 0.5));
            Transformation current = display.getTransformation();
            display.setTransformation(new Transformation(current.getTranslation(), new Quaternionf(rotation), current.getScale(), current.getRightRotation()));
        }
    }

    public void translate(ShipModel ship, double dx, double dy, double dz) {
        Map<BlockKey, BlockDisplay> map = displays.get(ship.id());
        if (map == null) return;
        for (BlockDisplay display : map.values()) if (display.isValid()) display.teleport(display.getLocation().add(dx, dy, dz));
    }

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
