package net.wdc.core.world.governor;

public record DrainReport(int processed, int remainingWaiting, int blocked, boolean budgetExhausted, boolean queueEmpty) {

    public static DrainReport idle(int remainingWaiting) {
        return new DrainReport(0, remainingWaiting, 0, false, remainingWaiting == 0);
    }
}
