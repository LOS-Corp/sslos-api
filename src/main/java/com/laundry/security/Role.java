package com.laundry.security;

/** Vai trò (§2). Khách vãng lai không có tài khoản; kiosk xác thực bằng khóa riêng (ROLE_KIOSK). */
public enum Role {
    CUSTOMER, STAFF, OWNER, ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
