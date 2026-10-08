package com.laundry.transaction.platform;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Factory for resolving platform-specific purchase service
 */
@Component
public class PlatformServiceFactory {

    private final Map<Platform, PlatformPurchaseService> platformServices;

    public PlatformServiceFactory(List<PlatformPurchaseService> services) {
        this.platformServices = new EnumMap<>(Platform.class);
        for (PlatformPurchaseService service : services) {
            this.platformServices.put(service.getPlatform(), service);
        }
    }

    /**
     * Get platform service by platform type
     */
    public PlatformPurchaseService getService(Platform platform) {
        PlatformPurchaseService service = platformServices.get(platform);
        if (service == null) {
            throw new IllegalArgumentException("Unsupported platform: " + platform);
        }
        return service;
    }

    /**
     * Get platform service by platform name (case-insensitive)
     */
    public PlatformPurchaseService getService(String platformName) {
        try {
            Platform platform = Platform.valueOf(platformName.toUpperCase());
            return getService(platform);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported platform: " + platformName);
        }
    }
}
