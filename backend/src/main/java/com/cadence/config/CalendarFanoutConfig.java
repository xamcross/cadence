package com.cadence.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bounded fixed-size executor for the panel free/busy fan-out (F10, research D4 / plan-review Backend).
 * Wrapped in {@link DelegatingSecurityContextExecutorService} so the {@code SecurityContext} propagates to
 * worker threads; the MDC correlation id is copied per task at the submit site in {@code AvailabilityService}.
 * Declared {@code destroyMethod = "shutdown"} so the pool is torn down on context close (a plain
 * {@code @Bean ExecutorService} is NOT closed by {@code server.shutdown=graceful}, which only drains the
 * web container). In-request fan-out tasks are joined before the response, so graceful drain covers them.
 * This is a bounded in-process pool — NOT a broker (C2).
 */
@Configuration
public class CalendarFanoutConfig {

    @Bean(name = "calendarFanoutExecutor", destroyMethod = "shutdown")
    public ExecutorService calendarFanoutExecutor(CalendarApiProperties props) {
        int parallelism = Math.max(1, props.getFreebusyParallelism());
        return new DelegatingSecurityContextExecutorService(Executors.newFixedThreadPool(parallelism));
    }
}
