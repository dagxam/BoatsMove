package ru.dagxam.boatsmove.ship;

import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Optional block-state snapshot. Containers are stored here independently of
 * any entity so activation/deactivation cannot destroy their contents.
 *
 * The concrete serializer will be added with the storage subsystem. The
 * current model keeps the contract explicit without coupling the ship to a
 * particular persistence format.
 */
public record ShipBlockState(
        String stateType,
        Map<Integer, ItemStack> inventory,
        Map<String, Object> properties
) {
    public ShipBlockState {
        inventory = inventory == null ? Map.of() : Map.copyOf(inventory);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public boolean hasInventory() {
        return !inventory.isEmpty();
    }
}
