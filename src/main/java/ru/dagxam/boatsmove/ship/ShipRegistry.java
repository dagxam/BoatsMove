package ru.dagxam.boatsmove.ship;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime registry of logical ships.
 *
 * The registry deliberately owns ShipModel/vehicle state rather than relying
 * on a vanilla Boat entity. A ship is a player-built structure first; boat
 * behavior is a controller layered on top of that structure.
 */
public final class ShipRegistry {
    private final Map<UUID, ShipModel> ships = new LinkedHashMap<>();

    public void register(ShipModel ship) {
        ships.put(ship.id(), ship);
    }

    public ShipModel get(UUID id) {
        return ships.get(id);
    }

    public void unregister(UUID id) {
        ships.remove(id);
    }

    public Collection<ShipModel> all() {
        return Collections.unmodifiableCollection(ships.values());
    }

    public int size() {
        return ships.size();
    }

    public void clearRuntimeState() {
        ships.clear();
    }
}
