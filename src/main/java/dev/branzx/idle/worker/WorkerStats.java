package dev.branzx.idle.worker;

public record WorkerStats(int diligence, int luck, int stamina, int speed) {

    public String serialize() {
        return diligence + "," + luck + "," + stamina + "," + speed;
    }

    public static WorkerStats deserialize(String value) {
        String[] parts = value.split(",");
        return new WorkerStats(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]));
    }

    public WorkerStats plus(int dDiligence, int dLuck, int dStamina, int dSpeed) {
        return new WorkerStats(diligence + dDiligence, luck + dLuck, stamina + dStamina, speed + dSpeed);
    }
}
