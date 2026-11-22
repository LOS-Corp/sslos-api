package com.laundry.kiosk;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Tells kiosk accounts (V72) apart from people. A booking made at the kiosk belongs to the kiosk account, but the
 * money is the walk-in customer's: anything owed back must reach that person, never the kiosk's wallet.
 */
@Component
@RequiredArgsConstructor
public class KioskAccounts {

    private final JdbcTemplate jdbc;

    public boolean isKiosk(UUID userId) {
        if (userId == null) {
            return false;
        }
        Boolean kiosk = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                               WHERE ur.user_id = ? AND r.name = 'KIOSK')
                """, Boolean.class, userId);
        return Boolean.TRUE.equals(kiosk);
    }
}
