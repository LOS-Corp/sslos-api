package com.laundry.shared.security;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, taken from the JWT only (BR-01: identity never comes from the request body).
 * Declare it as a controller parameter; {@link CurrentUserArgumentResolver} fills it.
 */
public record CurrentUser(UUID id, Set<Role> roles) {

    public CurrentUser {
        roles = roles.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(roles));
    }

    public static CurrentUser of(UUID id, Role... roles) {
        return new CurrentUser(id, Set.of(roles));
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    /** Owner and Admin see every branch; Staff only their assigned branches. */
    public boolean isChainWide() {
        return has(Role.OWNER) || has(Role.ADMIN);
    }

    /** Role reported in audit entries: the highest one the account holds. */
    public Role primaryRole() {
        for (Role role : Role.values()) {
            if (roles.contains(role)) {
                return role;
            }
        }
        return Role.CUSTOMER;
    }
}
