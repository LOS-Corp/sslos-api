package com.laundry.config;

import com.laundry.machine.domain.CheckInWindowPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Business parameters of SRS 5.1 ("single source of values"); defaults are the adopted values. */
@Configuration
@EnableConfigurationProperties(BookingProperties.class)
public class BookingPolicyConfig {

    @Bean
    CheckInWindowPolicy checkInWindowPolicy(BookingProperties props) {
        return new CheckInWindowPolicy(props.checkInOpensBefore(), props.checkInClosesAfter(), props.holdDuration());
    }
}
