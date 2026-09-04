package ru.dagxam.boatsmove.ship;

import org.bukkit.block.BlockState;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

/** Runtime snapshot of a block entity owned by the ship. */
public record ShipBlockState(
        String stateType,
        BlockState blockState,
        ItemStack[] inventory
) {
    public ShipBlockState {
        blockState = blockState == null ? null : blockState.copy();
        inventory = inventory == null ? new ItemStack[0] : cloneContents(inventory);
    }

    private static ItemStack[] cloneContents(ItemStack[] source) {
        return Arrays.stream(source)
                .map(item -> item == null ? null : item.clone())
                .toArray(ItemStack[]::new);
    }

    public boolean hasInventory() {
        return inventory.length > 0;
    }
}
