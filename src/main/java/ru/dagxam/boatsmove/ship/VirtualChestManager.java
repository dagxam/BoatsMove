package ru.dagxam.boatsmove.ship;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.type.Chest;
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
    private final ShipRegistry registry;
    private final Map<UUID, OpenStorage> open = new HashMap<>();

    public VirtualChestManager(ShipRegistry registry) {
        this.registry = registry;
        registry.storageManager(this);
    }

    public boolean open(Player player, VirtualBlockInteraction.VirtualHit hit) {
        Material type = hit.block().blockData().getMaterial();
        int baseSize = sizeFor(type);
        if (baseSize <= 0) return false;
        ShipBlockState state = hit.block().state();
        if (state == null || !state.hasInventory()) return false;

        List<ShipBlock> pair = findDoubleChestParts(hit.ship(), hit.block());
        boolean doubleChest = !pair.isEmpty();
        int size = doubleChest ? 54 : baseSize;
        VirtualStorageHolder holder = new VirtualStorageHolder();
        Inventory inventory = Bukkit.createInventory(holder, size, titleFor(type, hit.shipId(), doubleChest));
        holder.inventory(inventory);

        if (doubleChest) {
            ShipBlock first = pair.get(0), second = pair.get(1);
            put(inventory, 0, first.state().inventory());
            put(inventory, 27, second.state().inventory());
        } else put(inventory, 0, state.inventory());

        OpenStorage previous = open.remove(player.getUniqueId());
        if (previous != null) save(previous);
        open.put(player.getUniqueId(), new OpenStorage(hit.shipId(), hit.block().x(), hit.block().y(), hit.block().z(), type, inventory, doubleChest));
        player.openInventory(inventory);
        return true;
    }

    private void put(Inventory inventory, int offset, ItemStack[] source) {
        for (int i = 0; i < Math.min(source.length, 27); i++) if (source[i] != null) inventory.setItem(offset + i, source[i].clone());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        OpenStorage storage = open.get(player.getUniqueId());
        if (storage == null || event.getView().getTopInventory() != storage.inventory()) return;
        if (!isStillActive(storage)) { event.setCancelled(true); player.closeInventory(); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        OpenStorage storage = open.get(player.getUniqueId());
        if (storage == null || event.getView().getTopInventory() != storage.inventory()) return;
        if (!isStillActive(storage)) { event.setCancelled(true); player.closeInventory(); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        OpenStorage storage = open.remove(player.getUniqueId());
        if (storage == null || event.getInventory() != storage.inventory()) return;
        save(storage);
    }

    public void flushAll() { for (OpenStorage storage : new ArrayList<>(open.values())) save(storage); }

    public void flushShip(UUID shipId) {
        for (OpenStorage storage : new ArrayList<>(open.values())) if (storage.shipId().equals(shipId)) save(storage);
    }

    public void closeShip(UUID shipId) {
        for (Map.Entry<UUID, OpenStorage> entry : new ArrayList<>(open.entrySet())) {
            OpenStorage storage = entry.getValue();
            if (!storage.shipId().equals(shipId)) continue;
            save(storage);
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.getOpenInventory().getTopInventory() == storage.inventory()) player.closeInventory();
            open.remove(entry.getKey());
        }
    }

    /** Restores block data, captured TileState, then inventory contents. */
    public void restoreShip(ShipModel ship, World world, org.bukkit.Location origin) {
        for (ShipBlock block : ship.blocks()) {
            org.bukkit.block.Block target = world.getBlockAt(origin.getBlockX() + block.x(), origin.getBlockY() + block.y(), origin.getBlockZ() + block.z());
            target.setBlockData(block.blockData(), false);
        }
        for (ShipBlock block : ship.blocks()) {
            ShipBlockState snapshot = block.state();
            if (snapshot == null || snapshot.blockState() == null) continue;
            org.bukkit.block.Block target = world.getBlockAt(origin.getBlockX() + block.x(), origin.getBlockY() + block.y(), origin.getBlockZ() + block.z());
            snapshot.blockState().copy(target.getLocation()).update(true, false);
        }
        for (ShipBlock block : ship.blocks()) {
            ShipBlockState snapshot = block.state();
            if (snapshot == null || !snapshot.hasInventory()) continue;
            org.bukkit.block.Block target = world.getBlockAt(origin.getBlockX() + block.x(), origin.getBlockY() + block.y(), origin.getBlockZ() + block.z());
            org.bukkit.block.BlockState current = target.getState();
            if (current instanceof org.bukkit.block.Container container) container.getInventory().setContents(trimToSize(snapshot.inventory(), container.getInventory().getSize()));
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
        if (storage.doubleChest()) {
            List<ShipBlock> pair = findDoubleChestParts(ship, block);
            if (pair.size() == 2) {
                pair.get(0).replaceState(withInventory(pair.get(0).state(), slice(storage.inventory().getContents(), 0, 27)));
                pair.get(1).replaceState(withInventory(pair.get(1).state(), slice(storage.inventory().getContents(), 27, 54)));
                return;
            }
        }
        block.replaceState(withInventory(block.state(), storage.inventory().getContents()));
    }

    private static ItemStack[] slice(ItemStack[] source, int from, int to) {
        ItemStack[] result = new ItemStack[to - from];
        for (int i = from; i < to && i < source.length; i++) result[i - from] = source[i] == null ? null : source[i].clone();
        return result;
    }

    private static ShipBlockState withInventory(ShipBlockState state, ItemStack[] contents) { return new ShipBlockState(state.stateType(), state.blockState(), contents); }

    private static ShipBlock findBlock(ShipModel ship, int x, int y, int z) {
        for (ShipBlock block : ship.blocks()) if (block.x() == x && block.y() == y && block.z() == z) return block;
        return null;
    }

    private static List<ShipBlock> findDoubleChestParts(ShipModel ship, ShipBlock source) {
        List<ShipBlock> result = new ArrayList<>();
        Material type = source.blockData().getMaterial();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) return result;
        for (ShipBlock candidate : ship.blocks()) {
            if (candidate == source || candidate.state() == null || candidate.blockData().getMaterial() != type) continue;
            int distance = Math.abs(candidate.x() - source.x()) + Math.abs(candidate.y() - source.y()) + Math.abs(candidate.z() - source.z());
            if (distance == 1) result.add(candidate);
        }
        if (result.size() != 1) return List.of();
        result.add(0, source);
        result.sort((a, b) -> Integer.compare(chestOrder(a), chestOrder(b)));
        return result;
    }

    private static int chestOrder(ShipBlock block) {
        if (block.blockData() instanceof Chest chest) return chest.getType() == Chest.Type.LEFT ? 0 : 1;
        return 0;
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

    private static String titleFor(Material type, UUID shipId, boolean doubleChest) {
        String id = shipId.toString().substring(0, 8);
        if (doubleChest) return "Корабельный двойной сундук " + id;
        return switch (type) {
            case BARREL -> "Корабельная бочка " + id;
            case FURNACE -> "Корабельная печь " + id;
            case SMOKER -> "Корабельная коптильня " + id;
            case BLAST_FURNACE -> "Корабельная плавильня " + id;
            default -> "Корабельный сундук " + id;
        };
    }

    private static ItemStack[] trimToSize(ItemStack[] source, int size) {
        ItemStack[] result = new ItemStack[size];
        for (int i = 0; i < Math.min(source.length, size); i++) result[i] = source[i] == null ? null : source[i].clone();
        return result;
    }

    private record OpenStorage(UUID shipId, int x, int y, int z, Material type, Inventory inventory, boolean doubleChest) {}

    private static final class VirtualStorageHolder implements InventoryHolder {
        private Inventory inventory;
        private void inventory(Inventory inventory) { this.inventory = inventory; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
