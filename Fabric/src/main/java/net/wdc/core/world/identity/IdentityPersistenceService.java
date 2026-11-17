package net.wdc.core.world.identity;

import net.wdc.core.world.work.CoreThreads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class IdentityPersistenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldData/Identity");
    private static final int QUEUE_CAPACITY = 8;
    private static final long SHUTDOWN_GRACE_MS = 2000L;
    private static final long BLOCKING_SAVE_TIMEOUT_MS = 3000L;

    private final IdentityFileStore fileStore = new IdentityFileStore();
    private final ThreadPoolExecutor ioExecutor;

    private final AtomicReference<LoadedIdentitySnapshot> loadedResult = new AtomicReference<>();
    private final AtomicLong currentLoadGeneration = new AtomicLong();
    private volatile long latestRequestedGeneration = -1L;

    private final AtomicLong writesCompleted = new AtomicLong();
    private final AtomicLong writesDiscarded = new AtomicLong();
    private final AtomicLong loadsCompleted = new AtomicLong();
    private final AtomicLong loadsSuperseded = new AtomicLong();

    private record LoadedIdentitySnapshot(String dimensionKey, List<IdentityPositionSnapshot> positions) {}

    public IdentityPersistenceService() {
        this.ioExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                CoreThreads.workerFactory("WorldData-Identity-IO", Thread.NORM_PRIORITY),
                this::onRejected
        );
    }

    public void save(String dimensionKey, List<IdentityPositionSnapshot> positions) {
        if (dimensionKey == null) return;
        ioExecutor.execute(() -> {
            fileStore.write(dimensionKey, positions);
            writesCompleted.incrementAndGet();
        });
    }

    public void saveBlocking(String dimensionKey, List<IdentityPositionSnapshot> positions) {
        if (dimensionKey == null) return;

        CountDownLatch done = new CountDownLatch(1);
        Runnable task = () -> {
            try {
                fileStore.write(dimensionKey, positions);
                writesCompleted.incrementAndGet();
            } finally {
                done.countDown();
            }
        };

        try {
            ioExecutor.execute(task);
        } catch (RejectedExecutionException executorAlreadyShutDown) {
            fileStore.write(dimensionKey, positions);
            writesCompleted.incrementAndGet();
            return;
        }

        try {
            if (!done.await(BLOCKING_SAVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                LOGGER.debug("WorldData: blocking identity save timed out waiting for the IO thread");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void requestLoad(String dimensionKey) {
        long generation = currentLoadGeneration.incrementAndGet();
        latestRequestedGeneration = generation;

        ioExecutor.execute(() -> {
            Optional<List<IdentityPositionSnapshot>> result = fileStore.read(dimensionKey);

            if (generation != latestRequestedGeneration) {
                loadsSuperseded.incrementAndGet();
                return;
            }

            result.ifPresent(positions -> {
                loadedResult.set(new LoadedIdentitySnapshot(dimensionKey, positions));
                loadsCompleted.incrementAndGet();
            });
        });
    }

    public Optional<List<IdentityPositionSnapshot>> pollLoadedResult(String dimensionKey) {
        LoadedIdentitySnapshot current = loadedResult.get();
        if (current == null || !current.dimensionKey().equals(dimensionKey)) return Optional.empty();
        loadedResult.compareAndSet(current, null);
        return Optional.of(current.positions());
    }

    public void cancelPendingLoad() {
        latestRequestedGeneration = currentLoadGeneration.incrementAndGet();
        loadedResult.set(null);
    }

    public IdentityPersistenceDiagnostics diagnostics() {
        return new IdentityPersistenceDiagnostics(
                ioExecutor.getQueue().size(),
                writesCompleted.get(),
                writesDiscarded.get(),
                loadsCompleted.get(),
                fileStore.corruptedReadsDetected(),
                !ioExecutor.isShutdown()
        );
    }

    public void shutdown() {
        ioExecutor.shutdown();
        try {
            ioExecutor.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        fileStore.close();
        try {
            if (!ioExecutor.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                LOGGER.debug("WorldData: identity persistence shutdown grace period elapsed with work still queued; stopping now");
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void onRejected(Runnable runnable, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            throw new RejectedExecutionException("WorldData identity IO executor is shut down");
        }

        Runnable discarded = executor.getQueue().poll();
        if (discarded != null) {
            writesDiscarded.incrementAndGet();
            LOGGER.debug("WorldData: discarded oldest pending identity persistence task (queue full)");
        }
        executor.execute(runnable);
    }
}
