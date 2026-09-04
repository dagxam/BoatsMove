package ru.dagxam.boatsmove.ship;

public enum ShipClass {
    SMALL(1.00, 1.00),
    MEDIUM(0.90, 1.15),
    LARGE(0.78, 1.30);

    private final double speedMultiplier;
    private final double buoyancyMultiplier;

    ShipClass(double speedMultiplier, double buoyancyMultiplier) {
        this.speedMultiplier = speedMultiplier;
        this.buoyancyMultiplier = buoyancyMultiplier;
    }

    public double speedMultiplier() { return speedMultiplier; }
    public double buoyancyMultiplier() { return buoyancyMultiplier; }

    public static ShipClass fromBlockCount(int blocks) {
        if (blocks >= 1000) return LARGE;
        if (blocks >= 200) return MEDIUM;
        return SMALL;
    }
}
