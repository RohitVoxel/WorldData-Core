package net.wdc.core.world.spill;

import net.wdc.core.world.work.CoreThreads;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class WDCIoExecutor {
    public static final int DEFAULT_QUEUE_CAPACITY = 256;
    public static final int MAX_QUEUE_CAPACITY = 65_536;

    private final ThreadPoolExecutor executor;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile int queueCapacity;

    public WDCIoExecutor() {
        this(DEFAULT_QUEUE_CAPACITY);
    }

    public WDCIoExecutor(int queueCapacity) {
        setQueueCapacity(queueCapacity);
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue,
                CoreThreads.workerFactory("WorldData-Spill-IO", Thread.NORM_PRIORITY - 1));
    }

    public void setQueueCapacity(int capacity) {
        if (capacity < 1 || capacity > MAX_QUEUE_CAPACITY) {
            throw new IllegalArgumentException("queueCapacity must be within 1.." + MAX_QUEUE_CAPACITY);
        }
        this.queueCapacity = capacity;
    }

    public int queueCapacity() { return queueCapacity; }

    public boolean trySubmit(Runnable job) {
        if (job == null) throw new IllegalArgumentException("job must not be null");
        if (executor.getQueue().size() >= queueCapacity) return false;
        try {
            executor.execute(() -> {
                inFlight.incrementAndGet();
                try { job.run(); } finally { inFlight.decrementAndGet(); }
            });
            return true;
        } catch (RejectedExecutionException saturated) {
            return false;
        }
    }

    public int queueDepth() { return executor.getQueue().size(); }
    public int inFlight() { return inFlight.get(); }
    public boolean isShutdown() { return executor.isShutdown(); }

    public void shutdown() { executor.shutdown(); }

    public boolean awaitQuiescence(long millis) {
        shutdown();
        try { return executor.awaitTermination(millis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
}
