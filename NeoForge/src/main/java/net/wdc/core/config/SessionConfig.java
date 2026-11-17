package net.wdc.core.config;

public final class SessionConfig {

    public boolean allowed = true;

    public SessionConfig copy() {
        SessionConfig copy = new SessionConfig();
        copy.allowed = allowed;
        return copy;
    }
}
