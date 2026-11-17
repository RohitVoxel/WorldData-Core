package net.wdc.core.api;

public final class WDCApiVersion {

    public static final int MAJOR = 1;
    public static final int MINOR = 0;

    private WDCApiVersion() {}

    public static String asString() {
        return MAJOR + "." + MINOR;
    }
}
