package com.laundry.shared.security;

/**
 * {@code @PreAuthorize} expressions of the permission matrix (task/09_PHAN_QUYEN_4_VAI_TRO.md).
 * Using these constants keeps every controller on the same vocabulary.
 */
public final class Roles {

    public static final String CUSTOMER = "hasRole('CUSTOMER')";
    public static final String STAFF = "hasRole('STAFF')";
    public static final String OWNER = "hasRole('OWNER')";
    public static final String ADMIN = "hasRole('ADMIN')";
    public static final String OWNER_OR_ADMIN = "hasAnyRole('OWNER','ADMIN')";
    /** People who run a branch day to day (branch scope is checked in the service for STAFF). */
    public static final String OPERATOR = "hasAnyRole('STAFF','OWNER')";
    public static final String OPERATOR_OR_ADMIN = "hasAnyRole('STAFF','OWNER','ADMIN')";

    private Roles() {
    }
}
