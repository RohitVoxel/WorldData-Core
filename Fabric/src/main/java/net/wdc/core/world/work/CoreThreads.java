package net.wdc.core.world.work;

import java.util.concurrent.ThreadFactory;

public final class CoreThreads {

    private CoreThreads() {}

    public static final class WorkerThread extends Thread {

        private WorkerThread(Runnable task, String name, int priority) {
            super(task, name);
            setDaemon(true);
            setPriority(priority);
        }
    }

    public static ThreadFactory workerFactory(String name, int priority) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (priority < Thread.MIN_PRIORITY || priority > Thread.MAX_PRIORITY) {
            throw new IllegalArgumentException("priority out of range");
        }
        return task -> new WorkerThread(task, name, priority);
    }

    public static boolean isWorker() {
        return Thread.currentThread() instanceof WorkerThread;
    }

    public static void requireNotWorker(String action) {
        Thread current = Thread.currentThread();
        if (current instanceof WorkerThread) {
            throw new IllegalStateException("WorldData Core: " + action + " must not run on worker thread " + current.getName());
        }
    }
}
