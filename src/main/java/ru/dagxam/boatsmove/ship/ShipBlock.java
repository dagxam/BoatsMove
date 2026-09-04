package ru.dagxam.boatsmove.ship;

import org.bukkit.block.data.BlockData;

/**
 * A block in the ship's local coordinate system.
 *
 * World coordinates are intentionally not stored here. When the ship moves,
 * local coordinates remain stable and are transformed into world coordinates.
 */
public record ShipBlock(
        int x,
        int y,
        int z,
        BlockData blockData,
        ShipBlockState state
) {
    public ShipBlock {
        blockData = blockData.clone();
    }
}
