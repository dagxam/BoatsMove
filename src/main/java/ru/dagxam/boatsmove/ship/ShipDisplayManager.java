package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Vector3f;

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
                    entity.setTransformation(entity.getTransformation());
                    entity.getPersistentDataContainer().set(
                            new org.bukkit.NamespacedKey(plugin, "ship_id"),
                            org.bukkit.persistence.PersistentDataType.STRING,
                            ship.id().toString());
                });
                created.add(display);
            }
            displays.put(ship.id(), created);
        } catch (RuntimeException ex) {
            for (BlockDisplay display : created) display.remove();
            throw ex;
        }
    }

    /** Moves every visual block by a world-space delta. */
    public void translate(ShipModel ship, double dx, double dy, double dz) {
        List<BlockDisplay> list = displays.get(ship.id());
        if (list == null) return;
        for (BlockDisplay display : list) {
            if (!display.isValid()) continue;
            Location target = display.getLocation().add(dx, dy, dz);
            display.teleport(target);
        }
    }

    public void remove(UUID shipId) {
        List<BlockDisplay> list = displays.remove(shipId);
        if (list == null) return;
        for (BlockDisplay display : list) {
            if (display.isValid()) display.remove();
        }
    }

    public void removeAll() {
        for (UUID id : new ArrayList<>(displays.keySet())) remove(id);
    }

    public int displayCount(UUID shipId) {
        return displays.getOrDefault(shipId, List.of()).size();
    }
}
