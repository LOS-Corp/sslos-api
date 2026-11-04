package com.laundry.architecture;

import com.laundry.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Rules on the migrated schema itself. */
@IntegrationTest
class SchemaRulesIT {

    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("BR-30 / NFR-11: no column stores a timestamp without its time zone")
    void everyTimestampHasATimeZone() {
        List<String> columns = jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND data_type = 'timestamp without time zone'
                  AND table_name <> 'flyway_schema_history'
                """, String.class);
        assertThat(columns).isEmpty();
    }

    @Test
    @DisplayName("BR-20: self-service ironing is not on sale")
    void noSelfServiceIroning() {
        assertThat(jdbc.queryForObject("SELECT active FROM booking_service_definitions WHERE code = 'IRON_ONLY'",
                Boolean.class)).isFalse();
    }
}
