package com.cadence.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides a {@link Clock} bean so time-sensitive logic (session skew/expiry, lockout windows,
 * sliding renewal) is deterministically testable (research D11). Tests override this bean with a
 * mutable Clock. {@code @ConditionalOnMissingBean} lets a test context supply its own.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
