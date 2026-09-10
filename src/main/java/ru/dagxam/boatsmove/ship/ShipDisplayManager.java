package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Renders an active ship as a rigid visual structure using display entities. */
public final class ShipDisplayManager {
    private final JavaPlugin plugin;
    private final Map<UUID, Map<BlockKey, BlockDisplay>> displays = new HashMap<>();

    public ShipDisplayManager(JavaPlugin plugin, int interpolationTicks) {
        this.plugin = plugin;
        // Translation interpolation is safe because every block receives the same
        // translation delta. Rotation itself stays snapped so neighbouring blocks
        // cannot follow different interpolated arcs and visually separate.
    }

    public void spawn(ShipModel ship) {
        remove(ship.id());
        World world = plugin.getServer().getWorld(ship.worldId());
        if (world == null) throw new IllegalStateException("Мир корабля не найден.");
        Location origin = ship.origin();
        Map<BlockKey, BlockDisplay> created = new HashMap<>();
        try {
            for (ShipBlock block : ship.blocks()) {
                Location center = origin.clone().add(block.x() + 0.5, block.y() + 0.5, block.z() + 0.5);
                BlockDisplay display = world.spawn(center, BlockDisplay.class, entity -> {
                    entity.setBlock(block.blockData().clone());
                    entity.setInterpolationDelay(0);
                    entity.setInterpolationDuration(0);
                    entity.setTeleportDuration(1);
                    entity.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                    entity.setTransformation(new Transformation(
                            new Vector3f(-0.5f, -0.5f, -0.5f),
                            new Quaternionf(),
                            new Vector3f(1f, 1f, 1f),
                            new Quaternionf()));
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

    /** Applies exactly the same rigid rotation to every block and smooths only translation. */
    public void updatePose(ShipModel ship, Location position, float yaw, float pitch, float roll) {
        Map<BlockKey, BlockDisplay> map = displays.get(ship.id());
        if (map == null || position == null) return;

        float yawRad = (float) Math.toRadians(yaw - ship.origin().getYaw());
        float pitchRad = (float) Math.toRadians(pitch);
        float rollRad = (float) Math.toRadians(roll);
        Quaternionf rotation = new Quaternionf()
                .rotateY(yawRad)
                .rotateX(pitchRad)
                .rotateZ(rollRad);

        for (ShipBlock block : ship.blocks()) {
            BlockDisplay display = map.get(new BlockKey(block.x(), block.y(), block.z()));
            if (display == null || !display.isValid()) continue;

            Vector3f center = new Vector3f(block.x() + 0.5f, block.y() + 0.5f, block.z() + 0.5f);
            rotation.transform(center);
            display.teleport(position.clone().add(center.x(), center.y(), center.z()));
            display.setTransformation(new Transformation(
                    new Vector3f(-0.5f, -0.5f, -0.5f),
                    new Quaternionf(rotation),
                    new Vector3f(1f, 1f, 1f),
                    new Quaternionf()));
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(0);
            display.setTeleportDuration(1);
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
