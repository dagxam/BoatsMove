package ru.dagxam.boatsmove.ship;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

/** Derives propulsion and steering integrity directly from the surviving ship blocks. */
public final class ShipSystemsManager {
    private final Set<Material> engineMaterials;
    private final Set<Material> steeringMaterials;

    public ShipSystemsManager(Set<Material> engineMaterials, Set<Material> steeringMaterials) {
        this.engineMaterials = engineMaterials == null ? Set.of() : Set.copyOf(engineMaterials);
        this.steeringMaterials = steeringMaterials == null ? Set.of() : Set.copyOf(steeringMaterials);
    }

    public double engineIntegrity(ShipModel ship) {
        return integrity(ship, engineMaterials);
    }

    public double steeringIntegrity(ShipModel ship) {
        return integrity(ship, steeringMaterials);
    }

    /**
     * Returns a speed multiplier. A ship without configured engine blocks remains
     * compatible with simple builds; once engines are installed, damage/flooding
     * can progressively reduce propulsion and a destroyed engine stops propulsion.
     */
    public double propulsionMultiplier(ShipModel ship) {
        double integrity = engineIntegrity(ship);
        if (!hasSystem(ship, engineMaterials)) return 1.0;
        double floodPenalty = 1.0 - Math.max(0.0, ship.flooding()) * 0.55;
        return clamp(integrity * floodPenalty, 0.0, 1.0);
    }

    /** Returns the turn-rate multiplier from surviving steering blocks. */
    public double steeringMultiplier(ShipModel ship) {
        double integrity = steeringIntegrity(ship);
        if (!hasSystem(ship, steeringMaterials)) return 1.0;
        double floodPenalty = 1.0 - Math.max(0.0, ship.flooding()) * 0.40;
        return clamp(integrity * floodPenalty, 0.0, 1.0);
    }

    private double integrity(ShipModel ship, Set<Material> materials) {
        if (!hasSystem(ship, materials)) return 1.0;
        int total = 0;
        int operational = 0;
        for (ShipBlock block : ship.blocks()) {
            if (!materials.contains(block.data().getMaterial())) continue;
            total++;
            operational++;
        }
        return total == 0 ? 1.0 : (double) operational / total;
    }

    private boolean hasSystem(ShipModel ship, Set<Material> materials) {
        if (materials.isEmpty()) return false;
        for (ShipBlock block : ship.blocks()) {
            if (materials.contains(block.data().getMaterial())) return true;
        }
        return false;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

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
