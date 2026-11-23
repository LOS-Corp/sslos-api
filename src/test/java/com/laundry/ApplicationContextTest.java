package com.laundry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Khởi động toàn bộ ứng dụng trên PostgreSQL thật: Flyway chạy V1, Hibernate kiểm khớp từng cột (ddl-auto=validate).
 * Cần một PostgreSQL trống (CI: service postgres; máy dev: SPRING_DATASOURCE_URL trỏ tới DB test).
 */
@SpringBootTest(properties = {"app.mqtt.enabled=false", "app.jobs.enabled=false"})
class ApplicationContextTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void schemaMigratesAndEntitiesMatch() {
        assertThat(jdbc.queryForObject("select count(*) from store_settings", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = 'public'",
                Integer.class)).isGreaterThan(40);
    }
}
