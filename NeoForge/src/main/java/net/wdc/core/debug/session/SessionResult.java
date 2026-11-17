package net.wdc.core.debug.session;

import net.wdc.core.redirect.RedirectCounts;

import java.time.ZonedDateTime;

public record SessionResult(
        ZonedDateTime startedAt,
        ZonedDateTime endedAt,
        int requestedSeconds,
        double actualSeconds,
        EndReason reason,
        boolean redirectAtStart,
        boolean redirectAtEnd,
        boolean redirectChanged,
        TickStats.Snapshot ticks,
        RedirectCounts redirect,
        CallerWindow callers) {

    public enum EndReason {
        COMPLETED("completed"),
        STOPPED("stopped early"),
        SERVER_STOPPING("server stopping");

        private final String label;

        EndReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
