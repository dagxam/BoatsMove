package ru.dagxam.boatsmove.ship;

import org.bukkit.block.data.BlockData;

/** A block in the ship's local coordinate system. */
public final class ShipBlock {
    private final int x;
    private final int y;
    private final int z;
    private final BlockData blockData;
    private ShipBlockState state;

    public ShipBlock(int x, int y, int z, BlockData blockData, ShipBlockState state) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockData = blockData.clone();
        this.state = state;
    }

    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public BlockData blockData() { return blockData.clone(); }
    public ShipBlockState state() { return state; }
    public void replaceState(ShipBlockState state) { this.state = state; }
}
