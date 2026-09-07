package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lightweight visual water layer for flooded ship compartments. */
public final class ShipFloodVisualManager {
    private static final int MAX_WATER_DISPLAYS = 160;
    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final Map<UUID, List<BlockDisplay>> displays = new HashMap<>();

    public ShipFloodVisualManager(JavaPlugin plugin, ShipRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public void tick() {
        for (ShipModel ship : registry.all()) {
            if (ship.state() != ShipState.ACTIVE || ship.blockCount() == 0) {
                clear(ship.id());
                continue;
            }
            update(ship);
        }
    }

    private void update(ShipModel ship) {
        double level = ship.flooding();
        if (level < 0.035) {
            clear(ship.id());
            return;
        }

        Location base = registry.position(ship);
        World world = base.getWorld();
        if (world == null) return;

        List<ShipBlock> blocks = ship.blocks();
        int minY = blocks.stream().mapToInt(ShipBlock::y).min().orElse(0);
        int maxY = blocks.stream().mapToInt(ShipBlock::y).max().orElse(0);
        float yaw = ship.yaw();
        List<BlockDisplay> current = displays.computeIfAbsent(ship.id(), ignored -> new ArrayList<>());

        int desired = Math.min(MAX_WATER_DISPLAYS, Math.max(1, (int) Math.ceil(level * 24.0)));
        while (current.size() < desired) {
            BlockDisplay display = world.spawn(base, BlockDisplay.class, entity -> {
                entity.setBlock(Material.WATER.createBlockData());
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setInterpolationDuration(2);
                entity.setTeleportDuration(2);
                entity.setPersistent(false);
                entity.setViewRange(64.0f);
            });
            current.add(display);
        }
        while (current.size() > desired) {
            current.remove(current.size() - 1).remove();
        }

        int span = Math.max(1, (int) Math.ceil(Math.sqrt(Math.max(1, blocks.size()))));
        for (int i = 0; i < current.size(); i++) {
            BlockDisplay display = current.get(i);
            double angle = (i * 2.3999632297) % (Math.PI * 2.0);
            double radius = Math.min(0.42 * span, 0.7 + Math.sqrt(i + 1) * 0.32);
            double localX = Math.cos(angle) * radius;
            double localZ = Math.sin(angle) * radius;
            double localY = minY + Math.max(0.15, (maxY - minY + 1) * Math.min(1.0, level) * 0.55);
            Location target = base.clone().add(localX, localY, localZ);
            target.setYaw(yaw);
            display.teleport(target);
            float scale = (float) Math.max(0.35, Math.min(1.8, 0.55 + level));
            display.setTransformation(new Transformation(
                    new Vector3f(-0.5f, -0.08f, -0.5f),
                    new AxisAngle4f(),
                    new Vector3f(scale, 0.08f + (float) level * 0.18f, scale),
                    new AxisAngle4f()));
        }
    }

    public void clear(UUID shipId) {
        List<BlockDisplay> list = displays.remove(shipId);
        if (list == null) return;
        for (BlockDisplay display : list) {
            if (display != null && !display.isDead()) display.remove();
        }
    }

    public void clearAll() {
        Iterator<List<BlockDisplay>> iterator = displays.values().iterator();
        while (iterator.hasNext()) {
            List<BlockDisplay> list = iterator.next();
            for (BlockDisplay display : list) {
                if (display != null && !display.isDead()) display.remove();
            }
            iterator.remove();
        }
    }
}
