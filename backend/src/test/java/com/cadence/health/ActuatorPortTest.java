package com.cadence.health;

import com.cadence.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ActuatorPortTest extends BaseIntegrationTest {

    @Value("${management.server.port:18081}")
    private int managementPort;

    @Test
    void actuatorHealthOnManagementPortReturns200() {
        TestRestTemplate rest = new TestRestTemplate();
        ResponseEntity<String> response = rest.getForEntity(
            "http://localhost:" + managementPort + "/actuator/health",
            String.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    void actuatorHealthOnPublicPortReturnsNon200() {
        TestRestTemplate rest = new TestRestTemplate();
        ResponseEntity<String> response = rest.getForEntity(
            "http://localhost:8080/actuator/health",
            String.class
        );
        // Spring Security blocks the public port with 401 or 403 (not 200)
        assertThat(response.getStatusCode().value()).isNotEqualTo(200);
    }
}
