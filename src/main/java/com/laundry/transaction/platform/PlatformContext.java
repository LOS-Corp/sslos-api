package com.laundry.transaction.platform;

/**
 * Platform context holder using ThreadLocal
 * Used to pass platform information through the request lifecycle
 */
public class PlatformContext {
    
    private static final ThreadLocal<Platform> CURRENT_PLATFORM = new ThreadLocal<>();
    
    public static void setPlatform(Platform platform) {
        CURRENT_PLATFORM.set(platform);
    }
    
    public static Platform getPlatform() {
        return CURRENT_PLATFORM.get();
    }
    
    public static void clear() {
        CURRENT_PLATFORM.remove();
    }
    
    public static Platform requirePlatform() {
        Platform platform = CURRENT_PLATFORM.get();
        if (platform == null) {
            throw new IllegalStateException("Platform context not set");
        }
        return platform;
    }
}
