package net.wdc.core.api.internal;

public final class WorldAuthority {

    private static volatile boolean serverRunning;
    private static volatile Thread serverThread;

    private WorldAuthority() {}

    public static void serverStarted(Thread owner) {
        serverThread = owner;
        serverRunning = true;
    }

    public static void serverStopped() {
        serverRunning = false;
        serverThread = null;
    }

    public static boolean isServerAuthoritative() {
        return serverRunning;
    }

    public static boolean clientMayObserve() {
        return !serverRunning;
    }

    public static boolean isServerThread() {
        Thread owner = serverThread;
        return owner != null && owner == Thread.currentThread();
    }

    public static void reset() {
        serverRunning = false;
        serverThread = null;
    }
}
