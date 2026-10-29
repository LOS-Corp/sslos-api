package com.laundry.shared.security;

import java.util.Locale;
import java.util.Optional;

/**
 * The four roles of SSLOS (BR-01, decision 27/10/2026). There is no MANAGER:
 * the Owner manages every branch and assigns Staff to branches.
 */
public enum Role {
    ADMIN,
    OWNER,
    STAFF,
    CUSTOMER;

    private static final String PREFIX = "ROLE_";

    public String authority() {
        return PREFIX + name();
    }

    /** Parses {@code "OWNER"} or {@code "ROLE_OWNER"}; unknown names (e.g. a retired role) yield empty. */
    public static Optional<Role> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String name = value.trim().toUpperCase(Locale.ROOT);
        if (name.startsWith(PREFIX)) {
            name = name.substring(PREFIX.length());
        }
        for (Role role : values()) {
            if (role.name().equals(name)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
