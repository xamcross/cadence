# Contract: Management Endpoints

**Feature**: 001-project-scaffold  
**Scope**: Spring Boot Actuator endpoints — health and metrics  
**Audience**: Internal operations/DevOps only; never exposed to the public internet

---

## Access Control

| Port | Health | Metrics | Application traffic |
|---|---|---|---|
| Management port (default 8081) | ✅ 200 OK | ✅ 200 OK | ❌ Not routed here |
| Public application port (default 8080) | ❌ 403 | ❌ 403 | ✅ All /api/** routes |

**Note on the public-port status (verified by `ActuatorPortTest`):** with a separate management
port, Spring Security's main `authenticated()` chain (`@Order(2)`) denies `/actuator/**` on the
public port with **403 Forbidden**. This is stronger than a 404 — it does not disclose whether the
endpoint exists. The essential guarantee is that actuator is never *served* (never 200) on the
public port.

The management port MUST NOT be mapped to the public Fly.io hostname. It is accessible only from within the Fly private network (for internal health checks) or via `fly proxy`.

---

## Endpoint: GET /actuator/health

**Port**: Management (8081)  
**Auth**: None — management port is network-restricted, not auth-restricted  
**Purpose**: Liveness/readiness probe for Fly.io health checks and deployment gate

### Response: Healthy

```
HTTP 200 OK
Content-Type: application/vnd.spring-boot.actuator.v3+json

{
  "status": "UP",
  "components": {
    "mongo": { "status": "UP" },
    "diskSpace": { "status": "UP" }
  }
}
```

### Response: Degraded (MongoDB unreachable)

```
HTTP 503 Service Unavailable
Content-Type: application/vnd.spring-boot.actuator.v3+json

{
  "status": "DOWN",
  "components": {
    "mongo": { "status": "DOWN", "details": { "error": "..." } }
  }
}
```

### Contract Rules

- Response time MUST be < 200 ms under normal load.
- If MongoDB is unreachable, the health endpoint MUST return 503 (not 200 with a degraded status). Fly.io uses HTTP status to determine whether to route traffic.
- The `details` field in degraded responses MUST NOT contain connection string values or credentials.
- The health endpoint MUST NOT be served on the public application port (8080). In practice Spring Security's main `authenticated()` chain denies it with 403 (see the access-control note above); a 404 or connection-refused would also satisfy the intent. The hard requirement is that it never returns 200 on the public port.

### Fly.io Health Check Configuration (`fly.toml`)

**IMPORTANT**: The legacy `[[services.http_checks]]` format is for Fly Nomad (v1) apps and is silently ignored on Machines-era apps. Use the Machines-era format below. Using the wrong format means the health check never fires and Fly never gates deployments on application health.

```toml
[http_service]
  internal_port = 8080          # public application port (NOT 8081)
  force_https = true
  auto_stop_machines = false
  auto_start_machines = false

  [[http_service.checks]]
    grace_period = "60s"
    interval = "10s"
    method = "GET"
    path = "/actuator/health"
    timeout = "5s"
    port = 8081                 # management port for health check

[build]
  dockerfile = "backend/Dockerfile"   # Dockerfile lives inside backend/, not repo root
  build-context = "backend"

kill_timeout = "35s"    # must exceed spring.lifecycle.timeout-per-shutdown-phase=30s
                        # default 5s would SIGKILL mid-drain, defeating graceful shutdown

[env]
  SERVER_PORT = "8080"
  MANAGEMENT_SERVER_PORT = "8081"
  SPRING_PROFILES_ACTIVE = "production"
  # Secrets (never inline here): fly secrets set MONGODB_URI="..." JWT_SECRET="..." etc.
```

---

## Endpoint: GET /actuator/metrics

**Port**: Management (8081)  
**Auth**: None (network-restricted)  
**Purpose**: JVM and application metrics for operational observability

### Response

```
HTTP 200 OK
Content-Type: application/vnd.spring-boot.actuator.v3+json

{
  "names": [
    "jvm.memory.used",
    "jvm.gc.pause",
    "http.server.requests",
    "process.uptime",
    "scheduler.checkpoint.replayed",
    "scheduler.deadletter.count"
  ]
}
```

### Endpoint: GET /actuator/metrics/{name}

```
HTTP 200 OK

{
  "name": "http.server.requests",
  "measurements": [
    { "statistic": "COUNT", "value": 1234 },
    { "statistic": "TOTAL_TIME", "value": 5.678 }
  ],
  "availableTags": [
    { "tag": "uri", "values": ["/api/internal/workspace", "/api/candidate/schedule"] },
    { "tag": "status", "values": ["200", "401", "404"] }
  ]
}
```

### Contract Rules

- The metrics endpoint MUST return 404 on the public port (8080).
- Custom metrics `scheduler.checkpoint.replayed` (Counter) and `scheduler.deadletter.count` (Counter) MUST be registered at startup and increment as events occur.
- Metric tags MUST NOT include candidate email, name, or any PII value. The `uri` tag uses route templates (e.g., `/api/candidate/schedule/{token}`) — the `{token}` value is a path variable and MUST NOT appear in metric tags.

---

## Endpoint: GET /actuator/metrics/{name} — 404 on Unknown Metric

```
HTTP 404 Not Found

{
  "description": "No metric with name 'nonexistent.metric' found"
}
```

---

## Configuration (`application.yml`)

```yaml
management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health, metrics
  endpoint:
    health:
      show-details: always
      show-components: always
  health:
    mongo:
      enabled: true
```

The `exposure.include` list MUST be explicit — `include: "*"` is prohibited because it exposes `env`, `configprops`, and `heapdump` endpoints that could leak secrets or memory.

---

## Spring Security Interaction

**Critical**: When `spring-boot-starter-security` is on the classpath (which it is in this project), Spring Boot 3.x's auto-configured `SecurityFilterChain` secures ALL endpoints by default — including Actuator endpoints on the management port. Without an explicit security override, all `GET /actuator/**` requests will return `401 Unauthorized`, causing the `ActuatorPortTest` to fail and the Fly.io health check to return 401 (treated as unhealthy, preventing deployment).

**Required configuration**: A dedicated `SecurityFilterChain` bean that permits Actuator endpoints on the management port:

```java
@Bean
@Order(1)
SecurityFilterChain managementSecurityChain(HttpSecurity http) throws Exception {
    return http
        .securityMatcher(new AntPathRequestMatcher("/actuator/**"))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(csrf -> csrf.disable())
        .build();
}
```

This bean must be `@Order(1)` (highest precedence) so it matches before the main application security chain. The management port's network isolation (not exposed to the public internet via Fly.io) provides the effective access control; the Spring Security layer can safely `permitAll()` for Actuator paths.
