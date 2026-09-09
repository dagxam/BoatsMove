package ru.dagxam.boatsmove.ship;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Optional;

/** Applies structural damage to logical ships and preserves destroyed-container contents. */
public final class ShipDamageManager {
    private final ShipRegistry registry;
    private final ShipActivationService activation;
    private final ShipDisplayManager displays;

    public ShipDamageManager(ShipRegistry registry, ShipActivationService activation, ShipDisplayManager displays) {
        this.registry = registry;
        this.activation = activation;
        this.displays = displays;
    }

    public boolean damage(ShipModel ship, double amount, Player source) {
        if (ship == null || ship.state() != ShipState.ACTIVE) return false;
        double health = ship.damage(amount);
        sendStatus(source, ship, health);
        if (health <= 0.0) activation.deactivate(ship);
        return true;
    }

    /** Destroys the exact block selected by the virtual ray trace. */
    public boolean damageBlock(ShipModel ship, ShipBlock target, Location dropLocation, Player source) {
        return damageBlock(ship, target, dropLocation, damageFor(target == null ? null : target.blockData().getMaterial()), source);
    }

    /** Destroys a block with an explicit impact damage value (used by projectiles). */
    public boolean damageBlock(ShipModel ship, ShipBlock target, Location dropLocation, double impactDamage, Player source) {
        return damageBlock(ship, target, dropLocation, impactDamage, null, source);
    }

    /** Destroys a block and derives the leak from the actual impact point when available. */
    public boolean damageBlock(ShipModel ship, ShipBlock target, Location dropLocation, double impactDamage, Vector impactDirection, Player source) {
        if (ship == null || target == null || ship.state() != ShipState.ACTIVE) return false;
        if (!ship.containsBlock(target.x(), target.y(), target.z())) return false;

        VirtualChestManager storage = registry.storageManager();
        if (storage != null) storage.closeShip(ship.id());
        dropContainerContents(target, dropLocation);

        Optional<ShipBlock> removed = ship.removeBlock(target.x(), target.y(), target.z());
        if (removed.isEmpty()) return false;
        displays.removeBlock(ship.id(), target.x(), target.y(), target.z());

        double health = ship.damage(Math.max(0.1, impactDamage));
        double holeFlood = floodingFromHole(ship, target, impactDirection);
        ship.flooding(Math.min(1.0, ship.flooding() + holeFlood));
        applyDirectionalBreachPressure(ship, target, impactDirection);
        sendStatus(source, ship, health);

        if (ship.blockCount() == 0 || health <= 0.0) activation.deactivate(ship);
        return true;
    }

    private void dropContainerContents(ShipBlock block, Location location) {
        if (location == null) return;
        ShipBlockState state = block.state();
        if (state == null || !state.hasInventory()) return;
        World world = location.getWorld();
        if (world == null) return;
        for (ItemStack item : state.inventory()) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) world.dropItemNaturally(location, item.clone());
        }
    }

    private double damageFor(Material material) {
        if (material == null) return 4.0;
        if (material == Material.OBSIDIAN || material == Material.CRYING_OBSIDIAN || material == Material.RESPAWN_ANCHOR) return 16.0;
        if (material == Material.NETHERITE_BLOCK || material == Material.ANCIENT_DEBRIS) return 12.0;
        if (material.name().endsWith("_ORE") || material.name().endsWith("_BLOCK") || material == Material.DIAMOND_BLOCK) return 8.0;
        if (material == Material.CHEST || material == Material.TRAPPED_CHEST || material == Material.BARREL
                || material == Material.FURNACE || material == Material.SMOKER || material == Material.BLAST_FURNACE) return 6.0;
        if (!material.isSolid()) return 2.0;
        return 4.0;
    }

    private void applyDirectionalBreachPressure(ShipModel ship, ShipBlock block, Vector impactDirection) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ShipBlock part : ship.blocks()) {
            minX = Math.min(minX, part.x());
            maxX = Math.max(maxX, part.x());
            minZ = Math.min(minZ, part.z());
            maxZ = Math.max(maxZ, part.z());
        }

        double front = block.z() == maxZ ? 0.0 : 0.0;
        double rear = block.z() == minZ ? 0.0 : 0.0;
        double left = block.x() == minX ? 0.0 : 0.0;
        double right = block.x() == maxX ? 0.0 : 0.0;

        // Local hull edge is the strongest signal; the projectile direction is a secondary signal.
        if (block.z() == maxZ) front = 0.22;
        if (block.z() == minZ) rear = 0.22;
        if (block.x() == minX) left = 0.22;
        if (block.x() == maxX) right = 0.22;

        if (impactDirection != null && impactDirection.lengthSquared() > 1.0E-9) {
            Vector direction = impactDirection.clone().normalize();
            double horizontal = Math.sqrt(direction.getX() * direction.getX() + direction.getZ() * direction.getZ());
            if (horizontal > 0.15) {
                double sidePressure = Math.min(0.16, horizontal * 0.16);
                if (Math.abs(direction.getZ()) >= Math.abs(direction.getX())) {
                    if (direction.getZ() > 0.0) front = Math.max(front, sidePressure);
                    else rear = Math.max(rear, sidePressure);
                } else {
                    if (direction.getX() < 0.0) left = Math.max(left, sidePressure);
                    else right = Math.max(right, sidePressure);
                }
            }
        }

        if (front + rear + left + right <= 0.0) {
            // Interior damage still creates a weak directional pressure that decays over time.
            if (block.z() >= (minZ + maxZ) * 0.5) front = 0.07;
            else rear = 0.07;
        }

        ship.addFloodSidePressure(front, rear, left, right);
    }

    private double floodingFromHole(ShipModel ship, ShipBlock block, Vector impactDirection) {
        int y = block.y();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ShipBlock part : ship.blocks()) {
            minY = Math.min(minY, part.y());
            maxY = Math.max(maxY, part.y());
            minX = Math.min(minX, part.x());
            maxX = Math.max(maxX, part.x());
            minZ = Math.min(minZ, part.z());
            maxZ = Math.max(maxZ, part.z());
        }

        double base = y <= minY ? 0.12 : y <= minY + 1 ? 0.08 : 0.035;
        double verticalExposure = maxY > minY ? 1.0 - (double) (y - minY) / (maxY - minY) : 1.0;
        double edgeBonus = (block.x() == minX || block.x() == maxX || block.z() == minZ || block.z() == maxZ) ? 0.035 : 0.0;

        if (impactDirection != null && impactDirection.lengthSquared() > 1.0E-9) {
            Vector direction = impactDirection.clone().normalize();
            double horizontal = Math.sqrt(direction.getX() * direction.getX() + direction.getZ() * direction.getZ());
            if (horizontal > 0.15) edgeBonus += Math.min(0.04, horizontal * 0.04);
            if (direction.getY() > 0.55) base *= 0.55;
        }
        return Math.max(0.01, Math.min(0.22, base * (0.70 + 0.30 * verticalExposure) + edgeBonus));
    }

    private void sendStatus(Player source, ShipModel ship, double health) {
        if (source == null) return;
        source.sendActionBar(ChatColor.RED + "Корабль: " + Math.ceil(health) + "/" + Math.ceil(ship.maxHealth())
                + " HP  " + ChatColor.AQUA + "затопление " + Math.round(ship.flooding() * 100) + "%"
                + ChatColor.GRAY + "  блоков: " + ship.blockCount());
    }

    public boolean repair(ShipModel ship, double amount) {
        if (ship == null || ship.state() != ShipState.ACTIVE) return false;
        ship.repair(amount);
        return true;
    }
}
