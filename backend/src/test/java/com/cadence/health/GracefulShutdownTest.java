package com.cadence.health;

import com.cadence.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GracefulShutdownTest extends BaseIntegrationTest {

    @Autowired
    private ServerProperties serverProperties;

    @Autowired
    private LifecycleProperties lifecycleProperties;

    @Test
    void gracefulShutdownIsConfigured() {
        assertThat(serverProperties.getShutdown())
            .isEqualTo(org.springframework.boot.web.server.Shutdown.GRACEFUL);
    }

    @Test
    void shutdownTimeoutIsAtLeast30Seconds() {
        Duration timeout = lifecycleProperties.getTimeoutPerShutdownPhase();
        assertThat(timeout).isGreaterThanOrEqualTo(Duration.ofSeconds(30));
    }
}
