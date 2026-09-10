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
    private double floodFront;
    private double floodRear;
    private double floodLeft;
    private double floodRight;

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
    public void originYaw(float yaw) { origin.setYaw(yaw); }
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
    public double floodFront() { return floodFront; }
    public double floodRear() { return floodRear; }
    public double floodLeft() { return floodLeft; }
    public double floodRight() { return floodRight; }

    public void floodSides(double front, double rear, double left, double right) {
        floodFront = combineFloodSide(floodFront, front);
        floodRear = combineFloodSide(floodRear, rear);
        floodLeft = combineFloodSide(floodLeft, left);
        floodRight = combineFloodSide(floodRight, right);
    }

    public void addFloodSidePressure(double front, double rear, double left, double right) {
        floodFront = Math.max(floodFront, clamp01(front));
        floodRear = Math.max(floodRear, clamp01(rear));
        floodLeft = Math.max(floodLeft, clamp01(left));
        floodRight = Math.max(floodRight, clamp01(right));
    }

    public void clearFloodSides() {
        floodFront = 0.0;
        floodRear = 0.0;
        floodLeft = 0.0;
        floodRight = 0.0;
    }

    private static double combineFloodSide(double previous, double observed) {
        return Math.max(clamp01(observed), clamp01(previous) * 0.992);
    }

    private static double clamp01(double value) { return Math.max(0.0, Math.min(1.0, value)); }

    public double damage(double amount) {
        if (amount <= 0) return health;
        health = Math.max(0.0, health - amount);
        flooding(Math.max(flooding, 1.0 - health / maxHealth));
        return health;
    }

    public void repair(double amount) {
        if (amount <= 0) return;
        health = Math.min(maxHealth, health + amount);
        if (health >= maxHealth) flooding(0.0);
    }

    public boolean containsBlock(int x, int y, int z) {
        return findBlock(x, y, z).isPresent();
    }

    public Optional<ShipBlock> findBlock(int x, int y, int z) {
        for (ShipBlock block : blocks) {
            if (block.x() == x && block.y() == y && block.z() == z) return Optional.of(block);
        }
        return Optional.empty();
    }

    public boolean addBlock(ShipBlock block) {
        if (block == null || containsBlock(block.x(), block.y(), block.z())) return false;
        blocks.add(block);
        shipClass = ShipClass.fromBlockCount(blocks.size());
        maxHealth += 2.0;
        health = Math.min(maxHealth, health + 2.0);
        return true;
    }

    public Optional<ShipBlock> removeBlock(int x, int y, int z) {
        for (int i = 0; i < blocks.size(); i++) {
            ShipBlock block = blocks.get(i);
            if (block.x() == x && block.y() == y && block.z() == z) {
                blocks.remove(i);
                shipClass = ShipClass.fromBlockCount(blocks.size());
                return Optional.of(block);
            }
        }
        return Optional.empty();
    }

    public int blockCount() { return blocks.size(); }
}
