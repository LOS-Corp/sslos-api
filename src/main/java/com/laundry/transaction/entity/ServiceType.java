package com.laundry.transaction.entity;

/**
 * Service type enumeration for SSLOS
 */
public enum ServiceType {
    SELF_SERVICE("Tự giặt"),
    LAUNDRY_SERVICE("Giặt ủi");

    private final String displayName;

    ServiceType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
