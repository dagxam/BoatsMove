package ru.dagxam.boatsmove.ship;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Derives propulsion and steering integrity from the surviving logical ship blocks. */
public final class ShipSystemsManager {
    private final Set<Material> engineMaterials;
    private final Set<Material> steeringMaterials;
    private final Map<UUID, Integer> baselineEngines = new HashMap<>();
    private final Map<UUID, Integer> baselineSteering = new HashMap<>();

    public ShipSystemsManager(Set<Material> engineMaterials, Set<Material> steeringMaterials) {
        this.engineMaterials = engineMaterials == null ? Set.of() : Set.copyOf(engineMaterials);
        this.steeringMaterials = steeringMaterials == null ? Set.of() : Set.copyOf(steeringMaterials);
    }

    public double engineIntegrity(ShipModel ship) { return integrity(ship, engineMaterials, baselineEngines); }
    public double steeringIntegrity(ShipModel ship) { return integrity(ship, steeringMaterials, baselineSteering); }

    /** A ship with no configured engine blocks keeps legacy propulsion behavior. */
    public double propulsionMultiplier(ShipModel ship) {
        if (engineMaterials.isEmpty()) return 1.0;
        int current = count(ship, engineMaterials);
        if (current == 0 && !baselineEngines.containsKey(ship.id())) return 1.0;
        double floodPenalty = 1.0 - Math.max(0.0, ship.flooding()) * 0.55;
        return clamp(engineIntegrity(ship) * floodPenalty, 0.0, 1.0);
    }

    /** A ship with no configured steering blocks keeps legacy steering behavior. */
    public double steeringMultiplier(ShipModel ship) {
        if (steeringMaterials.isEmpty()) return 1.0;
        int current = count(ship, steeringMaterials);
        if (current == 0 && !baselineSteering.containsKey(ship.id())) return 1.0;
        double floodPenalty = 1.0 - Math.max(0.0, ship.flooding()) * 0.40;
        return clamp(steeringIntegrity(ship) * floodPenalty, 0.0, 1.0);
    }

    public void forget(ShipModel ship) {
        baselineEngines.remove(ship.id());
        baselineSteering.remove(ship.id());
    }

    private double integrity(ShipModel ship, Set<Material> materials, Map<UUID, Integer> baselines) {
        if (materials.isEmpty()) return 1.0;
        int current = count(ship, materials);
        int baseline = baselines.computeIfAbsent(ship.id(), id -> Math.max(1, current));
        return clamp((double) current / baseline, 0.0, 1.0);
    }

    private int count(ShipModel ship, Set<Material> materials) {
        int count = 0;
        for (ShipBlock block : ship.blocks()) if (materials.contains(block.data().getMaterial())) count++;
        return count;
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    public static Set<Material> parseMaterials(java.util.List<String> names) {
        if (names == null || names.isEmpty()) return Set.of();
        EnumSet<Material> result = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) result.add(material);
        }
        return Set.copyOf(result);
    }
}
