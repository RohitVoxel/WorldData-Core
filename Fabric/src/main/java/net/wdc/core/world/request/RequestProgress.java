package net.wdc.core.world.request;

public record RequestProgress(long totalLogical, long completed, long active) {

    public RequestProgress {
        if (totalLogical < 0) throw new IllegalArgumentException("totalLogical must be >= 0");
        if (completed < 0) throw new IllegalArgumentException("completed must be >= 0");
        if (active < 0) throw new IllegalArgumentException("active must be >= 0");
    }

    public static RequestProgress initial(long totalLogical) {
        return new RequestProgress(totalLogical, 0L, 0L);
    }

    public long remaining() {
        return Math.max(0L, totalLogical - completed - active);
    }

    public boolean isComplete() {
        return totalLogical > 0 && completed >= totalLogical;
    }
}
