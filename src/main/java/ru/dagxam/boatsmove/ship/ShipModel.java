package ru.dagxam.boatsmove.ship;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Authoritative logical model of an active player-built ship. */
public final class ShipModel {
    private final UUID id;
    private final UUID ownerId;
    private final UUID worldId;
    private final List<ShipBlock> blocks;
    private final Location origin;
    private ShipState state = ShipState.BUILT;
    private float yaw;
    private float pitch;
    private ShipClass shipClass;
    private double health;
    private double maxHealth;
    private double flooding;

    public ShipModel(UUID id, UUID ownerId, World world, Location origin, List<ShipBlock> blocks) {
        this.id = id;
        this.ownerId = ownerId;
        this.worldId = world.getUID();
        this.origin = origin.clone();
        this.yaw = origin.getYaw();
        this.pitch = origin.getPitch();
        this.blocks = new ArrayList<>(blocks);
        this.shipClass = ShipClass.fromBlockCount(blocks.size());
        this.maxHealth = Math.max(20.0, blocks.size() * 2.0);
        this.health = maxHealth;
    }

    public UUID id() { return id; }
    public UUID ownerId() { return ownerId; }
    public UUID worldId() { return worldId; }
    public Location origin() { return origin.clone(); }
    public List<ShipBlock> blocks() { return List.copyOf(blocks); }
    public ShipState state() { return state; }
    public void state(ShipState state) { this.state = state; }
    public float yaw() { return yaw; }
    public void yaw(float yaw) { this.yaw = yaw; }
    public float pitch() { return pitch; }
    public void pitch(float pitch) { this.pitch = pitch; }
    public ShipClass shipClass() { return shipClass; }
    public void shipClass(ShipClass shipClass) { this.shipClass = shipClass == null ? ShipClass.SMALL : shipClass; }
    public double health() { return health; }
    public void health(double health) { this.health = Math.max(0.0, Math.min(maxHealth, health)); }
    public double maxHealth() { return maxHealth; }
    public void maxHealth(double maxHealth) {
        this.maxHealth = Math.max(20.0, maxHealth);
        this.health = Math.min(this.health, this.maxHealth);
    }
    public double flooding() { return flooding; }
    public void flooding(double flooding) { this.flooding = Math.max(0.0, Math.min(1.0, flooding)); }

    public double damage(double amount) {
        if (amount <= 0) return health;
        health = Math.max(0.0, health - amount);
        flooding(Math.max(flooding, 1.0 - health / maxHealth));
        return health;
    }

    public void repair(double amount) {
        if (amount <= 0) return;
        health = Math.min(maxHealth, health + amount);
        flooding(Math.min(flooding, 1.0 - health / maxHealth));
    }

    /** Removes exactly one logical block identified by its immutable local coordinates. */
    public Optional<ShipBlock> removeBlock(int x, int y, int z) {
        for (int i = 0; i < blocks.size(); i++) {
            ShipBlock block = blocks.get(i);
            if (block.x() == x && block.y() == y && block.z() == z) {
                blocks.remove(i);
                return Optional.of(block);
            }
        }
        return Optional.empty();
    }

    public boolean containsBlock(int x, int y, int z) {
        for (ShipBlock block : blocks) {
            if (block.x() == x && block.y() == y && block.z() == z) return true;
        }
        return false;
    }

    public int blockCount() { return blocks.size(); }
}
