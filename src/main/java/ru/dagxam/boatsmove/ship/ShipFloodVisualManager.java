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

/** Renders compartment-local water volumes without changing real world blocks. */
public final class ShipFloodVisualManager {
    private static final int MAX_WATER_DISPLAYS = 160;
    private final JavaPlugin plugin;
    private final ShipRegistry registry;
    private final ShipFloodingManager flooding;
    private final Map<UUID, List<BlockDisplay>> displays = new HashMap<>();

    public ShipFloodVisualManager(JavaPlugin plugin, ShipRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.flooding = null;
    }

    public ShipFloodVisualManager(JavaPlugin plugin, ShipRegistry registry, ShipFloodingManager flooding) {
        this.plugin = plugin;
        this.registry = registry;
        this.flooding = flooding;
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
        if (flooding == null) return;
        List<ShipFloodingManager.CompartmentWater> compartments = flooding.compartmentWater(ship);
        if (compartments.isEmpty()) {
            clear(ship.id());
            return;
        }

        Location base = registry.position(ship);
        World world = base.getWorld();
        if (world == null) return;
        List<BlockDisplay> current = displays.computeIfAbsent(ship.id(), ignored -> new ArrayList<>());

        int desired = 0;
        for (ShipFloodingManager.CompartmentWater compartment : compartments) {
            desired += Math.max(1, Math.min(48, (int) Math.ceil(Math.sqrt(compartment.volume()) * compartment.level())));
        }
        desired = Math.min(MAX_WATER_DISPLAYS, desired);
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
        while (current.size() > desired) current.remove(current.size() - 1).remove();

        int index = 0;
        for (ShipFloodingManager.CompartmentWater compartment : compartments) {
            int count = Math.max(1, Math.min(48, (int) Math.ceil(Math.sqrt(compartment.volume()) * compartment.level())));
            double level = Math.max(0.01, Math.min(1.0, compartment.level()));
            for (int local = 0; local < count && index < current.size(); local++, index++) {
                BlockDisplay display = current.get(index);
                double angle = (local * 2.3999632297) % (Math.PI * 2.0);
                double radius = Math.max(0.15, Math.min(2.4, Math.sqrt(compartment.volume()) * 0.22));
                double x = compartment.seedX() + 0.5 + Math.cos(angle) * radius * (0.35 + 0.65 * ((local % 7) / 6.0));
                double z = compartment.seedZ() + 0.5 + Math.sin(angle) * radius * (0.35 + 0.65 * ((local % 7) / 6.0));
                double y = compartment.bottomY() + level * Math.max(1, compartment.topY() - compartment.bottomY() + 1) - 0.05;
                Location target = transform(base, ship, x, y, z);
                display.teleport(target);
                float width = (float) Math.max(0.28, Math.min(1.15, radius * 0.65));
                float height = (float) Math.max(0.035, Math.min(0.30, 0.06 + level * 0.18));
                display.setTransformation(new Transformation(
                        new Vector3f(-0.5f, -0.02f, -0.5f),
                        new AxisAngle4f(),
                        new Vector3f(width, height, width),
                        new AxisAngle4f()));
            }
        }
        while (index < current.size()) current.get(index++).remove();
    }

    private Location transform(Location base, ShipModel ship, double x, double y, double z) {
        double radians = Math.toRadians(ship.yaw() - ship.origin().getYaw());
        double cos = Math.cos(radians), sin = Math.sin(radians);
        double worldX = x * cos - z * sin;
        double worldZ = x * sin + z * cos;
        Location result = base.clone().add(worldX, y, worldZ);
        result.setYaw(ship.yaw());
        return result;
    }

    public void clear(UUID shipId) {
        List<BlockDisplay> list = displays.remove(shipId);
        if (list == null) return;
        for (BlockDisplay display : list) if (display != null && !display.isDead()) display.remove();
    }

    public void clearAll() {
        Iterator<List<BlockDisplay>> iterator = displays.values().iterator();
        while (iterator.hasNext()) {
            for (BlockDisplay display : iterator.next()) if (display != null && !display.isDead()) display.remove();
            iterator.remove();
        }
    }
}
