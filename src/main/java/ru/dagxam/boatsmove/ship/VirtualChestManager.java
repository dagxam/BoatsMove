package ru.dagxam.boatsmove.ship;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Virtual inventory/TileState bridge for containers on active ships. */
public final class VirtualChestManager implements Listener {
    private static final String ID_PREFIX = " ";
    private final ShipRegistry registry;
    private final Map<UUID, OpenStorage> open = new HashMap<>();

    public VirtualChestManager(ShipRegistry registry) {
        this.registry = registry;
    }

    public boolean open(Player player, VirtualBlockInteraction.VirtualHit hit) {
        Material type = hit.block().blockData().getMaterial();
        int baseSize = sizeFor(type);
        if (baseSize <= 0) return false;

        ShipBlockState state = hit.block().state();
        if (state == null || !state.hasInventory()) return false;

        boolean doubleChest = isDoubleChest(hit.ship(), hit.block());
        int size = doubleChest ? 54 : baseSize;
        VirtualStorageHolder holder = new VirtualStorageHolder();
        Inventory inventory = Bukkit.createInventory(holder, size, titleFor(type, hit.shipId()));
        holder.inventory(inventory);

        ItemStack[] source = state.inventory();
        for (int i = 0; i < Math.min(source.length, inventory.getSize()); i++) {
            if (source[i] != null) inventory.setItem(i, source[i].clone());
        }

        OpenStorage previous = open.remove(player.getUniqueId());
        if (previous != null) save(previous);
        open.put(player.getUniqueId(), new OpenStorage(hit.shipId(), hit.block().x(), hit.block().y(), hit.block().z(), type, inventory));
        player.openInventory(inventory);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        OpenStorage storage = open.get(player.getUniqueId());
        if (storage == null || event.getView().getTopInventory() != storage.inventory()) return;
        if (!isStillActive(storage)) {
            event.setCancelled(true);
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        OpenStorage storage = open.get(player.getUniqueId());
        if (storage == null || event.getView().getTopInventory() != storage.inventory()) return;
        if (!isStillActive(storage)) {
            event.setCancelled(true);
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        OpenStorage storage = open.remove(player.getUniqueId());
        if (storage == null || event.getInventory() != storage.inventory()) return;
        save(storage);
    }

    /** Writes every currently open virtual inventory back to its ShipModel. */
    public void flushAll() {
        for (OpenStorage storage : new ArrayList<>(open.values())) save(storage);
    }

    /** Writes all open virtual inventories belonging to one ship. */
    public void flushShip(UUID shipId) {
        for (OpenStorage storage : new ArrayList<>(open.values())) {
            if (storage.shipId().equals(shipId)) save(storage);
        }
    }

    /** Saves and closes all virtual inventories of a ship before deactivation. */
    public void closeShip(UUID shipId) {
        for (Map.Entry<UUID, OpenStorage> entry : new ArrayList<>(open.entrySet())) {
            OpenStorage storage = entry.getValue();
            if (!storage.shipId().equals(shipId)) continue;
            save(storage);
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.getOpenInventory().getTopInventory() == storage.inventory()) {
                player.closeInventory();
            }
            open.remove(entry.getKey());
        }
    }

    /**
     * Restores block data, then the captured TileState, then inventory contents.
     * This keeps furnace timers/properties and other BlockEntity state separate
     * from the inventory payload and restores them in the safe order.
     */
    public void restoreShip(ShipModel ship, World world, org.bukkit.Location origin) {
        List<ShipBlock> blocks = ship.blocks();

        for (ShipBlock block : blocks) {
            org.bukkit.block.Block target = world.getBlockAt(
                    origin.getBlockX() + block.x(),
                    origin.getBlockY() + block.y(),
                    origin.getBlockZ() + block.z());
            target.setBlockData(block.blockData(), false);
        }

        for (ShipBlock block : blocks) {
            ShipBlockState snapshot = block.state();
            if (snapshot == null || snapshot.blockState() == null) continue;
            org.bukkit.block.Block target = world.getBlockAt(
                    origin.getBlockX() + block.x(),
                    origin.getBlockY() + block.y(),
                    origin.getBlockZ() + block.z());
            try {
                snapshot.blockState().copy(target.getLocation()).update(true, false);
            } catch (RuntimeException ignored) {
                // Continue restoring the remaining BlockEntities.
            }
        }

        for (ShipBlock block : blocks) {
            ShipBlockState snapshot = block.state();
            if (snapshot == null || !snapshot.hasInventory()) continue;
            org.bukkit.block.Block target = world.getBlockAt(
                    origin.getBlockX() + block.x(),
                    origin.getBlockY() + block.y(),
                    origin.getBlockZ() + block.z());
            org.bukkit.block.BlockState current = target.getState();
            if (current instanceof org.bukkit.block.Container container) {
                container.getInventory().setContents(trimToSize(snapshot.inventory(), container.getInventory().getSize()));
            }
        }
    }

    private boolean isStillActive(OpenStorage storage) {
        ShipModel ship = registry.get(storage.shipId());
        return ship != null && ship.state() == ShipState.ACTIVE;
    }

    private void save(OpenStorage storage) {
        ShipModel ship = registry.get(storage.shipId());
        if (ship == null || (ship.state() != ShipState.ACTIVE && ship.state() != ShipState.DEACTIVATING)) return;

        ShipBlock block = findBlock(ship, storage.x(), storage.y(), storage.z());
        if (block == null || block.state() == null) return;

        ItemStack[] contents = storage.inventory().getContents();
        block.replaceState(withInventory(block.state(), contents));

        if (storage.type() == Material.CHEST || storage.type() == Material.TRAPPED_CHEST) {
            for (ShipBlock other : findDoubleChestParts(ship, block)) {
                other.replaceState(withInventory(other.state(), contents));
            }
        }
    }

    private static ShipBlockState withInventory(ShipBlockState state, ItemStack[] contents) {
        return new ShipBlockState(state.stateType(), state.blockState(), contents);
    }

    private static ShipBlock findBlock(ShipModel ship, int x, int y, int z) {
        for (ShipBlock block : ship.blocks()) {
            if (block.x() == x && block.y() == y && block.z() == z) return block;
        }
        return null;
    }

    /** Adjacent 54-slot chest snapshots represent the two halves of one chest. */
    private static List<ShipBlock> findDoubleChestParts(ShipModel ship, ShipBlock source) {
        List<ShipBlock> result = new ArrayList<>();
        if (source.state() == null || source.state().inventory().length != 54) return result;
        Material type = source.blockData().getMaterial();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) return result;

        for (ShipBlock candidate : ship.blocks()) {
            if (candidate == source || candidate.state() == null) continue;
            if (candidate.blockData().getMaterial() != type || candidate.state().inventory().length != 54) continue;
            int distance = Math.abs(candidate.x() - source.x())
                    + Math.abs(candidate.y() - source.y())
                    + Math.abs(candidate.z() - source.z());
            if (distance == 1) result.add(candidate);
        }
        return result;
    }

