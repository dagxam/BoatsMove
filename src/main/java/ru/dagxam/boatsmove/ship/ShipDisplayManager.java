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
    private final Map<UUID, List<BlockDisplay>> displays = new HashMap<>();
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
        List<BlockDisplay> created = new ArrayList<>(ship.blockCount());
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
                created.add(display);
            }
            displays.put(ship.id(), created);
        } catch (RuntimeException ex) {
            for (BlockDisplay display : created) display.remove();
            throw ex;
        }
    }

    public void updatePose(ShipModel ship, Location position, float yaw) {
        updatePose(ship, position, yaw, 0f, 0f);
    }

    /** Updates every display to the exact rigid-body pose, including buoyancy tilt. */
    public void updatePose(ShipModel ship, Location position, float yaw, float pitch, float roll) {
        List<BlockDisplay> list = displays.get(ship.id());
        if (list == null || position == null) return;
        double relativeYaw = Math.toRadians(yaw - ship.origin().getYaw());
        double sin = Math.sin(relativeYaw), cos = Math.cos(relativeYaw);
        double p = Math.toRadians(pitch), r = Math.toRadians(roll);
        List<ShipBlock> blocks = ship.blocks();
        int count = Math.min(list.size(), blocks.size());
        for (int i = 0; i < count; i++) {
            BlockDisplay display = list.get(i);
            ShipBlock block = blocks.get(i);
            if (!display.isValid()) continue;

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
        List<BlockDisplay> list = displays.get(ship.id());
        if (list == null) return;
        for (BlockDisplay display : list) if (display.isValid()) display.teleport(display.getLocation().add(dx, dy, dz));
    }

    public void remove(UUID shipId) {
        List<BlockDisplay> list = displays.remove(shipId);
        if (list == null) return;
        for (BlockDisplay display : list) if (display.isValid()) display.remove();
    }

    public void removeAll() {
        for (UUID id : new ArrayList<>(displays.keySet())) remove(id);
    }

    public int displayCount(UUID shipId) { return displays.getOrDefault(shipId, List.of()).size(); }
}
