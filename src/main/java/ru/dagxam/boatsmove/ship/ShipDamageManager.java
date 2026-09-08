package ru.dagxam.boatsmove.ship;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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
        if (ship == null || target == null || ship.state() != ShipState.ACTIVE) return false;
        if (!ship.containsBlock(target.x(), target.y(), target.z())) return false;

        VirtualChestManager storage = registry.storageManager();
        if (storage != null) storage.closeShip(ship.id());
        dropContainerContents(target, dropLocation);

        Optional<ShipBlock> removed = ship.removeBlock(target.x(), target.y(), target.z());
        if (removed.isEmpty()) return false;
        displays.removeBlock(ship.id(), target.x(), target.y(), target.z());

        double health = ship.damage(Math.max(0.1, impactDamage));
        ship.flooding(Math.min(1.0, ship.flooding() + floodingFromHole(target)));
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

    private double floodingFromHole(ShipBlock block) {
        int y = block.y();
        if (y <= 0) return 0.10;
        if (y == 1) return 0.07;
        return 0.04;
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
