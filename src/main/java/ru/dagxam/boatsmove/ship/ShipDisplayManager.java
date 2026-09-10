package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
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
                // The entity is placed at the block centre. The -0.5 translation
                // moves the rendered block back so its rotation pivot is exactly
                // its centre instead of its lower corner.
                Location center = origin.clone().add(block.x() + 0.5, block.y() + 0.5, block.z() + 0.5);
                BlockDisplay display = world.spawn(center, BlockDisplay.class, entity -> {
                    entity.setBlock(block.blockData().clone());
                    entity.setInterpolationDelay(0);
                    entity.setInterpolationDuration(interpolationTicks);
                    entity.setTeleportDuration(interpolationTicks);
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

    /** Rotates every block around its own centre while moving every centre by the same ship transform. */
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

            // The ship transform is applied to the centre of every block.
            // Because every display also has a -0.5 local translation, its
            // geometry rotates around that centre and cannot swing around a corner.
            Vector3f localCenter = new Vector3f(block.x() + 0.5f, block.y() + 0.5f, block.z() + 0.5f);
            rotation.transform(localCenter);
            Location center = position.clone().add(localCenter.x(), localCenter.y(), localCenter.z());
            display.teleport(center);

            Transformation current = display.getTransformation();
            display.setTransformation(new Transformation(
                    current.getTranslation(),
                    new Quaternionf(rotation),
                    current.getScale(),
                    current.getRightRotation()));
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
