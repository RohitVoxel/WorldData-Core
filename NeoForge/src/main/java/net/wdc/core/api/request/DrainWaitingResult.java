package net.wdc.core.api.request;

public record DrainWaitingResult(int processed, int remainingWaiting, boolean budgetExhausted, boolean queueEmpty, int blocked) {

    public boolean madeProgress() {
        return processed > 0;
    }
}
