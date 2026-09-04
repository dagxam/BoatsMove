package ru.dagxam.boatsmove.ship;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/** Applies damage to logical ships and converts structural damage into flooding. */
public final class ShipDamageManager {
    private final ShipRegistry registry;
    private final ShipActivationService activation;

    public ShipDamageManager(ShipRegistry registry, ShipActivationService activation) {
        this.registry = registry;
        this.activation = activation;
    }

    public boolean damage(ShipModel ship, double amount, Player source) {
        if (ship == null || ship.state() != ShipState.ACTIVE) return false;
        double health = ship.damage(amount);
        if (source != null) {
            source.sendActionBar(ChatColor.RED + "Корабль: " + Math.ceil(health) + "/" + Math.ceil(ship.maxHealth())
                    + " HP  " + ChatColor.AQUA + "затопление " + Math.round(ship.flooding() * 100) + "%");
        }
        if (health <= 0.0) {
            activation.deactivate(ship);
        }
        return true;
    }

    public boolean repair(ShipModel ship, double amount) {
        if (ship == null || ship.state() != ShipState.ACTIVE) return false;
        ship.repair(amount);
        return true;
    }
}
