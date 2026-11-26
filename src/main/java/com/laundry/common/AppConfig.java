package com.laundry.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableAsync
public class AppConfig {

    /** Mọi chỗ lấy giờ đều qua Clock để test được. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("LaundryOS API").version("2.0")
                        .description("Đặt lượt giặt/sấy, kiosk, thanh toán, ví, giặt hộ và thiết bị IoT"))
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("kiosk", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Kiosk-Key")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
