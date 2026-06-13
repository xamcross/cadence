package com.cadence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    // Pattern A: @ServiceConnection directly on the @Container field.
    // spring-boot-testcontainers (NOT in starter-test) activates this annotation.
    // Without spring-boot-testcontainers on the classpath, @ServiceConnection has no effect
    // and tests silently connect to localhost:27017 — which is absent in CI.
    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired
    protected MongoTemplate mongoTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void mongoRoundTrip() {
        Map<String, String> doc = Map.of("_class", "test", "value", "cadence-ok");
        mongoTemplate.save(doc, "test_roundtrip");
        long count = mongoTemplate.getCollection("test_roundtrip").countDocuments();
        assertThat(count).isGreaterThanOrEqualTo(1);
        mongoTemplate.dropCollection("test_roundtrip");
    }
}
