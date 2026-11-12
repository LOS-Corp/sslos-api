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

    /** Owner and Admin run the whole system; Staff work in the store but cannot change its setup. */
    public boolean isChainWide() {
        return has(Role.OWNER) || has(Role.ADMIN);
    }

    /** Admin, Owner or Staff: people who run the store. */
    public boolean isOperator() {
        return isChainWide() || has(Role.STAFF);
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
