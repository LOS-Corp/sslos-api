package com.laundry.shared.security;

import java.util.Locale;
import java.util.Optional;

/**
 * The roles of SSLOS (BR-01, decision 27/10/2026). There is no MANAGER: the Owner runs the store and manages Staff.
 * KIOSK (V72) is the in-store self-service screen: a device account the Owner creates, which books and pays for
 * walk-in customers who have no account. Declared last, so it never outranks a person's role.
 */
public enum Role {
    ADMIN,
    OWNER,
    STAFF,
    CUSTOMER,
    KIOSK;

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
