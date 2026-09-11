package ru.dagxam.boatsmove.ship;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Performs activation/deactivation transactions for logical ships. */
public final class ShipActivationService {
    private final ShipRegistry registry;
    private final ShipStructureScanner scanner = new ShipStructureScanner();
    private final ShipDisplayManager displayManager;
    private final ShipPassengerManager passengerManager;
    private final int minBlocks;
    private final int maxBlocks;
    private final Set<Material> forbidden;
    private final int maxActiveShips;

    public ShipActivationService(ShipRegistry registry, ShipDisplayManager displayManager,
                                 ShipPassengerManager passengerManager, int minBlocks, int maxBlocks,
                                 Set<Material> forbidden, int maxActiveShips) {
        this.registry = registry;
        this.displayManager = displayManager;
        this.passengerManager = passengerManager;
        this.minBlocks = minBlocks;
        this.maxBlocks = maxBlocks;
        this.forbidden = Set.copyOf(forbidden);
        this.maxActiveShips = maxActiveShips;
    }

    public Result activate(Player player, Block control) {
        if (player == null || control == null) return Result.failure("Не удалось определить игрока или контрольный блок.");
        if (registry.size() >= maxActiveShips) return Result.failure("Достигнут лимит активных кораблей: " + maxActiveShips + ".");
        ShipStructureScanner.Result scan = scanner.scan(control, minBlocks, maxBlocks, forbidden);
        if (!scan.success()) return Result.failure(scan.error());
        ShipStructureScanner.ShipSnapshot snapshot = scan.snapshot();
        if (!snapshot.world().equals(control.getWorld())) return Result.failure("Мир контрольного блока изменился во время активации.");
        ShipModel ship = new ShipModel(snapshot.id(), player.getUniqueId(), snapshot.world(), snapshot.origin(), snapshot.blocks());
        // Establish the activation heading before spawning displays. This prevents
        // the first display pose from being interpreted as an unwanted rotation.
        ship.originYaw(player.getYaw());
        ship.yaw(player.getYaw());
        ship.state(ShipState.ACTIVATING);
        for (ShipBlock block : snapshot.blocks()) {
            Block worldBlock = worldBlock(snapshot, block);
            if (!worldBlock.getBlockData().matches(block.blockData())) return Result.failure("Конструкция изменилась во время активации. Попробуйте ещё раз.");
        }
        Set<ShipBlock> removed = new HashSet<>();
        try {
            for (ShipBlock block : snapshot.blocks()) {
                worldBlock(snapshot, block).setType(Material.AIR, false);
                removed.add(block);
            }
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
        return snapshot.world().getBlockAt(snapshot.origin().getBlockX() + block.x(), snapshot.origin().getBlockY() + block.y(), snapshot.origin().getBlockZ() + block.z());
    }

    private void rollback(ShipStructureScanner.ShipSnapshot snapshot, Set<ShipBlock> removed) {
        for (ShipBlock block : removed) {
            Block target = worldBlock(snapshot, block);
            target.setBlockData(block.blockData(), false);
            if (block.state() != null && block.state().blockState() != null) {
                try { block.state().blockState().copy(target.getLocation()).update(true, false); } catch (RuntimeException ignored) { }
            }
        }
    }

    public Result deactivate(ShipModel ship) {
        if (ship == null || ship.state() != ShipState.ACTIVE) return Result.failure("Корабль не активен.");
        ShipRuntimeState runtime = registry.runtime(ship.id());
        if (runtime == null) return Result.failure("Не найдено состояние активного корабля.");
        World world = displayWorld(ship);
        if (world == null) return Result.failure("Мир корабля не найден.");

        int quarterTurns = nearestQuarterTurn(ship);
        org.bukkit.Location current = runtime.position();
        org.bukkit.Location restoreOrigin = current.clone();
        restoreOrigin.setX(Math.rint(current.getX()));
        restoreOrigin.setY(Math.rint(current.getY()));
        restoreOrigin.setZ(Math.rint(current.getZ()));
        restoreOrigin.setYaw(normalizeYaw(ship.origin().getYaw() + quarterTurns * 90.0f));
        restoreOrigin.setPitch(0.0f);

        if (!destinationSafe(ship, world, restoreOrigin, quarterTurns)) {
            return Result.failure("Деактивация отменена: место занято, чанки не загружены или часть корабля выходит за пределы мира.");
        }

        ship.state(ShipState.DEACTIVATING);
        if (passengerManager != null) passengerManager.releaseForDeactivation(ship);

        VirtualChestManager storage = registry.storageManager();
        try {
            if (storage != null) {
                storage.flushShip(ship.id());
                storage.closeShip(ship.id());
            }
            restoreShip(ship, world, restoreOrigin, quarterTurns);
            displayManager.remove(ship.id());
            ship.yaw(restoreOrigin.getYaw());
            ship.pitch(0.0f);
            ship.state(ShipState.BUILT);
            registry.unregister(ship.id());
            return new Result(true, "Корабль деактивирован и полностью восстановлен в ближайшей допустимой ориентации блоков.", ship);
        } catch (RuntimeException ex) {
            ship.state(ShipState.FAILED);
            return Result.failure("Восстановление остановлено для безопасности: " + ex.getMessage());
        }
    }

    private int nearestQuarterTurn(ShipModel ship) {
        float relative = normalizeYaw(ship.yaw() - ship.origin().getYaw());
        return Math.floorMod(Math.round(relative / 90.0f), 4);
    }

    private float normalizeYaw(float yaw) {
        float result = yaw % 360.0f;
        if (result < 0) result += 360.0f;
        return result;
    }

    private int rotationIndex(int quarterTurns) { return Math.floorMod(quarterTurns, 4); }

    private int rotatedX(ShipBlock block, int quarterTurns) {
        return switch (rotationIndex(quarterTurns)) {
            case 1 -> -block.z();
            case 2 -> -block.x();
            case 3 -> block.z();
            default -> block.x();
        };
    }

    private int rotatedZ(ShipBlock block, int quarterTurns) {
        return switch (rotationIndex(quarterTurns)) {
            case 1 -> block.x();
            case 2 -> -block.z();
            case 3 -> -block.x();
            default -> block.z();
        };
    }

    private boolean destinationSafe(ShipModel ship, World world, org.bukkit.Location origin, int quarterTurns) {
        int minY = ship.blocks().stream().mapToInt(ShipBlock::y).min().orElse(0);
        int maxY = ship.blocks().stream().mapToInt(ShipBlock::y).max().orElse(0);
        if (origin.getBlockY() + minY < world.getMinHeight() || origin.getBlockY() + maxY >= world.getMaxHeight()) return false;

        Set<String> checked = new HashSet<>();
        for (ShipBlock block : ship.blocks()) {
            int x = origin.getBlockX() + rotatedX(block, quarterTurns);
            int y = origin.getBlockY() + block.y();
            int z = origin.getBlockZ() + rotatedZ(block, quarterTurns);
            if (!checked.add(x + ":" + y + ":" + z)) continue;
            if (!world.getWorldBorder().isInside(new org.bukkit.Location(world, x + 0.5, y + 0.5, z + 0.5))) return false;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
            Material type = world.getBlockAt(x, y, z).getType();
            if (!type.isAir() && type != Material.WATER && type != Material.BUBBLE_COLUMN) return false;
        }
        return true;
    }

    private org.bukkit.block.data.BlockData rotatedBlockData(ShipBlock block, int quarterTurns) {
        org.bukkit.block.data.BlockData data = block.blockData().clone();
        switch (rotationIndex(quarterTurns)) {
            case 1 -> data.rotate(StructureRotation.CLOCKWISE_90);
            case 2 -> data.rotate(StructureRotation.CLOCKWISE_180);
            case 3 -> data.rotate(StructureRotation.COUNTERCLOCKWISE_90);
            default -> { }
        }
        return data;
    }

    private void restoreShip(ShipModel ship, World world, org.bukkit.Location origin, int quarterTurns) {
        Map<String, Block> targets = new HashMap<>();
        Map<String, org.bukkit.block.data.BlockData> originalData = new HashMap<>();
        try {
            for (ShipBlock block : ship.blocks()) {
                int x = origin.getBlockX() + rotatedX(block, quarterTurns);
                int y = origin.getBlockY() + block.y();
                int z = origin.getBlockZ() + rotatedZ(block, quarterTurns);
                String key = x + ":" + y + ":" + z;
                Block target = world.getBlockAt(x, y, z);
                targets.put(key, target);
                originalData.putIfAbsent(key, target.getBlockData());
            }

            for (ShipBlock block : ship.blocks()) {
                Block target = targetFor(world, origin, block, quarterTurns);
                org.bukkit.block.data.BlockData expected = rotatedBlockData(block, quarterTurns);
                target.setBlockData(expected, false);
                if (!target.getBlockData().matches(expected)) throw new IllegalStateException("BlockData не восстановился в " + target.getLocation());
            }

            for (ShipBlock block : ship.blocks()) {
                ShipBlockState snapshot = block.state();
                if (snapshot == null || snapshot.blockState() == null) continue;
                Block target = targetFor(world, origin, block, quarterTurns);
                snapshot.blockState().copy(target.getLocation()).update(true, false);
            }

            VirtualChestManager storage = registry.storageManager();
            if (storage != null) storage.restoreInventoriesOnly(ship, world, origin, quarterTurns);

            for (ShipBlock block : ship.blocks()) {
                ShipBlockState snapshot = block.state();
                if (snapshot == null || !snapshot.hasInventory()) continue;
                Block target = targetFor(world, origin, block, quarterTurns);
                if (!(target.getState() instanceof org.bukkit.block.Container)) throw new IllegalStateException("Не удалось восстановить контейнер " + target.getLocation());
            }
        } catch (RuntimeException ex) {
            for (Map.Entry<String, Block> entry : targets.entrySet()) {
                org.bukkit.block.data.BlockData data = originalData.get(entry.getKey());
                if (data != null) try { entry.getValue().setBlockData(data, false); } catch (RuntimeException ignored) { }
            }
            throw new IllegalStateException("Ошибка полной материализации: " + ex.getMessage(), ex);
        }
    }

    private Block targetFor(World world, org.bukkit.Location origin, ShipBlock block, int quarterTurns) {
        return world.getBlockAt(origin.getBlockX() + rotatedX(block, quarterTurns),
                origin.getBlockY() + block.y(), origin.getBlockZ() + rotatedZ(block, quarterTurns));
    }

    private World displayWorld(ShipModel ship) {
        for (World world : new HashSet<>(org.bukkit.Bukkit.getWorlds())) if (world.getUID().equals(ship.worldId())) return world;
        return null;
    }

    public Result failureResult(String message) { return Result.failure(message); }

    public record Result(boolean success, String message, ShipModel ship) {
        public static Result success(ShipModel ship) { return new Result(true, "Корабль активирован: " + ship.blockCount() + " блоков.", ship); }
        public static Result failure(String message) { return new Result(false, ChatColor.RED + message, null); }
    }
}
