package com.minicard.infrastructure.time;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {

    @Bean
    public Clock clock() {
        // Domain timestamps use UTC so persistence and distributed processing do
        // not depend on the local timezone of an application instance.
        return Clock.systemUTC();
    }
}
