package com.cadence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    // Singleton container pattern: the container is started once in the static initializer
    // and is NEVER stopped (Ryuk reaps it at JVM exit). This is REQUIRED for a multi-class
    // suite. With the @Testcontainers/@Container lifecycle, the container is stopped and
    // restarted per test class (new mapped port each time), but Spring caches the
    // ApplicationContext by configuration — so a second class reusing a cached context would
    // point at the dead port and fail with MongoTimeoutException. Starting once keeps the
    // mapped port stable across every cached context.
    //
    // @ServiceConnection (from spring-boot-testcontainers, NOT in starter-test) wires the
    // container's URI into Spring. Without that dependency the annotation is a no-op and
    // tests silently connect to localhost:27017 — absent in CI.
    @ServiceConnection
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    static {
        mongo.start();
    }

    @Autowired
    protected MongoTemplate mongoTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void mongoRoundTrip() {
        // Must be a mutable map: mongoTemplate.save() injects the generated _id via Map.put(),
        // which throws UnsupportedOperationException on the immutable Map.of(...).
        Map<String, String> doc = new HashMap<>();
        doc.put("value", "cadence-ok");
        mongoTemplate.save(doc, "test_roundtrip");
        long count = mongoTemplate.getCollection("test_roundtrip").countDocuments();
        assertThat(count).isGreaterThanOrEqualTo(1);
        mongoTemplate.dropCollection("test_roundtrip");
    }
}
