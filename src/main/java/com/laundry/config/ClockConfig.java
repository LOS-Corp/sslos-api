package com.laundry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * One UTC clock for business time (BR-30, NFR-11). Policies read time from it,
 * so tests can pin boundaries such as T−60, T+10 or second 290/310 of a hold.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
