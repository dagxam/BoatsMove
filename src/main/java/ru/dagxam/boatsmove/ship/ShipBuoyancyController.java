package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight multi-point buoyancy model for block-built ships.
 * It estimates immersion from the hull geometry and keeps the ship near the
 * local water surface without turning every block into an independent physics body.
 */
public final class ShipBuoyancyController {
    private final double gravity;
    private final double buoyancy;
    private final double verticalDamping;
    private final double waterlineOffset;
    private final double maxVerticalSpeed;
    private final boolean waterOnly;

    public ShipBuoyancyController(double gravity, double buoyancy, double verticalDamping,
                                  double waterlineOffset, double maxVerticalSpeed, boolean waterOnly) {
        this.gravity = Math.max(0.001, gravity);
        this.buoyancy = Math.max(0.001, buoyancy);
        this.verticalDamping = Math.max(0.0, Math.min(0.999, verticalDamping));
        this.waterlineOffset = waterlineOffset;
        this.maxVerticalSpeed = Math.max(0.01, maxVerticalSpeed);
        this.waterOnly = waterOnly;
    }

    public void tick(ShipModel ship, ShipRuntimeState runtime) {
        Location position = runtime.position();
        World world = position.getWorld();
        if (world == null) return;

        HullStats hull = hullStats(ship);
        if (hull.count == 0) return;

        WaterSample sample = sampleWater(world, position, ship, hull);
        if (sample.waterPoints == 0) {
            double velocity = runtime.verticalSpeed() - gravity;
            runtime.verticalSpeed(clamp(velocity * (1.0 - verticalDamping), -maxVerticalSpeed, maxVerticalSpeed));
            runtime.position(position.clone().add(0, runtime.verticalSpeed(), 0));
            return;
        }

        double waterLevel = sample.averageWaterY;
        double hullBottom = position.getY() + hull.minY;
        double hullTop = position.getY() + hull.maxY + 1.0;
        double height = Math.max(1.0, hullTop - hullBottom);

        // Desired immersion is deliberately deeper for larger/heavier hulls,
        // while still remaining bounded. This makes a tiny craft sit higher
        // and a large hull draw more water without requiring mass per block.
        double sizeFactor = clamp(Math.cbrt(hull.count / 8.0), 0.65, 1.55);
        double desiredImmersion = clamp(0.42 * sizeFactor, 0.28, 0.68);
        double targetBottom = waterLevel - waterlineOffset - height * desiredImmersion;
        double error = targetBottom - hullBottom;

        // Buoyancy is proportional to immersion and gently pulls toward the
        // equilibrium waterline. Partial submersion therefore produces a
        // stronger restoring force than a hull that is almost completely out.
        double immersion = clamp((waterLevel - hullBottom) / height, 0.0, 1.0);
        double force = gravity + (error * buoyancy * (0.35 + immersion));
        double velocity = (runtime.verticalSpeed() + force) * (1.0 - verticalDamping);
        velocity = clamp(velocity, -maxVerticalSpeed, maxVerticalSpeed);

        if (Math.abs(error) < 0.015) velocity *= 0.55;

        runtime.verticalSpeed(velocity);
        runtime.position(position.clone().add(0, velocity, 0));

        if (waterOnly && sample.waterPoints == 0) runtime.verticalSpeed(-gravity);
    }

    private WaterSample sampleWater(World world, Location position, ShipModel ship, HullStats hull) {
        List<double[]> points = samplePoints(hull);
        double sum = 0.0;
        int waterPoints = 0;

        for (double[] point : points) {
            int x = (int) Math.floor(position.getX() + point[0]);
            int z = (int) Math.floor(position.getZ() + point[1]);
            int baseY = (int) Math.floor(position.getY() + hull.minY);

            for (int y = baseY; y <= (int) Math.ceil(position.getY() + hull.maxY + 1.0); y++) {
                Material type = world.getBlockAt(x, y, z).getType();
                if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
                    sum += y + 1.0;
                    waterPoints++;
                    break;
                }
            }
        }

        return new WaterSample(waterPoints, waterPoints == 0 ? 0.0 : sum / waterPoints);
    }

    private List<double[]> samplePoints(HullStats hull) {
        List<double[]> points = new ArrayList<>(5);
        points.add(new double[]{hull.minX, hull.minZ});
        points.add(new double[]{hull.maxX, hull.minZ});
        points.add(new double[]{(hull.minX + hull.maxX) * 0.5, (hull.minZ + hull.maxZ) * 0.5});
        points.add(new double[]{hull.minX, hull.maxZ});
        points.add(new double[]{hull.maxX, hull.maxZ});
        return points;
    }

    private HullStats hullStats(ShipModel ship) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (ShipBlock block : ship.blocks()) {
            minX = Math.min(minX, block.x());
            minY = Math.min(minY, block.y());
            minZ = Math.min(minZ, block.z());
            maxX = Math.max(maxX, block.x());
            maxY = Math.max(maxY, block.y());
            maxZ = Math.max(maxZ, block.z());
        }
        return new HullStats(minX, minY, minZ, maxX, maxY, maxZ, ship.blockCount());
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record HullStats(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int count) {}
    private record WaterSample(int waterPoints, double averageWaterY) {}
}
