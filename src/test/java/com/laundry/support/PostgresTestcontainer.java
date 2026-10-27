package com.laundry.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container for the whole test JVM. Flyway builds the schema from
 * the real migrations, so constraints, triggers and JSONB behave exactly as in
 * production (NFR-12, NFR-15).
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("sslos_test")
                    .withUsername("sslos")
                    .withPassword("sslos");

    static {
        POSTGRES.start();
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }
}
