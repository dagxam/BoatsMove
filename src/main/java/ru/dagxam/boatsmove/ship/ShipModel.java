package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Immutable identity plus mutable lifecycle data for a player-built ship.
 *
 * ShipModel is the source of truth while active. Rendering, movement and
 * passenger handling must never become the authoritative storage for blocks
 * or inventories.
 */
public final class ShipModel {
    private final UUID id;
    private final UUID ownerId;
    private final UUID worldId;
    private final List<ShipBlock> blocks;
    private final Location origin;

    private ShipState state = ShipState.BUILT;
    private float yaw;
    private float pitch;

    public ShipModel(UUID id, UUID ownerId, World world, Location origin, List<ShipBlock> blocks) {
        this.id = id;
        this.ownerId = ownerId;
        this.worldId = world.getUID();
        this.origin = origin.clone();
        this.yaw = origin.getYaw();
        this.pitch = origin.getPitch();
        this.blocks = new ArrayList<>(blocks);
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID worldId() {
        return worldId;
    }

    public Location origin() {
        return origin.clone();
    }

    public List<ShipBlock> blocks() {
        return List.copyOf(blocks);
    }

    public ShipState state() {
        return state;
    }

    public void state(ShipState state) {
        this.state = state;
    }

    public float yaw() {
        return yaw;
    }

    public void yaw(float yaw) {
        this.yaw = yaw;
    }

    public float pitch() {
        return pitch;
    }

    public void pitch(float pitch) {
        this.pitch = pitch;
    }

    public int blockCount() {
        return blocks.size();
    }
}
