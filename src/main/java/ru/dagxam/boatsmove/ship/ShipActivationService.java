package ru.dagxam.boatsmove.ship;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Performs the activation transaction for a player-built ship.
 *
 * The important invariant is simple: the world is changed only after a
 * complete snapshot has been created and all limits have passed. If removal
 * fails, every block already removed by this transaction is restored from the
 * same snapshot.
 */
public final class ShipActivationService {
    private final ShipRegistry registry;
    private final ShipStructureScanner scanner = new ShipStructureScanner();
    private final int minBlocks;
    private final int maxBlocks;
    private final Set<Material> forbidden;
    private final int maxActiveShips;

    public ShipActivationService(ShipRegistry registry, int minBlocks, int maxBlocks,
                                 Set<Material> forbidden, int maxActiveShips) {
        this.registry = registry;
        this.minBlocks = minBlocks;
        this.maxBlocks = maxBlocks;
        this.forbidden = Set.copyOf(forbidden);
        this.maxActiveShips = maxActiveShips;
    }

    public Result activate(Player player, Block control) {
        if (player == null || control == null) {
            return Result.failure("Не удалось определить игрока или контрольный блок.");
        }
        if (registry.size() >= maxActiveShips) {
            return Result.failure("Достигнут лимит активных кораблей: " + maxActiveShips + ".");
        }

        ShipStructureScanner.Result scan = scanner.scan(control, minBlocks, maxBlocks, forbidden);
        if (!scan.success()) {
            return Result.failure(scan.error());
        }

        ShipStructureScanner.ShipSnapshot snapshot = scan.snapshot();
        if (!snapshot.world().equals(control.getWorld())) {
            return Result.failure("Мир контрольного блока изменился во время активации.");
        }

        UUID ownerId = player.getUniqueId();
        ShipModel ship = new ShipModel(snapshot.id(), ownerId, snapshot.world(), snapshot.origin(), snapshot.blocks());
        ship.state(ShipState.ACTIVATING);

        // Re-check the exact source blocks before changing anything. This prevents
        // an activation from consuming a structure changed between scan and commit.
        for (ShipBlock block : snapshot.blocks()) {
            Block worldBlock = worldBlock(snapshot, block);
            if (!worldBlock.getBlockData().matches(block.blockData())) {
                return Result.failure("Конструкция изменилась во время активации. Попробуйте ещё раз.");
            }
        }

        Set<ShipBlock> removed = new HashSet<>();
        try {
            for (ShipBlock block : snapshot.blocks()) {
                worldBlock(snapshot, block).setType(Material.AIR, false);
                removed.add(block);
            }
        } catch (RuntimeException ex) {
            rollback(snapshot, removed);
            return Result.failure("Активация отменена: не удалось убрать все блоки. Конструкция восстановлена.");
        }

        ship.state(ShipState.ACTIVE);
        registry.register(ship);
        return Result.success(ship);
    }

    private Block worldBlock(ShipStructureScanner.ShipSnapshot snapshot, ShipBlock block) {
        World world = snapshot.world();
        return world.getBlockAt(
                snapshot.origin().getBlockX() + block.x(),
                snapshot.origin().getBlockY() + block.y(),
                snapshot.origin().getBlockZ() + block.z()
        );
    }

    private void rollback(ShipStructureScanner.ShipSnapshot snapshot, Set<ShipBlock> removed) {
        for (ShipBlock block : removed) {
            Block target = worldBlock(snapshot, block);
            target.setBlockData(block.blockData(), false);
            if (block.state() != null && block.state().blockState() != null) {
                try {
                    block.state().blockState().copy(target.getLocation()).update(true, false);
                } catch (RuntimeException ignored) {
                    // The activation result is already a failure; do not hide the
                    // original error. A later restoration pass can repair tile data.
                }
            }
        }
    }

    public record Result(boolean success, String message, ShipModel ship) {
        public static Result success(ShipModel ship) {
            return new Result(true, "Корабль активирован: " + ship.blockCount() + " блоков.", ship);
        }

        public static Result failure(String message) {
            return new Result(false, ChatColor.RED + message, null);
        }
    }
}
