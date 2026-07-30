package com.cadence.billing;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 032 -- in-test Freemius API stub (the StubLever pattern; WireMock is banned). JVM-lifetime
 * singleton: NEVER stopped in an @AfterAll, or a second test class reusing the cached Spring
 * context hits a dead port. Serves GET /v1/products/{pid}/licenses/{lid}.json from programmed
 * bodies. Bodies deliberately include SENTINEL-marked unparsed fields (user_email, secret_key)
 * so PII/minimization assertions are non-circular. Shape is integration-pending (F40/F41 rule).
 */
public final class StubFreemius {

    private final HttpServer server;
    private final Map<String, String> licenses = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int forcedStatus = 0;

    public StubFreemius() {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            int status;
            String payload;
            if (forcedStatus != 0) {
                status = forcedStatus;
                payload = "{\"error\":\"SENTINEL-STUB-ERROR-BODY\"}";
            } else {
                String licenseId = path.substring(path.lastIndexOf('/') + 1).replace(".json", "");
                String body = licenses.get(licenseId);
                status = body == null ? 404 : 200;
                payload = body == null ? "{\"error\":\"SENTINEL-STUB-NOT-FOUND\"}" : body;
            }
            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() { return "http://localhost:" + server.getAddress().getPort(); }

    public void programLicense(String licenseId, String json) { licenses.put(licenseId, json); }

    public void programStatus(int status) { this.forcedStatus = status; }

    public int requestCount() { return requests.get(); }

    public String lastAuthHeader() { return lastAuth.get(); }

    public void reset() {
        licenses.clear();
        forcedStatus = 0;
        requests.set(0);
        lastAuth.set(null);
    }
}