    private static boolean isDoubleChest(ShipModel ship, ShipBlock block) {
        return !findDoubleChestParts(ship, block).isEmpty();
    }

    private static int sizeFor(Material type) {
        return switch (type) {
            case CHEST, TRAPPED_CHEST, BARREL -> 27;
            case FURNACE, SMOKER, BLAST_FURNACE -> 3;
            case HOPPER -> 5;
            case DISPENSER, DROPPER -> 9;
            default -> 0;
        };
    }

    private static String titleFor(Material type, UUID shipId) {
        String id = shipId.toString().substring(0, 8);
        return switch (type) {
            case BARREL -> "Корабельная бочка" + ID_PREFIX + id;
            case FURNACE -> "Корабельная печь" + ID_PREFIX + id;
            case SMOKER -> "Корабельная коптильня" + ID_PREFIX + id;
            case BLAST_FURNACE -> "Корабельная плавильня" + ID_PREFIX + id;
            default -> "Корабельный сундук" + ID_PREFIX + id;
        };
    }

    private static ItemStack[] trimToSize(ItemStack[] source, int size) {
        ItemStack[] result = new ItemStack[size];
        for (int i = 0; i < Math.min(source.length, size); i++) {
            result[i] = source[i] == null ? null : source[i].clone();
        }
        return result;
    }

    private record OpenStorage(UUID shipId, int x, int y, int z, Material type, Inventory inventory) {}

    private static final class VirtualStorageHolder implements InventoryHolder {
        private Inventory inventory;

        private void inventory(Inventory inventory) { this.inventory = inventory; }

        @Override
        public Inventory getInventory() { return inventory; }
    }
}
