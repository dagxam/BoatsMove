package ru.dagxam.boatsmove.ship;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Finds a connected ship structure without modifying the world. */
public final class ShipStructureScanner {
    // A ship can now connect by face, edge or corner. This supports structures
    // such as diagonal stair/beam chains while still requiring actual block contact.
    private static final int[][] NEIGHBORS = buildNeighbors();

    private static int[][] buildNeighbors() {
        List<int[]> result = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    result.add(new int[]{dx, dy, dz});
                }
            }
        }
        return result.toArray(new int[0][]);
    }

    public Result scan(Block control, int minBlocks, int maxBlocks, Set<Material> forbidden) {
        if (control == null || control.getType().isAir()) {
            return Result.failure("Контрольный блок не найден.");
        }

        World world = control.getWorld();
        int ox = control.getX(), oy = control.getY(), oz = control.getZ();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        List<ShipBlock> blocks = new ArrayList<>();
        queue.add(new int[]{ox, oy, oz});

        while (!queue.isEmpty()) {
            int[] p = queue.removeFirst();
            long key = pack(p[0], p[1], p[2]);
            if (!visited.add(key)) continue;

            Block block = world.getBlockAt(p[0], p[1], p[2]);
            Material type = block.getType();
            if (type.isAir()) continue;
            if (forbidden.contains(type)) {
                return Result.failure("Запрещённый блок в конструкции: " + type);
            }

            BlockState state = block.getState();
            ShipBlockState snapshot = snapshotState(state);
            blocks.add(new ShipBlock(p[0] - ox, p[1] - oy, p[2] - oz,
                    block.getBlockData(), snapshot));

            if (blocks.size() > maxBlocks) {
                return Result.failure("Корабль слишком большой. Максимум: " + maxBlocks + " блоков.");
            }

            for (int[] d : NEIGHBORS) {
                int nx = p[0] + d[0], ny = p[1] + d[1], nz = p[2] + d[2];
                if (ny < world.getMinHeight() || ny >= world.getMaxHeight()) continue;
                long nk = pack(nx, ny, nz);
                if (!visited.contains(nk) && !world.getBlockAt(nx, ny, nz).getType().isAir()) {
                    queue.addLast(new int[]{nx, ny, nz});
                }
            }
        }

        if (blocks.size() < minBlocks) {
            return Result.failure("Конструкция слишком маленькая. Минимум: " + minBlocks + " блоков.");
        }
        return Result.success(new ShipSnapshot(UUID.randomUUID(), world, control.getLocation(), blocks));
    }

    private ShipBlockState snapshotState(BlockState state) {
        Inventory inventory = state instanceof Container container ? container.getInventory() : null;
        ItemStack[] contents = inventory == null ? new ItemStack[0] : inventory.getContents();
        return new ShipBlockState(state.getType().name(), state, contents);
    }

    private static long pack(int x, int y, int z) {
        long lx = x & 0x3FFFFFFL, ly = y & 0xFFFL, lz = z & 0x3FFFFFFL;
        return (lx << 38) | (ly << 26) | lz;
    }

    public record ShipSnapshot(UUID id, World world, org.bukkit.Location origin, List<ShipBlock> blocks) {
        public ShipSnapshot {
            blocks = List.copyOf(blocks);
            origin = origin.clone();
        }
    }

    public record Result(boolean success, String error, ShipSnapshot snapshot) {
        public static Result success(ShipSnapshot snapshot) { return new Result(true, null, snapshot); }
        public static Result failure(String error) { return new Result(false, error, null); }
    }
}
