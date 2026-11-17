package net.wdc.core.debug.session;

import net.wdc.core.debug.monitor.MonitorSnapshot;
import net.wdc.core.debug.monitor.WDCApiMonitor;
import net.wdc.core.redirect.ReadRedirect;
import net.wdc.core.redirect.RedirectCounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class SessionMonitor {

    public static final int DEFAULT_SECONDS = 300;
    public static final int MIN_SECONDS = 60;
    public static final int MAX_SECONDS = 600;

    public enum StartOutcome { STARTED, ALREADY_RUNNING, NOT_ALLOWED, INVALID_DURATION }

    public record Status(boolean running, double elapsedSeconds, int requestedSeconds, boolean redirectAtStart,
                         boolean redirectNow, TickStats.Snapshot ticks, Path lastFile, String lastSummary) {}

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Session");

    private final LongSupplier nanoClock;
    private final Supplier<ZonedDateTime> wallClock;
    private final Supplier<Path> directory;
    private final BooleanSupplier allowed;

    private volatile boolean running;

    private final TickStats ticks = new TickStats();
    private long startNanos;
    private long deadlineNanos;
    private long tickStartNanos;
    private boolean tickOpen;
    private int requestedSeconds;
    private ZonedDateTime startedAt;
    private boolean redirectAtStart;
    private long togglesAtStart;
    private RedirectCounts redirectBase = RedirectCounts.ZERO;
    private MonitorSnapshot monitorBase;
    private boolean monitorEnabledBySession;
    private long monitorResetsAtStart;
    private String monitorProblem;
    private Consumer<String> notifier;
    private Path lastFile;
    private String lastSummary;

    public SessionMonitor(LongSupplier nanoClock, Supplier<ZonedDateTime> wallClock, Supplier<Path> directory,
                          BooleanSupplier allowed) {
        this.nanoClock = nanoClock;
        this.wallClock = wallClock;
        this.directory = directory;
        this.allowed = allowed;
    }

    public boolean running() {
        return running;
    }

    public StartOutcome start(int seconds, Consumer<String> notifier) {
        if (!allowed.getAsBoolean()) return StartOutcome.NOT_ALLOWED;
        if (running) return StartOutcome.ALREADY_RUNNING;
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) return StartOutcome.INVALID_DURATION;
        ticks.reset();
        tickOpen = false;
        requestedSeconds = seconds;
        this.notifier = notifier;
        startedAt = wallClock.get();
        redirectAtStart = ReadRedirect.enabled();
        togglesAtStart = ReadRedirect.toggles();
        redirectBase = ReadRedirect.lifetime();
        monitorEnabledBySession = false;
        monitorProblem = null;
        monitorBase = null;
        if (!WDCApiMonitor.enabled) {
            if (WDCApiMonitor.enable()) monitorEnabledBySession = true;
            else monitorProblem = "the API monitor is disabled in worlddata_core.json (monitor.allowed is false)";
        }
        monitorResetsAtStart = WDCApiMonitor.resets();
        if (monitorProblem == null) monitorBase = WDCApiMonitor.snapshot(Integer.MAX_VALUE);
        startNanos = nanoClock.getAsLong();
        deadlineNanos = startNanos + seconds * 1_000_000_000L;
        running = true;
        return StartOutcome.STARTED;
    }

    public boolean callerAttribution() {
        return monitorProblem == null;
    }

    public String callerProblem() {
        return monitorProblem;
    }

    public void onTickStart() {
        if (!running) return;
        tickStartNanos = nanoClock.getAsLong();
        tickOpen = true;
    }

    public void onTickEnd() {
        if (!running) return;
        long now = nanoClock.getAsLong();
        if (tickOpen) {
            ticks.record(now - tickStartNanos);
            tickOpen = false;
        }
        if (now >= deadlineNanos) finish(SessionResult.EndReason.COMPLETED, now);
    }

    public void onServerStopping() {
        if (running) finish(SessionResult.EndReason.SERVER_STOPPING, nanoClock.getAsLong());
    }

    public SessionResult stop() {
        return stop(null);
    }

    public SessionResult stop(Consumer<String> replyTo) {
        if (!running) return null;
        if (replyTo != null) notifier = replyTo;
        return finish(SessionResult.EndReason.STOPPED, nanoClock.getAsLong());
    }

    public Status status() {
        if (!running) {
            return new Status(false, 0.0, 0, false, ReadRedirect.enabled(), TickStats.Snapshot.EMPTY, lastFile, lastSummary);
        }
        double elapsed = (nanoClock.getAsLong() - startNanos) / 1_000_000_000.0;
        return new Status(true, Math.max(0.0, elapsed), requestedSeconds, redirectAtStart, ReadRedirect.enabled(),
                ticks.snapshot(), lastFile, lastSummary);
    }

    public Path lastFile() {
        return lastFile;
    }

    private SessionResult finish(SessionResult.EndReason reason, long nowNanos) {
        running = false;
        tickOpen = false;
        SessionResult result = build(reason, nowNanos);
        Path written = write(result);
        lastFile = written;
        lastSummary = result.ticks().summary();
        Consumer<String> target = notifier;
        notifier = null;
        announce(target, result, written);
        return result;
    }

    private SessionResult build(SessionResult.EndReason reason, long nowNanos) {
        CallerWindow callers = callers();
        boolean redirectNow = ReadRedirect.enabled();
        RedirectCounts window = ReadRedirect.lifetime().minus(redirectBase);
        boolean changed = ReadRedirect.toggles() != togglesAtStart;
        return new SessionResult(startedAt, wallClock.get(), requestedSeconds,
                Math.max(0L, nowNanos - startNanos) / 1_000_000_000.0, reason, redirectAtStart, redirectNow, changed,
                ticks.snapshot(), window, callers);
    }

    private CallerWindow callers() {
        if (monitorProblem != null) return CallerWindow.unavailable(monitorProblem);
        try {
            MonitorSnapshot end = WDCApiMonitor.snapshot(Integer.MAX_VALUE);
            String note = null;
            if (!end.enabled()) note = "the API monitor was switched off during the session, so later calls are missing";
            return CallerWindow.between(monitorBase, end, WDCApiMonitor.resets() != monitorResetsAtStart, note);
        } catch (RuntimeException failure) {
            LOGGER.warn("WorldData: could not read the API monitor for the session report", failure);
            return CallerWindow.unavailable("the API monitor could not be read: " + failure.getMessage());
        } finally {
            if (monitorEnabledBySession) WDCApiMonitor.disable();
            monitorEnabledBySession = false;
        }
    }

    private Path write(SessionResult result) {
        try {
            Path dir = directory.get();
            Files.createDirectories(dir);
            Path file = dir.resolve(SessionReport.fileName(result));
            int attempt = 1;
            String base = file.getFileName().toString();
            while (Files.exists(file)) {
                String name = base.substring(0, base.length() - ".txt".length()) + "-" + (++attempt) + ".txt";
                file = dir.resolve(name);
            }
            Files.write(file, SessionReport.lines(result), StandardCharsets.UTF_8);
            return file;
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("WorldData: could not write the session report", failure);
            return null;
        }
    }

    private void announce(Consumer<String> target, SessionResult result, Path file) {
        List<String> lines = List.of(
                "§a[WorldData] Test session finished (" + result.reason().label() + ").",
                "§7" + result.ticks().summary() + ", redirect " + (result.redirectChanged() ? "changed during the session"
                        : result.redirectAtStart() ? "ON" : "OFF") + ".",
                file == null ? "§cThe report file could not be written, see the server log."
                        : "§7Report: §f" + file);
        LOGGER.info("WorldData: test session finished ({}). {} Report: {}", result.reason().label(), result.ticks().summary(),
                file == null ? "not written" : file);
        if (target == null) return;
        try {
            for (String line : lines) target.accept(line);
        } catch (RuntimeException ignored) {
            LOGGER.debug("WorldData: the session notice could not be delivered");
        }
    }
}
