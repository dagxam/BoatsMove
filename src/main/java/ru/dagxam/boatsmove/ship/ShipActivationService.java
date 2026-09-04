package ru.dagxam.boatsmove.ship;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Performs the activation/deactivation transaction for a player-built ship. */
public final class ShipActivationService {
    private final ShipRegistry registry;
    private final ShipStructureScanner scanner = new ShipStructureScanner();
    private final ShipDisplayManager displayManager;
    private final int minBlocks;
    private final int maxBlocks;
    private final Set<Material> forbidden;
    private final int maxActiveShips;

    public ShipActivationService(ShipRegistry registry, ShipDisplayManager displayManager,
                                 int minBlocks, int maxBlocks, Set<Material> forbidden,
                                 int maxActiveShips) {
        this.registry = registry;
        this.displayManager = displayManager;
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
        if (!scan.success()) return Result.failure(scan.error());

        ShipStructureScanner.ShipSnapshot snapshot = scan.snapshot();
        if (!snapshot.world().equals(control.getWorld())) {
            return Result.failure("Мир контрольного блока изменился во время активации.");
        }

        UUID ownerId = player.getUniqueId();
        ShipModel ship = new ShipModel(snapshot.id(), ownerId, snapshot.world(), snapshot.origin(), snapshot.blocks());
        ship.state(ShipState.ACTIVATING);

        // Re-check exact source blocks before changing the world.
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

            // Rendering is part of activation. If it fails, restore the original
            // blocks instead of leaving the player with an invisible ship.
            displayManager.spawn(ship);
        } catch (RuntimeException ex) {
            displayManager.remove(ship.id());
            rollback(snapshot, removed);
            return Result.failure("Активация отменена: не удалось создать визуальную модель. Конструкция восстановлена.");
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
                    // Keep the original activation error; a future restoration pass
                    // can repair complex tile-state data.
                }
            }
        }
    }

    public Result deactivate(ShipModel ship) {
        if (ship == null || ship.state() != ShipState.ACTIVE) {
            return Result.failure("Корабль не активен.");
        }

        ShipRuntimeState runtime = registry.runtime(ship.id());
        if (runtime == null) {
            return Result.failure("Не найдено состояние активного корабля.");
        }

        ship.state(ShipState.DEACTIVATING);
        displayManager.remove(ship.id());

        World world = displayWorld(ship);
        if (world == null) {
            ship.state(ShipState.FAILED);
            return Result.failure("Мир корабля не найден.");
        }

        var current = runtime.position();
        try {
            // Restore all real blocks at the ship's CURRENT position, not its
            // original activation position.
            for (ShipBlock block : ship.blocks()) {
                Block target = world.getBlockAt(
                        current.getBlockX() + block.x(),
                        current.getBlockY() + block.y(),
                        current.getBlockZ() + block.z());
                target.setBlockData(block.blockData(), false);
            }

            // BlockEntity inventory/state restoration is handled by the storage
            // layer. Do not pretend that setBlockData alone restores containers.
            ship.state(ShipState.BUILT);
            registry.unregister(ship.id());
            return new Result(true, "Корабль деактивирован в текущей позиции и восстановлен как блоки.", ship);
        } catch (RuntimeException ex) {
            ship.state(ShipState.FAILED);
            return Result.failure("Не удалось полностью восстановить корабль: " + ex.getMessage());
        }
    }

    private World displayWorld(ShipModel ship) {
        return pluginWorld(ship);
    }

    private World pluginWorld(ShipModel ship) {
        for (World world : registryWorlds()) {
            if (world.getUID().equals(ship.worldId())) return world;
        }
        return null;
    }

    private Set<World> registryWorlds() {
        return new HashSet<>(java.util.Objects.requireNonNullElseGet(
                org.bukkit.Bukkit.getWorlds(), java.util.List::of));
    }

    public Result failureResult(String message) { return Result.failure(message); }

    public record Result(boolean success, String message, ShipModel ship) {
        public static Result success(ShipModel ship) {
            return new Result(true, "Корабль активирован: " + ship.blockCount() + " блоков.", ship);
        }

        public static Result failure(String message) {
            return new Result(false, ChatColor.RED + message, null);
        }
    }
}
