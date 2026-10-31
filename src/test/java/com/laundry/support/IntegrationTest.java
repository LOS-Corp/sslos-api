package com.laundry.support;

import com.laundry.mqtt.service.MqttPublisher;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Spring Boot integration test on real PostgreSQL with the MQTT publisher mocked,
 * so no broker is needed. All integration tests share one cached context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestcontainer.class)
@MockitoBean(types = MqttPublisher.class)
public @interface IntegrationTest {
}
