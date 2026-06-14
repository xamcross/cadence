package com.cadence.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * Supplies a single {@link MutableClock} bean (which IS-A {@link java.time.Clock}) so auth time
 * logic is deterministic. The production {@code ClockConfig.clock()} backs off via its
 * {@code @ConditionalOnMissingBean(Clock)} since a Clock bean now exists. Exactly ONE Clock bean —
 * defining a second {@code @Primary Clock} alongside this caused a NoUniqueBeanDefinitionException
 * (two primaries assignable to Clock).
 */
@TestConfiguration
public class AuthTestConfig {

    public static final Instant FIXED_START = Instant.parse("2026-06-14T12:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(FIXED_START);
    }
}
