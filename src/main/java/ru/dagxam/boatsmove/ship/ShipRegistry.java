package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime registry of logical ships and their active runtime state.
 *
 * The registry deliberately owns ShipModel/vehicle state rather than relying
 * on a vanilla Boat entity. A ship is a player-built structure first; boat
 * behavior is a controller layered on top of that structure.
 */
public final class ShipRegistry {
    private final Map<UUID, ShipModel> ships = new LinkedHashMap<>();
    private final Map<UUID, ShipRuntimeState> runtime = new LinkedHashMap<>();

    public void register(ShipModel ship) {
        ships.put(ship.id(), ship);
        runtime.put(ship.id(), new ShipRuntimeState(ship.origin()));
    }

    public ShipModel get(UUID id) {
        return ships.get(id);
    }

    public ShipRuntimeState runtime(UUID id) {
        return runtime.get(id);
    }

    public Location position(ShipModel ship) {
        ShipRuntimeState state = runtime.get(ship.id());
        return state == null ? ship.origin() : state.position();
    }

    public void position(ShipModel ship, Location position) {
        runtime.computeIfAbsent(ship.id(), ignored -> new ShipRuntimeState(position)).position(position);
    }

    public void unregister(UUID id) {
        ships.remove(id);
        runtime.remove(id);
    }

    public Collection<ShipModel> all() {
        return Collections.unmodifiableCollection(ships.values());
    }

    public int size() {
        return ships.size();
    }

    public void clearRuntimeState() {
        ships.clear();
        runtime.clear();
    }
}
