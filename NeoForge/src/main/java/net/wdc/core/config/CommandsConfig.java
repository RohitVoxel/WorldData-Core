package net.wdc.core.config;

public final class CommandsConfig {

    public static final int DEFAULT_PERMISSION_LEVEL = 2;
    public static final int DEFAULT_INFO_PERMISSION_LEVEL = 0;
    public static final int MAX_LEVEL = 4;

    public int permissionLevel = DEFAULT_PERMISSION_LEVEL;
    public int infoPermissionLevel = DEFAULT_INFO_PERMISSION_LEVEL;

    public void validate() {
        if (permissionLevel < 0 || permissionLevel > MAX_LEVEL) {
            throw new IllegalArgumentException("permissionLevel must be within 0.." + MAX_LEVEL);
        }
        if (infoPermissionLevel < 0 || infoPermissionLevel > permissionLevel) {
            throw new IllegalArgumentException("infoPermissionLevel must be within 0..permissionLevel");
        }
    }

    public boolean clamp() {
        boolean changed = false;
        int level = Math.max(0, Math.min(MAX_LEVEL, permissionLevel));
        if (level != permissionLevel) { permissionLevel = level; changed = true; }
        int info = Math.max(0, Math.min(permissionLevel, infoPermissionLevel));
        if (info != infoPermissionLevel) { infoPermissionLevel = info; changed = true; }
        return changed;
    }

    public CommandsConfig copy() {
        CommandsConfig copy = new CommandsConfig();
        copy.permissionLevel = permissionLevel;
        copy.infoPermissionLevel = infoPermissionLevel;
        return copy;
    }
}
