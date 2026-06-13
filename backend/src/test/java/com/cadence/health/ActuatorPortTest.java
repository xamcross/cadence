package com.cadence.health;

import com.cadence.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

// RANDOM_PORT (not DEFINED_PORT): the main application port is bound to a random free port so a
// parallel process holding 8080 cannot break this context. The management port stays fixed at
// 18081 (application-test.yml) — already chosen to avoid conflicts.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorPortTest extends BaseIntegrationTest {

    // With RANDOM_PORT Spring randomises BOTH server.port and management.server.port to 0; the
    // actual assigned management port is published as `local.management.port` (NOT
    // `management.server.port`, which stays 0). Reading the wrong property yields port 0.
    @Value("${local.management.port}")
    private int managementPort;

    @LocalServerPort
    private int mainPort;

    @Test
    void actuatorHealthOnManagementPortReturns200() {
        ResponseEntity<String> response = new TestRestTemplate().getForEntity(
            "http://localhost:" + managementPort + "/actuator/health",
            String.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    void actuatorIsNotAccessibleOnPublicPort() {
        // Actuator endpoints are served ONLY on the management port. On the public port, with a
        // separate management port the @Order(1) actuator chain does not intercept /actuator/**,
        // so the @Order(2) authenticated() catch-all denies the request with 403. That is the
        // real, deterministic behaviour (verified, not assumed) and is a STRONGER posture than a
        // 404 — it does not disclose whether the endpoint exists. The contract is reconciled to
        // this in management-endpoints.md. The essential guarantee: actuator is never served
        // (never 200) on the public port.
        ResponseEntity<String> response = new TestRestTemplate().getForEntity(
            "http://localhost:" + mainPort + "/actuator/health",
            String.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void securedApplicationEndpointRejectsUnauthenticatedRequestOnPublicPort() {
        // Proves the @Order(2) authenticated() chain actually guards application routes: an
        // unauthenticated request to a real endpoint (the test-profile slow stub) is rejected
        // before reaching the handler, never returning 200. This is the assertion that the
        // public-port actuator test cannot make (404 there is routing, not security).
        ResponseEntity<String> response = new TestRestTemplate().getForEntity(
            "http://localhost:" + mainPort + "/api/internal/slow",
            String.class
        );
        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }
}
