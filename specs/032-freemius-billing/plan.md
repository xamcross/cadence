# Freemius Billing (032) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Free/Team plans to Cadence, sold through Freemius (merchant of record), with a hosted-checkout purchase flow, claim-on-return license binding, HMAC-verified webhooks, a nightly reconciliation sweep, and feature gates on ATS integrations, no-show defense, and SLA nudges.

**Architecture:** One PII-free `workspaceEntitlements` document per upgraded workspace (absence = Free). All Freemius access sits behind a `BillingProvider` seam (raw `RestClient`, explicit-field JSON, no SDK). Webhooks are treated as pokes — truth is always re-fetched from the Freemius API. Gates are enforced at four initiation points only; downgrades never delete data.

**Tech Stack:** Java 21 / Spring Boot 3.3.5, MongoDB (Mongock migrations, Testcontainers `mongo:7`), Angular 17 standalone + signals, Jasmine/Karma EdgeHeadless.

## Global Constraints

- Branch: `032-freemius-billing` (exists; spec committed). Spec: `specs/032-freemius-billing/spec.md`.
- Backend tests (run from `backend/`, Git Bash): `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests '<pattern>'`. Never download toolchains, browsers, or dependencies (zero-download rule). WireMock is banned — provider stubs are in-test JDK `HttpServer` JVM-lifetime singletons, never stopped.
- Frontend tests (run from `frontend/`): `npx ng test --watch=false --include='<glob>'` (EdgeHeadless).
- No new runtime dependency of any kind (constitution). No Freemius SDK.
- Mongock: next free changeset is **order "024"** (023 is the highest applied). Changesets are append-only; native driver `createIndex`/targeted `dropIndex` only, never `indexOps(...)`/`dropIndexes()`. Pure-ASCII Java sources (use `--`, not em-dashes, in changeset javadoc).
- Test cleanup: `mongoTemplate.remove(new Query(), Type.class)` — **never** `dropCollection` (drops Mongock indexes).
- Secrets only via env placeholders in `application.yml` (`${UPPER_SNAKE:default}`) bound to `@ConfigurationProperties`; never `@Value`, never in source or `fly.toml`.
- No PII in logs, audits, or the new collections: workspace ids + Freemius numeric ids only; never buyer email/name; never log a provider response body. Structured logging via `StructuredArguments.kv(...)`, enum values passed as `.name()`.
- Every internal endpoint carries `@PreAuthorize`; the webhook endpoint is `@PreAuthorize("permitAll()")` **and** allow-listed in `RbacEndpointInventoryTest`.
- Time: injected `java.time.Clock` (`Instant.now(clock)`), `MutableClock` in tests — never wall-clock.
- Frontend: standalone components, `inject()` DI, `signal()` state, `i18n`/`$localize` on every user-facing string, `.card`/`.alert`/`.btn` global primitives, no `changeDetection: OnPush`.
- Freemius request/payload shapes are pinned by our stub and marked **integration-pending** (the F40/F41 pattern): live-credential promotion is a later, separately-reviewed step.
- Commit after every task (message style `feat(billing): ...`), ending with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## File Structure

Backend (packages under `backend/src/main/java/com/cadence/`):

| File | Responsibility |
|---|---|
| `domain/BillingPlan.java`, `domain/EntitlementStatus.java`, `domain/GatedFeature.java` | plan/status/feature enums |
| `domain/WorkspaceEntitlement.java` | the entitlement document (collection `workspaceEntitlements`) |
| `domain/BillingWebhookEvent.java` | webhook idempotency ledger (collection `billingWebhookEvents`) |
| `repository/WorkspaceEntitlementRepository.java`, `repository/BillingWebhookEventRepository.java` | Spring Data repos |
| `config/migration/ChangeUnit024_BillingIndexes.java` | unique indexes |
| `config/BillingProperties.java` | `cadence.billing` config (base URLs, ids, bearer, webhook secret, timeouts, retry) |
| `integration/BillingProvider.java`, `integration/BillingLicense.java` | the provider seam + license record |
| `integration/FreemiusBillingClient.java`, `integration/BillingApiException.java`, `integration/BillingApiRetry.java` | the adapter (RestClient, explicit-field JSON, retry/classify) |
| `service/EntitlementService.java` | plan resolution + `hasFeature`/`requireFeature` |
| `service/BillingService.java` | view / checkout URL / claim / refresh |
| `api/BillingDtos.java`, `api/BillingExceptions.java`, `api/BillingController.java` | admin billing endpoints |
| `api/BillingExceptionHandler.java` | claim error envelopes (scoped advice) |
| `api/EntitlementExceptionHandler.java` | global 402 `upgrade_required` advice |
| `api/FreemiusWebhookController.java` | public HMAC webhook |
| `scheduler/EntitlementReconciliationScheduler.java` | nightly re-verify sweep |
| Modified: `security/SecurityConfig.java`, `domain/AuthEventType.java`, `service/AuthAuditService.java`, `service/AtsConnectionService.java`, `scheduler/AtsSyncScheduler.java`, `scheduler/NoShowDefenseScheduler.java`, `service/SlaNudgeService.java`, `api/AtsDtos.java`, `resources/application.yml`, `resources/application-test.yml` | wiring + gates |

Backend tests in `backend/src/test/java/com/cadence/billing/`: `StubFreemius.java`, `BillingItBase.java`, `FreemiusBillingClientTest.java`, `BillingIndexIT.java`, `EntitlementServiceIT.java`, `BillingClaimIT.java`, `BillingWebhookIT.java`, `BillingWebhookChainIT.java`, `EntitlementReconcileIT.java`, `BillingGatesIT.java`, `BillingNoSdkStructuralTest.java`. Modified: `rbac/RbacEndpointInventoryTest.java`.

Frontend:

| File | Responsibility |
|---|---|
| `frontend/src/app/features/admin/billing/billing.service.ts` | API calls + `EntitlementView` model |
| `frontend/src/app/features/admin/billing/billing.component.ts` (+`.scss`, `.spec.ts`) | Billing page (plan card, upgrade, claim-on-return, recovery) |
| `frontend/src/app/shared/ui/upgrade-prompt.component.ts` (+`.spec.ts`) | shared paywall prompt |
| Modified: `app.routes.ts`, `core/nav/nav.config.ts`, `core/auth/auth.interceptor.ts`, `features/admin/ats/ats-integration.component.ts` + `ats.service.ts`, `features/admin/workspace/workspace-settings.component.ts`, `features/scheduling/scheduling.component.ts` | route, nav, 402 toast, gated surfaces |
| Modified: `frontend/src/content/pages/pricing/body.html`, `.../pricing/meta.json` | Free/Team copy |

---

### Task 1: Billing domain model, repositories, Mongock indexes

**Files:**
- Create: `backend/src/main/java/com/cadence/domain/BillingPlan.java`
- Create: `backend/src/main/java/com/cadence/domain/EntitlementStatus.java`
- Create: `backend/src/main/java/com/cadence/domain/GatedFeature.java`
- Create: `backend/src/main/java/com/cadence/domain/WorkspaceEntitlement.java`
- Create: `backend/src/main/java/com/cadence/domain/BillingWebhookEvent.java`
- Create: `backend/src/main/java/com/cadence/repository/WorkspaceEntitlementRepository.java`
- Create: `backend/src/main/java/com/cadence/repository/BillingWebhookEventRepository.java`
- Create: `backend/src/main/java/com/cadence/config/migration/ChangeUnit024_BillingIndexes.java`
- Test: `backend/src/test/java/com/cadence/billing/BillingIndexIT.java`

**Interfaces:**
- Consumes: `BaseIntegrationTest` (Testcontainers singleton), Mongock scan package `com.cadence.config.migration`.
- Produces: `WorkspaceEntitlement` (getters/setters for `id, workspaceId, plan, status, fsLicenseId, fsUserId, fsPlanId, expiresAt, boundAt, lastVerifiedAt, updatedAt`; method `boolean confersTeam(Instant now)`), `BillingWebhookEvent(String eventId, String type, String fsLicenseId, Instant receivedAt, String outcome)` ctor, repos `WorkspaceEntitlementRepository.findByWorkspaceId(String)` / `.findByFsLicenseId(String)`, `BillingWebhookEventRepository.existsByEventId(String)`. Enums: `BillingPlan{FREE,TEAM}`, `EntitlementStatus{ACTIVE,CANCELLED,EXPIRED}`, `GatedFeature{ATS_INTEGRATIONS,NO_SHOW_DEFENSE,SLA_NUDGES}`.

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/cadence/billing/BillingIndexIT.java
package com.cadence.billing;

import com.cadence.BaseIntegrationTest;
import com.cadence.config.migration.ChangeUnit024_BillingIndexes;
import com.cadence.domain.BillingWebhookEvent;
import com.cadence.domain.EntitlementStatus;
import com.cadence.domain.WorkspaceEntitlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 032 Task 1 -- entitlement/webhook collections, unique indexes, changeset idempotency. */
class BillingIndexIT extends BaseIntegrationTest {

    @BeforeEach
    void clean() {
        mongoTemplate.remove(new Query(), WorkspaceEntitlement.class);
        mongoTemplate.remove(new Query(), BillingWebhookEvent.class);
    }

    private WorkspaceEntitlement entitlement(String ws, String license) {
        WorkspaceEntitlement e = new WorkspaceEntitlement();
        e.setWorkspaceId(ws);
        e.setFsLicenseId(license);
        e.setStatus(EntitlementStatus.ACTIVE);
        e.setBoundAt(Instant.parse("2026-07-30T00:00:00Z"));
        return e;
    }

    @Test
    void uniqueIndexes_exist_onBothCollections() {
        List<IndexInfo> ent = mongoTemplate.indexOps("workspaceEntitlements").getIndexInfo();
        assertThat(ent).anyMatch(i -> i.isUnique() && i.getIndexFields().size() == 1
            && "workspaceId".equals(i.getIndexFields().get(0).getKey()));
        assertThat(ent).anyMatch(i -> i.isUnique() && i.getIndexFields().size() == 1
            && "fsLicenseId".equals(i.getIndexFields().get(0).getKey()));
        List<IndexInfo> evt = mongoTemplate.indexOps("billingWebhookEvents").getIndexInfo();
        assertThat(evt).anyMatch(i -> i.isUnique() && i.getIndexFields().size() == 1
            && "eventId".equals(i.getIndexFields().get(0).getKey()));
    }

    @Test
    void duplicateWorkspace_andDuplicateLicense_rejected() {
        mongoTemplate.insert(entitlement("ws1", "L1"));
        assertThatThrownBy(() -> mongoTemplate.insert(entitlement("ws1", "L2")))
            .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> mongoTemplate.insert(entitlement("ws2", "L1")))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void confersTeam_followsStatusAndExpiry() {
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        WorkspaceEntitlement e = entitlement("ws1", "L1");
        assertThat(e.confersTeam(now)).isTrue();                     // ACTIVE, no expiry (lifetime)
        e.setExpiresAt(now.plusSeconds(60));
        e.setStatus(EntitlementStatus.CANCELLED);
        assertThat(e.confersTeam(now)).isTrue();                     // cancelled but paid period not ended
        e.setExpiresAt(now.minusSeconds(60));
        assertThat(e.confersTeam(now)).isFalse();                    // past effective end
        e.setExpiresAt(now.plusSeconds(60));
        e.setStatus(EntitlementStatus.EXPIRED);
        assertThat(e.confersTeam(now)).isFalse();                    // explicit provider EXPIRED
    }

    @Test
    void changeset_isIdempotentOnReRun() {
        new ChangeUnit024_BillingIndexes().execute(mongoTemplate);   // second run must not throw
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `backend/`): `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.BillingIndexIT'`
Expected: COMPILE FAILURE (`WorkspaceEntitlement` does not exist).

- [ ] **Step 3: Write the domain classes, repos, changeset**

```java
// backend/src/main/java/com/cadence/domain/BillingPlan.java
package com.cadence.domain;

/** 032 -- the two launch plans (FR-001). A workspace with no conferring entitlement row is FREE. */
public enum BillingPlan {
    FREE,
    TEAM
}
```

```java
// backend/src/main/java/com/cadence/domain/EntitlementStatus.java
package com.cadence.domain;

/** 032 -- provider-mirrored license state (FR-002). CANCELLED still confers TEAM until expiresAt. */
public enum EntitlementStatus {
    ACTIVE,
    CANCELLED,
    EXPIRED
}
```

```java
// backend/src/main/java/com/cadence/domain/GatedFeature.java
package com.cadence.domain;

/** 032 -- the exact Team gate set (FR-003). Append-only; gating map lives in EntitlementService. */
public enum GatedFeature {
    ATS_INTEGRATIONS,
    NO_SHOW_DEFENSE,
    SLA_NUDGES
}
```

```java
// backend/src/main/java/com/cadence/domain/WorkspaceEntitlement.java
package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * 032 -- one row per UPGRADED workspace (spec Key Entities). Absence of a row means the FREE plan
 * (FR-001/FR-022); launch therefore needs no migration. Binding is insert-only under two unique
 * indexes ({workspaceId}, {fsLicenseId} -- ChangeUnit024); lifecycle updates are findAndModify CAS
 * in BillingService, so no {@code @Version}. Holds Freemius numeric ids ONLY -- never buyer
 * name/email/payment data (FR-002). {@code expiresAt} null means a lifetime license.
 */
@Document(collection = "workspaceEntitlements")
public class WorkspaceEntitlement {

    @Id
    private String id;

    private String workspaceId;

    private BillingPlan plan = BillingPlan.TEAM;

    private EntitlementStatus status = EntitlementStatus.ACTIVE;

    /** Freemius license id -- the claim key; unique among bound rows. */
    private String fsLicenseId;

    @Field(value = "fsUserId", write = Field.Write.NON_NULL)
    private String fsUserId;

    @Field(value = "fsPlanId", write = Field.Write.NON_NULL)
    private String fsPlanId;

    /** License effective end; null = lifetime. */
    @Field(value = "expiresAt", write = Field.Write.NON_NULL)
    private Instant expiresAt;

    private Instant boundAt;

    @Field(value = "lastVerifiedAt", write = Field.Write.NON_NULL)
    private Instant lastVerifiedAt;

    private Instant updatedAt;

    public WorkspaceEntitlement() {}

    /** FR-001: confers TEAM while not provider-EXPIRED and not past the effective end. */
    public boolean confersTeam(Instant now) {
        if (status == EntitlementStatus.EXPIRED) {
            return false;
        }
        return expiresAt == null || expiresAt.isAfter(now);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public BillingPlan getPlan() { return plan; }
    public void setPlan(BillingPlan plan) { this.plan = plan; }
    public EntitlementStatus getStatus() { return status; }
    public void setStatus(EntitlementStatus status) { this.status = status; }
    public String getFsLicenseId() { return fsLicenseId; }
    public void setFsLicenseId(String fsLicenseId) { this.fsLicenseId = fsLicenseId; }
    public String getFsUserId() { return fsUserId; }
    public void setFsUserId(String fsUserId) { this.fsUserId = fsUserId; }
    public String getFsPlanId() { return fsPlanId; }
    public void setFsPlanId(String fsPlanId) { this.fsPlanId = fsPlanId; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getBoundAt() { return boundAt; }
    public void setBoundAt(Instant boundAt) { this.boundAt = boundAt; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(Instant lastVerifiedAt) { this.lastVerifiedAt = lastVerifiedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** Ids/status/instants only -- no PII exists on this document at all. */
    @Override
    public String toString() {
        return "WorkspaceEntitlement{id=" + id + ", workspaceId=" + workspaceId
            + ", plan=" + plan + ", status=" + status + ", expiresAt=" + expiresAt + "}";
    }
}
```

```java
// backend/src/main/java/com/cadence/domain/BillingWebhookEvent.java
package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * 032 -- idempotency ledger for processed Freemius webhook events (FR-009), the F22
 * processedWebhookEvents pattern in a SEPARATE collection (distinct provider id namespace).
 * Insert-then-catch-DuplicateKeyException on the unique {eventId} index. Carries no payload
 * bodies and no PII -- event id, type, license id, outcome only.
 */
@Document(collection = "billingWebhookEvents")
public class BillingWebhookEvent {

    @Id
    private String id;

    /** Opaque Freemius event id -- the unique idempotency key (ChangeUnit024). */
    private String eventId;

    private String type;

    @Field(value = "fsLicenseId", write = Field.Write.NON_NULL)
    private String fsLicenseId;

    private Instant receivedAt;

    private String outcome;

    public BillingWebhookEvent() {}

    public BillingWebhookEvent(String eventId, String type, String fsLicenseId,
                               Instant receivedAt, String outcome) {
        this.eventId = eventId;
        this.type = type;
        this.fsLicenseId = fsLicenseId;
        this.receivedAt = receivedAt;
        this.outcome = outcome;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getFsLicenseId() { return fsLicenseId; }
    public void setFsLicenseId(String fsLicenseId) { this.fsLicenseId = fsLicenseId; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
}
```

```java
// backend/src/main/java/com/cadence/repository/WorkspaceEntitlementRepository.java
package com.cadence.repository;

import com.cadence.domain.WorkspaceEntitlement;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * 032 -- entitlement lookups. Binding is insert-only (unique indexes); lifecycle transitions are
 * findAndModify CAS in BillingService, never via save() here.
 */
public interface WorkspaceEntitlementRepository extends MongoRepository<WorkspaceEntitlement, String> {

    Optional<WorkspaceEntitlement> findByWorkspaceId(String workspaceId);

    Optional<WorkspaceEntitlement> findByFsLicenseId(String fsLicenseId);
}
```

```java
// backend/src/main/java/com/cadence/repository/BillingWebhookEventRepository.java
package com.cadence.repository;

import com.cadence.domain.BillingWebhookEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

/** 032 -- webhook idempotency ledger; insert + existsByEventId only (FR-009). */
public interface BillingWebhookEventRepository extends MongoRepository<BillingWebhookEvent, String> {

    boolean existsByEventId(String eventId);
}
```

```java
// backend/src/main/java/com/cadence/config/migration/ChangeUnit024_BillingIndexes.java
package com.cadence.config.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 032 Freemius billing indexes. Order "024" -- derived off the highest APPLIED ChangeUnit ("023"),
 * NOT the branch number. Never rename after applied. Native createIndex + targeted dropIndex
 * rollback (CLAUDE.md Mongock rules; never dropIndexes()). New collections -> no dedupe step.
 * Pure ASCII source (the F30 NUL/binary lesson).
 *
 * <p>Three indexes:
 * <ul>
 *   <li>unique {@code {workspaceId}} on workspaceEntitlements -- one entitlement per workspace
 *       (FR-002); the claim-race loser gets DuplicateKeyException (SC-006).</li>
 *   <li>unique {@code {fsLicenseId}} on workspaceEntitlements -- one license can never back two
 *       workspaces (FR-006/SC-006). Always present on a bound row, so a plain unique index.</li>
 *   <li>unique {@code {eventId}} on billingWebhookEvents -- webhook replay suppression (FR-009),
 *       the ChangeUnit011 pattern in the billing-owned collection.</li>
 * </ul>
 */
@ChangeUnit(id = "024-billing-indexes", order = "024", author = "system")
public class ChangeUnit024_BillingIndexes {

    private static final Document WORKSPACE_KEY = new Document("workspaceId", 1);
    private static final Document LICENSE_KEY = new Document("fsLicenseId", 1);
    private static final Document EVENT_ID_KEY = new Document("eventId", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        MongoCollection<Document> entitlements = mongoTemplate.getCollection("workspaceEntitlements");
        entitlements.createIndex(WORKSPACE_KEY, new IndexOptions().unique(true));
        entitlements.createIndex(LICENSE_KEY, new IndexOptions().unique(true));
        mongoTemplate.getCollection("billingWebhookEvents")
            .createIndex(EVENT_ID_KEY, new IndexOptions().unique(true));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        MongoCollection<Document> entitlements = mongoTemplate.getCollection("workspaceEntitlements");
        entitlements.dropIndex(WORKSPACE_KEY);
        entitlements.dropIndex(LICENSE_KEY);
        mongoTemplate.getCollection("billingWebhookEvents").dropIndex(EVENT_ID_KEY);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.BillingIndexIT'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cadence/domain backend/src/main/java/com/cadence/repository backend/src/main/java/com/cadence/config/migration/ChangeUnit024_BillingIndexes.java backend/src/test/java/com/cadence/billing
git commit -m "feat(billing): entitlement + webhook-event collections with unique indexes (032 T1)"
```

---

### Task 2: Billing config + Freemius provider adapter

**Files:**
- Create: `backend/src/main/java/com/cadence/config/BillingProperties.java`
- Create: `backend/src/main/java/com/cadence/integration/BillingProvider.java`
- Create: `backend/src/main/java/com/cadence/integration/BillingLicense.java`
- Create: `backend/src/main/java/com/cadence/integration/BillingApiException.java`
- Create: `backend/src/main/java/com/cadence/integration/BillingApiRetry.java`
- Create: `backend/src/main/java/com/cadence/integration/FreemiusBillingClient.java`
- Modify: `backend/src/main/resources/application.yml` (add `cadence.billing` block after the `ats:` block)
- Modify: `backend/src/main/resources/application-test.yml` (test billing config)
- Test: `backend/src/test/java/com/cadence/billing/StubFreemius.java`
- Test: `backend/src/test/java/com/cadence/billing/FreemiusBillingClientTest.java`
- Test: `backend/src/test/java/com/cadence/billing/BillingNoSdkStructuralTest.java`

**Interfaces:**
- Consumes: `AuthProperties.getSpaBaseUrl()` (`com.cadence.config.AuthProperties`, prefix `auth`).
- Produces: `BillingProvider` with `BillingLicense fetchLicense(String licenseId)` and `String checkoutUrl(String userEmail)`; `record BillingLicense(String id, String planId, String userId, Instant expiresAt, boolean cancelled)`; `BillingApiException(boolean isTransient, Integer httpStatus, String category)` with `boolean isTransient()`, `boolean isNotFound()`, `boolean isAuth()`; `BillingProperties` getters `getBaseUrl/getCheckoutBaseUrl/getProductId/getTeamPlanId/getApiBearer/getWebhookSecret/getConnectTimeout/getReadTimeout/getRetryMaxAttempts/getRetryBaseBackoff`. Stub: `StubFreemius` with `String baseUrl()`, `void reset()`, `void programLicense(String licenseId, String json)`, `void programStatus(int status)`, `int requestCount()`, `String lastAuthHeader()`.

- [ ] **Step 1: Write the failing unit test**

```java
// backend/src/test/java/com/cadence/billing/FreemiusBillingClientTest.java
package com.cadence.billing;

import com.cadence.config.AuthProperties;
import com.cadence.config.BillingProperties;
import com.cadence.integration.BillingApiException;
import com.cadence.integration.BillingApiRetry;
import com.cadence.integration.BillingLicense;
import com.cadence.integration.FreemiusBillingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 032 Task 2 -- pure-unit adapter test against the JVM-lifetime StubFreemius (the LeverAtsClientTest pattern). */
class FreemiusBillingClientTest {

    private static final StubFreemius stub = new StubFreemius(); // JVM-lifetime; never stopped (dead-port footgun)

    private final BillingProperties props = props();
    private final AuthProperties auth = auth();
    private final FreemiusBillingClient client =
        new FreemiusBillingClient(new BillingApiRetry(props), props, auth);

    private static BillingProperties props() {
        BillingProperties p = new BillingProperties();
        p.setBaseUrl(stub.baseUrl());
        p.setCheckoutBaseUrl("https://checkout.example.test");
        p.setProductId("1001");
        p.setTeamPlanId("2002");
        p.setApiBearer("test-billing-bearer");
        p.setRetryBaseBackoff(Duration.ZERO);
        return p;
    }

    private static AuthProperties auth() {
        AuthProperties a = new AuthProperties();
        a.setSpaBaseUrl("https://app.example.test");
        return a;
    }

    @BeforeEach
    void reset() {
        stub.reset();
    }

    @Test
    void fetchLicense_parsesExplicitFields_andSendsBearer() {
        stub.programLicense("777", "{\"id\":\"777\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":\"2027-01-15 10:30:00\",\"is_cancelled\":false,"
            + "\"secret_key\":\"SENTINEL-NEVER-PARSED\",\"user_email\":\"SENTINEL@pii.test\"}");
        BillingLicense l = client.fetchLicense("777");
        assertThat(l.id()).isEqualTo("777");
        assertThat(l.planId()).isEqualTo("2002");
        assertThat(l.userId()).isEqualTo("55");
        assertThat(l.cancelled()).isFalse();
        assertThat(l.expiresAt()).isEqualTo(Instant.parse("2027-01-15T10:30:00Z"));
        assertThat(stub.lastAuthHeader()).isEqualTo("Bearer test-billing-bearer");
    }

    @Test
    void fetchLicense_nullExpiration_isLifetime() {
        stub.programLicense("778", "{\"id\":\"778\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":null,\"is_cancelled\":true}");
        BillingLicense l = client.fetchLicense("778");
        assertThat(l.expiresAt()).isNull();
        assertThat(l.cancelled()).isTrue();
    }

    @Test
    void errors_classify_notFound_auth_transient_malformed() {
        stub.programStatus(404);
        BillingApiException notFound =
            catchThrowableOfType(() -> client.fetchLicense("x"), BillingApiException.class);
        assertThat(notFound.isNotFound()).isTrue();
        assertThat(notFound.isTransient()).isFalse();

        stub.reset();
        stub.programStatus(401);
        BillingApiException authErr =
            catchThrowableOfType(() -> client.fetchLicense("x"), BillingApiException.class);
        assertThat(authErr.isAuth()).isTrue();

        stub.reset();
        stub.programStatus(500);
        BillingApiException transientErr =
            catchThrowableOfType(() -> client.fetchLicense("x"), BillingApiException.class);
        assertThat(transientErr.isTransient()).isTrue();

        stub.reset();
        stub.programLicense("779", "not-json{{");
        assertThatThrownBy(() -> client.fetchLicense("779"))
            .isInstanceOf(BillingApiException.class);
    }

    @Test
    void checkoutUrl_isHosted_withEncodedEmail_andReturnUrl() {
        String url = client.checkoutUrl("admin+x@corp.test");
        assertThat(url).startsWith("https://checkout.example.test/product/1001/plan/2002/?");
        assertThat(url).contains("user_email=admin%2Bx%40corp.test");
        assertThat(url).contains("readonly_user=true");
        assertThat(url).contains("return_url=https%3A%2F%2Fapp.example.test%2Fadmin%2Fbilling");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.FreemiusBillingClientTest'`
Expected: COMPILE FAILURE (classes do not exist).

- [ ] **Step 3: Write the stub, config, seam, and adapter**

```java
// backend/src/test/java/com/cadence/billing/StubFreemius.java
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
```

```java
// backend/src/main/java/com/cadence/config/BillingProperties.java
package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 032 -- Freemius billing config (FR-018/FR-019). base-url/checkout-base-url point at live Freemius
 * in prod; tests point base-url at the JDK HttpServer stub via @DynamicPropertySource. apiBearer and
 * webhookSecret are app-level secrets bound from Fly env placeholders (the EmailDeliveryProperties
 * model) -- blank values fail closed (webhook rejects; checkout/claim error) rather than boot-fail.
 */
@ConfigurationProperties(prefix = "cadence.billing")
public class BillingProperties {

    private String baseUrl = "https://api.freemius.com";
    private String checkoutBaseUrl = "https://checkout.freemius.com";
    private String productId = "";
    private String teamPlanId = "";
    private String apiBearer = "";
    private String webhookSecret = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
    private int retryMaxAttempts = 3;
    private Duration retryBaseBackoff = Duration.ofSeconds(2);

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getCheckoutBaseUrl() { return checkoutBaseUrl; }
    public void setCheckoutBaseUrl(String checkoutBaseUrl) { this.checkoutBaseUrl = checkoutBaseUrl; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getTeamPlanId() { return teamPlanId; }
    public void setTeamPlanId(String teamPlanId) { this.teamPlanId = teamPlanId; }
    public String getApiBearer() { return apiBearer; }
    public void setApiBearer(String apiBearer) { this.apiBearer = apiBearer; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getRetryMaxAttempts() { return retryMaxAttempts; }
    public void setRetryMaxAttempts(int retryMaxAttempts) { this.retryMaxAttempts = retryMaxAttempts; }
    public Duration getRetryBaseBackoff() { return retryBaseBackoff; }
    public void setRetryBaseBackoff(Duration retryBaseBackoff) { this.retryBaseBackoff = retryBaseBackoff; }
}
```

```java
// backend/src/main/java/com/cadence/integration/BillingLicense.java
package com.cadence.integration;

import java.time.Instant;

/** 032 -- the minimized license projection (explicit fields only; provider free-text never binds). */
public record BillingLicense(String id, String planId, String userId, Instant expiresAt, boolean cancelled) {}
```

```java
// backend/src/main/java/com/cadence/integration/BillingProvider.java
package com.cadence.integration;

/**
 * 032 -- the provider-agnostic billing seam (FR-018, constitution Dependency Policy). All Freemius
 * access lives behind this interface so service/scheduler code never references the concrete client
 * or the freemius.com hosts (enforced by BillingNoSdkStructuralTest).
 */
public interface BillingProvider {

    /**
     * Fetch one license by id. Throws {@link BillingApiException} classified transient (429/5xx/
     * network), not-found (404), auth (401/403 -- operator misconfig), or malformed.
     */
    BillingLicense fetchLicense(String licenseId);

    /**
     * Build the hosted-checkout URL for the Team plan with the buyer email prefilled read-only and
     * the return URL pointing at the SPA billing page (FR-005). Pure URL construction -- no HTTP.
     */
    String checkoutUrl(String userEmail);
}
```

```java
// backend/src/main/java/com/cadence/integration/BillingApiException.java
package com.cadence.integration;

/**
 * 032 -- normalized Freemius API failure (the AtsApiException shape). Message carries category and
 * status only -- never the response body, never the bearer.
 */
public class BillingApiException extends RuntimeException {

    private final boolean isTransient;
    private final Integer httpStatus;
    private final String category;

    public BillingApiException(boolean isTransient, Integer httpStatus, String category) {
        super("billing api failure: " + category + " status=" + httpStatus);
        this.isTransient = isTransient;
        this.httpStatus = httpStatus;
        this.category = category;
    }

    public boolean isTransient() { return isTransient; }
    public Integer httpStatus() { return httpStatus; }
    public String category() { return category; }
    public boolean isNotFound() { return httpStatus != null && httpStatus == 404; }
    public boolean isAuth() { return httpStatus != null && (httpStatus == 401 || httpStatus == 403); }
}
```

```java
// backend/src/main/java/com/cadence/integration/BillingApiRetry.java
package com.cadence.integration;

import com.cadence.config.BillingProperties;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 032 -- bounded retry with jittered backoff for TRANSIENT billing API failures only (the
 * AtsApiRetry shape). Pure backoff math; tests zero the base backoff so no sleeps occur.
 */
@Component
public class BillingApiRetry {

    private final int maxAttempts;
    private final long baseBackoffMillis;

    public BillingApiRetry(BillingProperties props) {
        this.maxAttempts = props.getRetryMaxAttempts();
        this.baseBackoffMillis = props.getRetryBaseBackoff().toMillis();
    }

    public <T> T execute(Supplier<T> attempt) {
        BillingApiException last = null;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                return attempt.get();
            } catch (BillingApiException e) {
                if (!e.isTransient()) {
                    throw e;
                }
                last = e;
                sleep(backoffMillis(i));
            }
        }
        throw last;
    }

    /** Exposed for backoff-shape assertions without sleeping. */
    long backoffMillis(int attemptIndex) {
        long base = baseBackoffMillis * (1L << attemptIndex);
        return base == 0 ? 0 : base + (long) (Math.random() * (base / 2.0));
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BillingApiException(true, null, "interrupted");
        }
    }
}
```

```java
// backend/src/main/java/com/cadence/integration/FreemiusBillingClient.java
package com.cadence.integration;

import com.cadence.config.AuthProperties;
import com.cadence.config.BillingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * 032 -- Freemius adapter behind {@link BillingProvider} (FR-018). Own RestClient (JDK HttpClient
 * factory, the LeverAtsClient recipe), static ObjectMapper, explicit JsonNode.path reads only --
 * user_email/secret_key and every other unparsed field never bind (SENTINEL-seeded in the stub).
 * Endpoint + payload shape are integration-pending: pinned by StubFreemius, promoted to live
 * credentials in a later, separately-reviewed step. Response bodies are never logged.
 */
@Component
public class FreemiusBillingClient implements BillingProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Freemius datetimes are UTC "yyyy-MM-dd HH:mm:ss" (integration-pending). */
    private static final DateTimeFormatter FS_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BillingApiRetry retry;
    private final BillingProperties props;
    private final AuthProperties auth;
    private final RestClient http;

    public FreemiusBillingClient(BillingApiRetry retry, BillingProperties props, AuthProperties auth) {
        this.retry = retry;
        this.props = props;
        this.auth = auth;
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(props.getConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(props.getReadTimeout());
        this.http = RestClient.builder()
            .baseUrl(props.getBaseUrl())
            .requestFactory(factory)
            .build();
    }

    @Override
    public BillingLicense fetchLicense(String licenseId) {
        String body = retry.execute(() -> call(() -> http.get()
            .uri("/v1/products/{pid}/licenses/{lid}.json", props.getProductId(), licenseId)
            .header("Authorization", "Bearer " + props.getApiBearer())
            .retrieve()
            .body(String.class)));
        return parseLicense(body);
    }

    @Override
    public String checkoutUrl(String userEmail) {
        return props.getCheckoutBaseUrl()
            + "/product/" + props.getProductId()
            + "/plan/" + props.getTeamPlanId()
            + "/?user_email=" + URLEncoder.encode(userEmail, StandardCharsets.UTF_8)
            + "&readonly_user=true"
            + "&return_url=" + URLEncoder.encode(auth.getSpaBaseUrl() + "/admin/billing", StandardCharsets.UTF_8);
    }

    private BillingLicense parseLicense(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new BillingApiException(false, null, "malformed_license");
        }
        String id = root.path("id").asText(null);
        if (id == null) {
            throw new BillingApiException(false, null, "malformed_license");
        }
        Instant expiresAt = null;
        JsonNode expiration = root.path("expiration");
        if (!expiration.isMissingNode() && !expiration.isNull()) {
            try {
                expiresAt = LocalDateTime.parse(expiration.asText(), FS_DATETIME).toInstant(ZoneOffset.UTC);
            } catch (Exception e) {
                throw new BillingApiException(false, null, "malformed_license");
            }
        }
        return new BillingLicense(id,
            root.path("plan_id").asText(null),
            root.path("user_id").asText(null),
            expiresAt,
            root.path("is_cancelled").asBoolean(false));
    }

    /** Run one HTTP attempt, normalising failures to {@link BillingApiException} (never logging the body). */
    private <T> T call(Supplier<T> attempt) {
        try {
            return attempt.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429 || status >= 500) {
                throw new BillingApiException(true, status, "transient");
            }
            if (status == 401 || status == 403) {
                throw new BillingApiException(false, status, "auth");
            }
            if (status == 404) {
                throw new BillingApiException(false, status, "not_found");
            }
            throw new BillingApiException(false, status, "fatal");
        } catch (ResourceAccessException e) {
            throw new BillingApiException(true, null, "network");
        }
    }
}
```

Add to `backend/src/main/resources/application.yml`, inside the top-level `cadence:` block, directly after the `ats:` sub-block (keep the file's existing 2-space indentation):

```yaml
  # 032 Freemius billing (FR-018/FR-019). base-url points at live Freemius in prod; tests override it
  # to the JDK HttpServer stub. product-id/team-plan-id/api-bearer/webhook-secret arrive only via Fly
  # env secrets; blank values fail closed (webhook rejects, checkout/claim error) -- no secrets here.
  billing:
    base-url: ${FREEMIUS_API_BASE_URL:https://api.freemius.com}
    checkout-base-url: ${FREEMIUS_CHECKOUT_BASE_URL:https://checkout.freemius.com}
    product-id: ${FREEMIUS_PRODUCT_ID:}
    team-plan-id: ${FREEMIUS_TEAM_PLAN_ID:}
    api-bearer: ${FREEMIUS_API_BEARER:}
    webhook-secret: ${FREEMIUS_WEBHOOK_SECRET:}
    connect-timeout: PT5S
    read-timeout: PT10S
    retry-max-attempts: 3
    retry-base-backoff: PT2S
```

Add to `backend/src/main/resources/application-test.yml`, inside its `cadence:` block:

```yaml
  billing:
    product-id: "1001"
    team-plan-id: "2002"
    api-bearer: test-billing-bearer
    webhook-secret: test-billing-webhook-secret
    retry-base-backoff: PT0S
```

```java
// backend/src/test/java/com/cadence/billing/BillingNoSdkStructuralTest.java
package com.cadence.billing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 032 -- the AtsNoSdkStructuralTest analogue: business logic must depend on the BillingProvider
 * seam only. No file under service/ or scheduler/ may mention the concrete client or the
 * Freemius hosts (FR-018 / SC-005-adjacent).
 */
class BillingNoSdkStructuralTest {

    private static final List<String> FORBIDDEN = List.of("FreemiusBillingClient", "freemius.com");

    @Test
    void serviceAndSchedulerLayers_neverReferenceTheConcreteBillingClient() throws IOException {
        for (String dir : List.of("src/main/java/com/cadence/service", "src/main/java/com/cadence/scheduler")) {
            try (Stream<Path> files = Files.walk(Path.of(dir))) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    String source;
                    try {
                        source = Files.readString(p);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    for (String needle : FORBIDDEN) {
                        assertThat(source).withFailMessage("%s references '%s'", p, needle)
                            .doesNotContain(needle);
                    }
                });
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.FreemiusBillingClientTest' --tests 'com.cadence.billing.BillingNoSdkStructuralTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cadence/config/BillingProperties.java backend/src/main/java/com/cadence/integration backend/src/main/resources backend/src/test/java/com/cadence/billing
git commit -m "feat(billing): Freemius adapter behind BillingProvider seam + config (032 T2)"
```

---

### Task 3: EntitlementService, gated features, global 402

**Files:**
- Create: `backend/src/main/java/com/cadence/service/EntitlementService.java`
- Create: `backend/src/main/java/com/cadence/api/BillingExceptions.java`
- Create: `backend/src/main/java/com/cadence/api/EntitlementExceptionHandler.java`
- Test: `backend/src/test/java/com/cadence/billing/BillingItBase.java`
- Test: `backend/src/test/java/com/cadence/billing/EntitlementServiceIT.java`

**Interfaces:**
- Consumes: Task 1 domain/repos; `AuthTestConfig`/`MutableClock` (`com.cadence.auth`); `BaseIntegrationTest`.
- Produces: `EntitlementService` with `BillingPlan planOf(String workspaceId)`, `boolean hasFeature(String workspaceId, GatedFeature feature)`, `void requireFeature(String workspaceId, GatedFeature feature)`; `BillingExceptions.UpgradeRequiredException` (mapped 402 `upgrade_required` by `EntitlementExceptionHandler`), `BillingExceptions.ClaimRejectedException(String code)` with `String code()`, `BillingExceptions.ClaimUnavailableException`; `BillingItBase` (MockMvc + admin cookie + StubFreemius + clean collections).

- [ ] **Step 1: Write the IT base and the failing test**

```java
// backend/src/test/java/com/cadence/billing/BillingItBase.java
package com.cadence.billing;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.BillingWebhookEvent;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

/**
 * 032 -- billing IT base (the WorkspaceItBase shape): MockMvc + fixed MutableClock + JVM-lifetime
 * StubFreemius wired into cadence.billing.base-url. Cleanup is remove-not-drop (Mongock indexes).
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
public abstract class BillingItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";
    protected static final StubFreemius stub = new StubFreemius();

    @DynamicPropertySource
    static void billingProps(DynamicPropertyRegistry r) {
        r.add("cadence.billing.base-url", stub::baseUrl);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected MutableClock clock;

    @BeforeEach
    void cleanBilling() {
        clock.set(AuthTestConfig.FIXED_START);
        stub.reset();
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), WorkspaceEntitlement.class);
        mongoTemplate.remove(new Query(), BillingWebhookEvent.class);
    }

    protected Member member(String email, Role role) {
        return memberService.create(WS, email, email, role, null, null);
    }

    protected Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    protected Cookie adminCookie() {
        return cookie(member("admin@x.com", Role.ADMIN));
    }

    /** Seed a bound TEAM entitlement directly (bypasses claim -- for gate/lifecycle tests). */
    protected WorkspaceEntitlement seedTeam(String workspaceId, String licenseId, Instant expiresAt) {
        WorkspaceEntitlement e = new WorkspaceEntitlement();
        e.setWorkspaceId(workspaceId);
        e.setFsLicenseId(licenseId);
        e.setFsPlanId("2002");
        e.setExpiresAt(expiresAt);
        e.setBoundAt(Instant.now(clock));
        e.setUpdatedAt(Instant.now(clock));
        return mongoTemplate.insert(e);
    }
}
```

```java
// backend/src/test/java/com/cadence/billing/EntitlementServiceIT.java
package com.cadence.billing;

import com.cadence.api.BillingExceptions;
import com.cadence.domain.BillingPlan;
import com.cadence.domain.GatedFeature;
import com.cadence.service.EntitlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 032 Task 3 -- plan resolution and feature gating (FR-001/FR-003), clock-driven expiry. */
class EntitlementServiceIT extends BillingItBase {

    @Autowired
    EntitlementService entitlements;

    @Test
    void noRow_meansFree_andEveryGateRefuses() {
        assertThat(entitlements.planOf(WS)).isEqualTo(BillingPlan.FREE);
        for (GatedFeature f : GatedFeature.values()) {
            assertThat(entitlements.hasFeature(WS, f)).isFalse();
            assertThatThrownBy(() -> entitlements.requireFeature(WS, f))
                .isInstanceOf(BillingExceptions.UpgradeRequiredException.class);
        }
    }

    @Test
    void boundRow_confersTeam_andEveryGatePasses() {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        assertThat(entitlements.planOf(WS)).isEqualTo(BillingPlan.TEAM);
        for (GatedFeature f : GatedFeature.values()) {
            assertThatCode(() -> entitlements.requireFeature(WS, f)).doesNotThrowAnyException();
        }
    }

    @Test
    void expiryIsClockDriven_teamDropsToFree_whenClockPassesExpiresAt() {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        assertThat(entitlements.planOf(WS)).isEqualTo(BillingPlan.TEAM);
        clock.advance(Duration.ofDays(31));
        assertThat(entitlements.planOf(WS)).isEqualTo(BillingPlan.FREE);
    }

    @Test
    void lifetimeLicense_neverExpires() {
        seedTeam(WS, "L1", null);
        clock.advance(Duration.ofDays(3650));
        assertThat(entitlements.planOf(WS)).isEqualTo(BillingPlan.TEAM);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.EntitlementServiceIT'`
Expected: COMPILE FAILURE (`EntitlementService` does not exist).

- [ ] **Step 3: Write the service, exceptions, and 402 advice**

```java
// backend/src/main/java/com/cadence/service/EntitlementService.java
package com.cadence.service;

import com.cadence.api.BillingExceptions;
import com.cadence.domain.BillingPlan;
import com.cadence.domain.GatedFeature;
import com.cadence.repository.WorkspaceEntitlementRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 032 -- plan resolution + feature gating (FR-001/FR-003/FR-013). Absence of an entitlement row,
 * or a row past its effective end, means FREE. The plan->features map is static: adding a future
 * plan is a map + enum change, never scattered conditionals. Callers gate INITIATION points only
 * (FR-004); candidate-facing paths must never call this (SC-007).
 */
@Service
public class EntitlementService {

    private static final Map<BillingPlan, Set<GatedFeature>> PLAN_FEATURES = Map.of(
        BillingPlan.FREE, Set.of(),
        BillingPlan.TEAM, EnumSet.allOf(GatedFeature.class));

    private final WorkspaceEntitlementRepository entitlements;
    private final Clock clock;

    public EntitlementService(WorkspaceEntitlementRepository entitlements, Clock clock) {
        this.entitlements = entitlements;
        this.clock = clock;
    }

    public BillingPlan planOf(String workspaceId) {
        Instant now = Instant.now(clock);
        return entitlements.findByWorkspaceId(workspaceId)
            .filter(e -> e.confersTeam(now))
            .map(e -> BillingPlan.TEAM)
            .orElse(BillingPlan.FREE);
    }

    public boolean hasFeature(String workspaceId, GatedFeature feature) {
        return PLAN_FEATURES.get(planOf(workspaceId)).contains(feature);
    }

    /** Throws the 402 upgrade_required refusal (FR-013) when the workspace lacks the feature. */
    public void requireFeature(String workspaceId, GatedFeature feature) {
        if (!hasFeature(workspaceId, feature)) {
            throw new BillingExceptions.UpgradeRequiredException();
        }
    }
}
```

```java
// backend/src/main/java/com/cadence/api/BillingExceptions.java
package com.cadence.api;

/** 032 -- billing domain exceptions (the CsvImportExceptions holder shape). */
public final class BillingExceptions {

    private BillingExceptions() {}

    /** 402 upgrade_required -- a gated action was attempted on a FREE workspace (FR-013). */
    public static class UpgradeRequiredException extends RuntimeException {}

    /**
     * 409 with a typed code -- a claim was refused: invalid_license / wrong_plan /
     * license_inactive / license_already_bound / already_upgraded (FR-006).
     */
    public static class ClaimRejectedException extends RuntimeException {
        private final String code;

        public ClaimRejectedException(String code) {
            super("claim rejected: " + code);
            this.code = code;
        }

        public String code() { return code; }
    }

    /** 503 billing_unavailable -- Freemius unreachable or misconfigured during claim (FR-006). */
    public static class ClaimUnavailableException extends RuntimeException {}
}
```

```java
// backend/src/main/java/com/cadence/api/EntitlementExceptionHandler.java
package com.cadence.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 032 -- GLOBAL advice for exactly one exception: the 402 upgrade_required envelope (FR-013).
 * Global (no assignableTypes) because gated services throw it from many controllers (ATS today,
 * more later); it handles nothing else, so per-feature advices are unaffected.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class EntitlementExceptionHandler {

    @ExceptionHandler(BillingExceptions.UpgradeRequiredException.class)
    public ResponseEntity<Map<String, String>> upgradeRequired(BillingExceptions.UpgradeRequiredException e) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
            .body(Map.of("error", "upgrade_required"));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.EntitlementServiceIT'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cadence/service/EntitlementService.java backend/src/main/java/com/cadence/api/BillingExceptions.java backend/src/main/java/com/cadence/api/EntitlementExceptionHandler.java backend/src/test/java/com/cadence/billing
git commit -m "feat(billing): EntitlementService plan gates + global 402 envelope (032 T3)"
```

---

### Task 4: Claim/checkout/view — BillingService + BillingController

**Files:**
- Create: `backend/src/main/java/com/cadence/service/BillingService.java`
- Create: `backend/src/main/java/com/cadence/api/BillingDtos.java`
- Create: `backend/src/main/java/com/cadence/api/BillingController.java`
- Create: `backend/src/main/java/com/cadence/api/BillingExceptionHandler.java`
- Modify: `backend/src/main/java/com/cadence/domain/AuthEventType.java` (append at end, never reorder)
- Modify: `backend/src/main/java/com/cadence/service/AuthAuditService.java` (three typed methods)
- Test: `backend/src/test/java/com/cadence/billing/BillingClaimIT.java`

**Interfaces:**
- Consumes: `BillingProvider.fetchLicense/checkoutUrl` (T2), `EntitlementService` (T3), `SessionService.Principal` (`principal.workspaceId()`, `principal.memberId()`), `AuthAuditService`, `MongoTemplate` CAS.
- Produces: `BillingService` with `BillingDtos.EntitlementResponse view(String workspaceId)`, `String checkoutUrl(String workspaceId, String actorMemberId)` (resolves the admin email via `MemberRepository.findById(actorMemberId)` — the email goes into the checkout URL only, never into logs or audits), `BillingDtos.EntitlementResponse claim(String workspaceId, String licenseId, String actorMemberId)`, `void refreshByLicenseId(String licenseId)`, `void refresh(WorkspaceEntitlement e)`. Endpoints: `GET /api/internal/billing/entitlement` (isAuthenticated), `POST /api/internal/billing/checkout-session` (ADMIN), `POST /api/internal/billing/claim` (ADMIN). DTOs: `EntitlementResponse(BillingPlan plan, EntitlementStatus status, Instant expiresAt, Instant boundAt)`, `CheckoutSessionResponse(String checkoutUrl)`, `ClaimRequest(@NotBlank @Size(max=64) String licenseId)`. New `AuthEventType` values: `BILLING_CHECKOUT_STARTED`, `BILLING_LICENSE_CLAIMED`, `BILLING_ENTITLEMENT_UPDATED`.

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/cadence/billing/BillingClaimIT.java
package com.cadence.billing;

import com.cadence.domain.Member;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 032 Task 4 -- checkout URL, claim validation, race semantics, role split (US1). */
class BillingClaimIT extends BillingItBase {

    private static final String ACTIVE_TEAM_LICENSE =
        "{\"id\":\"L1\",\"plan_id\":\"2002\",\"user_id\":\"55\",\"expiration\":\"2027-01-15 10:30:00\",\"is_cancelled\":false}";

    private String claimBody(String licenseId) {
        return "{\"licenseId\":\"" + licenseId + "\"}";
    }

    @Test
    void entitlement_isReadableByEveryRole_butDefaultsFree() throws Exception {
        for (Role role : Role.values()) {
            Cookie c = cookie(member(role.name().toLowerCase() + "@x.com", role));
            mvc.perform(get("/api/internal/billing/entitlement").cookie(c))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan", is("FREE")));
        }
    }

    @Test
    void checkoutSession_adminOnly_returnsHostedUrl() throws Exception {
        mvc.perform(post("/api/internal/billing/checkout-session").cookie(adminCookie()).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checkoutUrl", startsWith("https://checkout.freemius.com/product/1001/plan/2002/")));
        Cookie recruiter = cookie(member("rec@x.com", Role.RECRUITER));
        mvc.perform(post("/api/internal/billing/checkout-session").cookie(recruiter).with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void claim_validLicense_bindsAndReturnsTeam() throws Exception {
        stub.programLicense("L1", ACTIVE_TEAM_LICENSE);
        mvc.perform(post("/api/internal/billing/claim").cookie(adminCookie()).with(csrf())
                .contentType("application/json").content(claimBody("L1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.plan", is("TEAM")))
            .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    void claim_isIdempotent_forTheSameWorkspaceAndLicense() throws Exception {
        stub.programLicense("L1", ACTIVE_TEAM_LICENSE);
        Cookie admin = adminCookie();
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("L1")))
            .andExpect(status().isOk());
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("L1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.plan", is("TEAM")));
    }

    @Test
    void claim_typedRefusals() throws Exception {
        Cookie admin = adminCookie();
        // wrong plan
        stub.programLicense("L2", "{\"id\":\"L2\",\"plan_id\":\"9999\",\"user_id\":\"55\",\"expiration\":null,\"is_cancelled\":false}");
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("L2")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("wrong_plan")));
        // cancelled + past end = inactive
        stub.programLicense("L3", "{\"id\":\"L3\",\"plan_id\":\"2002\",\"user_id\":\"55\",\"expiration\":\"2020-01-01 00:00:00\",\"is_cancelled\":true}");
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("L3")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("license_inactive")));
        // unknown license id -> provider 404
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("NOPE")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("invalid_license")));
    }

    @Test
    void claim_licenseBoundElsewhere_andWorkspaceAlreadyUpgraded_areDistinct() throws Exception {
        stub.programLicense("L1", ACTIVE_TEAM_LICENSE);
        seedTeam("other-ws", "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        Cookie admin = adminCookie();
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("L1")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("license_already_bound")));

        seedTeam(WS, "L9", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programLicense("L4", "{\"id\":\"L4\",\"plan_id\":\"2002\",\"user_id\":\"55\",\"expiration\":null,\"is_cancelled\":false}");
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("L4")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("already_upgraded")));
    }

    @Test
    void claim_providerDown_is503_withoutBinding() throws Exception {
        stub.programStatus(500);
        mvc.perform(post("/api/internal/billing/claim").cookie(adminCookie()).with(csrf())
                .contentType("application/json").content(claimBody("L1")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error", is("billing_unavailable")));
        mvc.perform(get("/api/internal/billing/entitlement").cookie(adminCookie()))
            .andExpect(jsonPath("$.plan", is("FREE")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.BillingClaimIT'`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Write service, DTOs, controller, handler, audit**

```java
// backend/src/main/java/com/cadence/api/BillingDtos.java
package com.cadence.api;

import com.cadence.domain.BillingPlan;
import com.cadence.domain.EntitlementStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 032 -- billing API contracts. No license ids and no PII in responses. */
public final class BillingDtos {

    private BillingDtos() {}

    /** Plan view for the Billing page + gated-surface prompts. status/expiresAt/boundAt null on FREE. */
    public record EntitlementResponse(BillingPlan plan, EntitlementStatus status,
                                      Instant expiresAt, Instant boundAt) {}

    public record CheckoutSessionResponse(String checkoutUrl) {}

    public record ClaimRequest(@NotBlank @Size(max = 64) String licenseId) {}
}
```

```java
// backend/src/main/java/com/cadence/service/BillingService.java
package com.cadence.service;

import com.cadence.api.BillingDtos;
import com.cadence.api.BillingExceptions;
import com.cadence.domain.BillingPlan;
import com.cadence.domain.EntitlementStatus;
import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.integration.BillingApiException;
import com.cadence.integration.BillingLicense;
import com.cadence.integration.BillingProvider;
import com.cadence.config.BillingProperties;
import com.cadence.repository.MemberRepository;
import com.cadence.repository.WorkspaceEntitlementRepository;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 032 -- checkout URL, license claim (the ONLY binding act, FR-006/FR-007), and provider-truth
 * refresh (FR-010/FR-011). Binding is insert-only under the two unique indexes; the
 * DuplicateKeyException loser re-reads to classify the race deterministically. Refresh never
 * downgrades on a provider error (FR-011) -- BillingApiException propagates to the caller, which
 * isolates per row. Logs carry workspace/Freemius ids only.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final WorkspaceEntitlementRepository entitlements;
    private final MemberRepository members;
    private final BillingProvider provider;
    private final BillingProperties props;
    private final AuthAuditService audit;
    private final MongoTemplate mongo;
    private final Clock clock;

    public BillingService(WorkspaceEntitlementRepository entitlements, MemberRepository members,
                          BillingProvider provider, BillingProperties props, AuthAuditService audit,
                          MongoTemplate mongo, Clock clock) {
        this.entitlements = entitlements;
        this.members = members;
        this.provider = provider;
        this.props = props;
        this.audit = audit;
        this.mongo = mongo;
        this.clock = clock;
    }

    public BillingDtos.EntitlementResponse view(String workspaceId) {
        Instant now = Instant.now(clock);
        return entitlements.findByWorkspaceId(workspaceId)
            .filter(e -> e.confersTeam(now))
            .map(e -> new BillingDtos.EntitlementResponse(BillingPlan.TEAM, e.getStatus(),
                e.getExpiresAt(), e.getBoundAt()))
            .orElse(new BillingDtos.EntitlementResponse(BillingPlan.FREE, null, null, null));
    }

    public String checkoutUrl(String workspaceId, String actorMemberId) {
        String adminEmail = members.findById(actorMemberId)
            .map(m -> m.getEmail()) // converter-decrypted; goes into the checkout URL only, never logged
            .orElseThrow(() -> new BillingExceptions.ClaimUnavailableException());
        audit.billingCheckoutStarted(workspaceId, actorMemberId);
        return provider.checkoutUrl(adminEmail);
    }

    public BillingDtos.EntitlementResponse claim(String workspaceId, String licenseId, String actorMemberId) {
        Optional<WorkspaceEntitlement> existing = entitlements.findByWorkspaceId(workspaceId);
        if (existing.isPresent()) {
            if (licenseId.equals(existing.get().getFsLicenseId())) {
                return view(workspaceId); // idempotent re-claim (return-page refresh)
            }
            throw new BillingExceptions.ClaimRejectedException("already_upgraded");
        }
        BillingLicense license = fetchForClaim(licenseId);
        if (!props.getTeamPlanId().equals(license.planId())) {
            throw new BillingExceptions.ClaimRejectedException("wrong_plan");
        }
        Instant now = Instant.now(clock);
        boolean pastEnd = license.expiresAt() != null && !license.expiresAt().isAfter(now);
        if (license.cancelled() || pastEnd) {
            throw new BillingExceptions.ClaimRejectedException("license_inactive"); // FR-006: active only
        }
        WorkspaceEntitlement e = new WorkspaceEntitlement();
        e.setWorkspaceId(workspaceId);
        e.setFsLicenseId(license.id());
        e.setFsUserId(license.userId());
        e.setFsPlanId(license.planId());
        e.setStatus(EntitlementStatus.ACTIVE);
        e.setExpiresAt(license.expiresAt());
        e.setBoundAt(now);
        e.setLastVerifiedAt(now);
        e.setUpdatedAt(now);
        try {
            entitlements.insert(e);
        } catch (DuplicateKeyException dup) {
            Optional<WorkspaceEntitlement> current = entitlements.findByWorkspaceId(workspaceId);
            if (current.isPresent()) {
                if (licenseId.equals(current.get().getFsLicenseId())) {
                    return view(workspaceId); // lost an intra-workspace race to the same license
                }
                throw new BillingExceptions.ClaimRejectedException("already_upgraded");
            }
            throw new BillingExceptions.ClaimRejectedException("license_already_bound");
        }
        audit.billingLicenseClaimed(workspaceId, actorMemberId);
        log.info("billing license claimed {} {}",
            StructuredArguments.kv("workspaceId", workspaceId),
            StructuredArguments.kv("fsLicenseId", license.id()));
        return view(workspaceId);
    }

    private BillingLicense fetchForClaim(String licenseId) {
        try {
            return provider.fetchLicense(licenseId);
        } catch (BillingApiException ex) {
            if (ex.isNotFound()) {
                throw new BillingExceptions.ClaimRejectedException("invalid_license");
            }
            throw new BillingExceptions.ClaimUnavailableException();
        }
    }

    /** Webhook poke path (FR-010): unbound license ids are a no-op; bound ones re-fetch truth. */
    public void refreshByLicenseId(String licenseId) {
        entitlements.findByFsLicenseId(licenseId).ifPresent(this::refresh);
    }

    /**
     * Re-verify one entitlement against provider truth (FR-010/FR-011/FR-012). Throws
     * BillingApiException on provider failure -- the caller isolates; state is never changed on error.
     */
    public void refresh(WorkspaceEntitlement e) {
        BillingLicense license = provider.fetchLicense(e.getFsLicenseId());
        Instant now = Instant.now(clock);
        if (!props.getTeamPlanId().equals(license.planId())) {
            // Spec edge case: unknown plan id -> flag for the operator, never silently downgrade.
            log.warn("billing entitlement has unrecognized plan {} {}",
                StructuredArguments.kv("workspaceId", e.getWorkspaceId()),
                StructuredArguments.kv("fsPlanId", license.planId()));
            return;
        }
        EntitlementStatus status = license.cancelled() ? EntitlementStatus.CANCELLED : EntitlementStatus.ACTIVE;
        if (license.expiresAt() != null && !license.expiresAt().isAfter(now)) {
            status = EntitlementStatus.EXPIRED;
        }
        boolean changed = status != e.getStatus() || !Objects.equals(license.expiresAt(), e.getExpiresAt());
        mongo.findAndModify(
            Query.query(Criteria.where("_id").is(e.getId())),
            new Update().set("status", status).set("expiresAt", license.expiresAt())
                .set("lastVerifiedAt", now).set("updatedAt", now),
            FindAndModifyOptions.options().returnNew(true),
            WorkspaceEntitlement.class);
        if (changed) {
            audit.billingEntitlementUpdated(e.getWorkspaceId(), status.name().toLowerCase());
            log.info("billing entitlement updated {} {}",
                StructuredArguments.kv("workspaceId", e.getWorkspaceId()),
                StructuredArguments.kv("status", status.name()));
        }
    }
}
```

```java
// backend/src/main/java/com/cadence/api/BillingController.java
package com.cadence.api;

import com.cadence.service.BillingService;
import com.cadence.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 032 -- billing endpoints (spec US1/FR-005/FR-006/FR-014). Plan view is readable by every
 * authenticated member (gated-surface prompts need it); checkout + claim are Admin-only. The
 * workspace is always the session principal's -- never a path variable (house rule). Authorization
 * reads the persisted member role via the session filter.
 */
@RestController
@RequestMapping("/api/internal/billing")
public class BillingController {

    private final BillingService billing;

    public BillingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/entitlement")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BillingDtos.EntitlementResponse> entitlement(
            @AuthenticationPrincipal SessionService.Principal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(billing.view(principal.workspaceId()));
    }

    @PostMapping("/checkout-session")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BillingDtos.CheckoutSessionResponse> checkoutSession(
            @AuthenticationPrincipal SessionService.Principal principal) {
        String url = billing.checkoutUrl(principal.workspaceId(), principal.memberId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new BillingDtos.CheckoutSessionResponse(url));
    }

    @PostMapping("/claim")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BillingDtos.EntitlementResponse> claim(
            @AuthenticationPrincipal SessionService.Principal principal,
            @Valid @RequestBody BillingDtos.ClaimRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(billing.claim(principal.workspaceId(), req.licenseId(), principal.memberId()));
    }
}
```

> NOTE: verify the `Member` email getter name (`getEmail()` assumed — converter-decrypted at read);
> the compiler flags it immediately if it differs. The email flows into the checkout URL only.

```java
// backend/src/main/java/com/cadence/api/BillingExceptionHandler.java
package com.cadence.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/** 032 -- claim/checkout error envelopes, scoped to BillingController (the CsvImport advice shape). */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BillingController.class)
public class BillingExceptionHandler {

    private static ResponseEntity<Map<String, Object>> envelope(HttpStatus status, String error) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        return ResponseEntity.status(status).body(b);
    }

    @ExceptionHandler(BillingExceptions.ClaimRejectedException.class)
    public ResponseEntity<Map<String, Object>> claimRejected(BillingExceptions.ClaimRejectedException e) {
        return envelope(HttpStatus.CONFLICT, e.code());
    }

    @ExceptionHandler(BillingExceptions.ClaimUnavailableException.class)
    public ResponseEntity<Map<String, Object>> claimUnavailable(BillingExceptions.ClaimUnavailableException e) {
        return envelope(HttpStatus.SERVICE_UNAVAILABLE, "billing_unavailable");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> serverError(RuntimeException e) {
        if (e instanceof org.springframework.security.access.AccessDeniedException
            || e instanceof org.springframework.security.core.AuthenticationException) {
            throw e;
        }
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "server_error");
    }
}
```

Append to `backend/src/main/java/com/cadence/domain/AuthEventType.java`, at the very end of the enum (after the F70 block, comma-continuing the previous last constant):

```java
    // 032 Billing (append-only -- never reorder)
    BILLING_CHECKOUT_STARTED,
    BILLING_LICENSE_CLAIMED,
    BILLING_ENTITLEMENT_UPDATED
```

Append to `backend/src/main/java/com/cadence/service/AuthAuditService.java` (three typed methods, the `roleChanged` shape — ids and outcome strings only, `Instant.now(clock)`):

```java
    /** 032: an Admin started a checkout session. Ids only -- never the email or the URL. */
    public void billingCheckoutStarted(String workspaceId, String actorMemberId) {
        AuthAuditEvent event = new AuthAuditEvent();
        event.setEventType(AuthEventType.BILLING_CHECKOUT_STARTED);
        event.setWorkspaceId(workspaceId);
        event.setMemberId(actorMemberId);
        event.setOutcome("checkout_started");
        event.setOccurredAt(Instant.now(clock));
        repository.save(event);
    }

    /** 032: a license was claim-bound to the workspace (FR-006). Ids only. */
    public void billingLicenseClaimed(String workspaceId, String actorMemberId) {
        AuthAuditEvent event = new AuthAuditEvent();
        event.setEventType(AuthEventType.BILLING_LICENSE_CLAIMED);
        event.setWorkspaceId(workspaceId);
        event.setMemberId(actorMemberId);
        event.setOutcome("license_claimed");
        event.setOccurredAt(Instant.now(clock));
        repository.save(event);
    }

    /** 032: entitlement changed from provider truth (webhook poke or nightly sweep). Ids + status only. */
    public void billingEntitlementUpdated(String workspaceId, String outcome) {
        AuthAuditEvent event = new AuthAuditEvent();
        event.setEventType(AuthEventType.BILLING_ENTITLEMENT_UPDATED);
        event.setWorkspaceId(workspaceId);
        event.setOutcome(outcome);
        event.setOccurredAt(Instant.now(clock));
        repository.save(event);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.BillingClaimIT' --tests 'com.cadence.rbac.RbacEndpointInventoryTest' --tests 'com.cadence.rbac.DenyByDefaultContractTest'`
Expected: PASS — the three internal endpoints all carry `@PreAuthorize`, so the inventory needs no edits for them.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cadence backend/src/test/java/com/cadence/billing
git commit -m "feat(billing): checkout-session + claim endpoints with race-safe binding (032 T4)"
```

---

### Task 5: Freemius webhook endpoint

**Files:**
- Create: `backend/src/main/java/com/cadence/api/FreemiusWebhookController.java`
- Modify: `backend/src/main/java/com/cadence/security/SecurityConfig.java` (add `/api/webhooks/billing/**` to the existing `webhookSecurityChain` matcher; update the class javadoc chain list)
- Modify: `backend/src/test/java/com/cadence/rbac/RbacEndpointInventoryTest.java` (allowlist entry)
- Test: `backend/src/test/java/com/cadence/billing/BillingWebhookIT.java`
- Test: `backend/src/test/java/com/cadence/billing/BillingWebhookChainIT.java`

**Interfaces:**
- Consumes: `BillingService.refreshByLicenseId` (T4), `BillingWebhookEventRepository` (T1), `BillingProperties.getWebhookSecret()` (T2).
- Produces: `POST /api/webhooks/billing/freemius` — 401 invalid/missing signature; 200 ack for replays, unbound licenses, irrelevant types, malformed-but-signed bodies; 503 when the provider re-fetch fails (Freemius retries; no dedup row is written so the retry reprocesses).

- [ ] **Step 1: Write the failing tests**

```java
// backend/src/test/java/com/cadence/billing/BillingWebhookIT.java
package com.cadence.billing;

import com.cadence.domain.BillingWebhookEvent;
import com.cadence.domain.EntitlementStatus;
import com.cadence.domain.WorkspaceEntitlement;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 032 Task 5 -- HMAC verify, replay suppression, poke->refresh, unbound ack (US3). */
class BillingWebhookIT extends BillingItBase {

    private static final String PATH = "/api/webhooks/billing/freemius";

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("test-billing-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String event(String eventId, String type, String licenseId) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"" + type + "\","
            + "\"objects\":{\"license\":{\"id\":\"" + licenseId + "\"}},"
            + "\"user_email\":\"SENTINEL@pii.test\"}";
    }

    @Test
    void invalidSignature_is401_andNothingProcessed() throws Exception {
        String body = event("E1", "license.cancelled", "L1");
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", "deadbeef").content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        assertThat(mongoTemplate.findAll(BillingWebhookEvent.class)).isEmpty();
    }

    @Test
    void boundLicenseEvent_refetchesTruth_andUpdatesEntitlement() throws Exception {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programLicense("L1", "{\"id\":\"L1\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":\"2026-08-30 00:00:00\",\"is_cancelled\":true}");
        String body = event("E2", "license.cancelled", "L1");
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", sign(body)).content(body))
            .andExpect(status().isOk());
        WorkspaceEntitlement e = mongoTemplate.findAll(WorkspaceEntitlement.class).get(0);
        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.CANCELLED);   // truth from API, not payload
        assertThat(mongoTemplate.findAll(BillingWebhookEvent.class)).hasSize(1);
    }

    @Test
    void replayedEventId_isIdempotent_noSecondProviderCall() throws Exception {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programLicense("L1", "{\"id\":\"L1\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":null,\"is_cancelled\":false}");
        String body = event("E3", "license.updated", "L1");
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", sign(body)).content(body)).andExpect(status().isOk());
        int callsAfterFirst = stub.requestCount();
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", sign(body)).content(body)).andExpect(status().isOk());
        assertThat(stub.requestCount()).isEqualTo(callsAfterFirst);
        assertThat(mongoTemplate.findAll(BillingWebhookEvent.class)).hasSize(1);
    }

    @Test
    void unboundLicense_andIrrelevantType_areAcked_withoutStateChange() throws Exception {
        String unbound = event("E4", "license.created", "L-unbound");
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", sign(unbound)).content(unbound))
            .andExpect(status().isOk());
        String irrelevant = "{\"id\":\"E5\",\"type\":\"user.updated\"}";
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", sign(irrelevant)).content(irrelevant))
            .andExpect(status().isOk());
        assertThat(mongoTemplate.findAll(WorkspaceEntitlement.class)).isEmpty();
    }

    @Test
    void providerDownDuringRefresh_is503_andNoDedupRow_soRetryReprocesses() throws Exception {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programStatus(500);
        String body = event("E6", "license.expired", "L1");
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", sign(body)).content(body))
            .andExpect(status().isServiceUnavailable());
        assertThat(mongoTemplate.findAll(BillingWebhookEvent.class)).isEmpty();
        WorkspaceEntitlement e = mongoTemplate.findAll(WorkspaceEntitlement.class).get(0);
        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.ACTIVE);      // never downgraded on error
    }
}
```

```java
// backend/src/test/java/com/cadence/billing/BillingWebhookChainIT.java
package com.cadence.billing;

import org.junit.jupiter.api.Test;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 032 -- the WebhookSecurityChainTest analogue: CSRF-exempt webhook, nothing else widened. */
class BillingWebhookChainIT extends BillingItBase {

    @Test
    void webhook_isCsrfExempt_badSignatureStillRejected401() throws Exception {
        mvc.perform(post("/api/webhooks/billing/freemius").contentType(APPLICATION_JSON)
                .header("X-Signature", "00").content("{}"))
            .andExpect(status().isUnauthorized()); // reached the controller without a CSRF token
    }

    @Test
    void internalEndpoints_stillRequireAuth() throws Exception {
        mvc.perform(get("/api/internal/billing/entitlement"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/members"))
            .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.BillingWebhook*'`
Expected: FAIL (404 / compile failure — controller and chain entry missing).

- [ ] **Step 3: Write the controller and security wiring**

```java
// backend/src/main/java/com/cadence/api/FreemiusWebhookController.java
package com.cadence.api;

import com.cadence.config.BillingProperties;
import com.cadence.domain.BillingWebhookEvent;
import com.cadence.integration.BillingApiException;
import com.cadence.repository.BillingWebhookEventRepository;
import com.cadence.service.BillingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 032 -- inbound Freemius webhook (FR-008/FR-009/FR-010), the EmailWebhookController posture:
 * unauthenticated-by-design (dedicated permitAll STATELESS CSRF-exempt chain in SecurityConfig;
 * @PreAuthorize("permitAll()") for the RBAC inventory), HMAC-SHA256 over the RAW body verified
 * constant-time BEFORE any parse or state change, fail-closed on a blank secret, generic 401 with
 * no detail. The event is a POKE: only id/type/license id are read; entitlement truth is re-fetched
 * from the API by BillingService. Processing order is refresh-then-record so a transient provider
 * failure returns 503 WITHOUT a dedup row -- Freemius retries and the retry reprocesses (refresh is
 * idempotent by construction). Payload bodies and the secret are never logged.
 */
@RestController
public class FreemiusWebhookController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HMAC_ALG = "HmacSHA256";
    private static final String SIGNATURE_HEADER = "X-Signature";

    private final BillingProperties props;
    private final BillingService billing;
    private final BillingWebhookEventRepository events;
    private final Clock clock;

    public FreemiusWebhookController(BillingProperties props, BillingService billing,
                                     BillingWebhookEventRepository events, Clock clock) {
        this.props = props;
        this.billing = billing;
        this.events = events;
        this.clock = clock;
    }

    @PostMapping("/api/webhooks/billing/freemius")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> receive(@RequestBody(required = false) String rawBody,
                                        @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        if (rawBody == null || !signatureValid(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            return ResponseEntity.ok().build(); // signed but malformed: ack, never 5xx-loop the provider
        }
        String eventId = root.path("id").asText(null);
        String type = root.path("type").asText("");
        if (eventId == null || !type.startsWith("license.")) {
            return ResponseEntity.ok().build(); // irrelevant event family: ack (FR-010)
        }
        if (events.existsByEventId(eventId)) {
            return ResponseEntity.ok().build(); // replay: idempotent no-op (FR-009)
        }
        String licenseId = root.path("objects").path("license").path("id").asText(null);
        if (licenseId == null) {
            licenseId = root.path("license_id").asText(null); // integration-pending: both shapes pinned
        }
        if (licenseId != null) {
            try {
                billing.refreshByLicenseId(licenseId); // unbound -> no-op inside (FR-010)
            } catch (BillingApiException e) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build(); // provider retries
            }
        }
        try {
            events.insert(new BillingWebhookEvent(eventId, type, licenseId, Instant.now(clock), "processed"));
        } catch (DuplicateKeyException ignored) {
            // concurrent duplicate delivery -- the other worker recorded it
        }
        return ResponseEntity.ok().build();
    }

    /** Constant-time HMAC-SHA256 of the raw body vs X-Signature (hex; optional sha256= prefix). */
    private boolean signatureValid(String body, String signature) {
        String secret = props.getWebhookSecret();
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
            return false; // fail-closed: an unconfigured secret never accepts an event
        }
        byte[] provided;
        try {
            int eq = signature.indexOf('=');
            provided = HexFormat.of().parseHex(eq >= 0 ? signature.substring(eq + 1) : signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, body), provided);
    }

    private static byte[] hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }
}
```

In `backend/src/main/java/com/cadence/security/SecurityConfig.java`:
1. Change the `webhookSecurityChain` matcher line from
   `.securityMatcher("/api/webhooks/email/**")` to
   `.securityMatcher("/api/webhooks/email/**", "/api/webhooks/billing/**")`.
2. Extend that bean's javadoc: the chain now routes both machine-webhook namespaces; the real gate for each is the in-controller HMAC (F22 email; 032 Freemius billing).
3. Update the class-header chain enumeration comment (lines ~25-37) to mention `/api/webhooks/billing/**` — CLAUDE.md treats that comment as contract documentation.

In `backend/src/test/java/com/cadence/rbac/RbacEndpointInventoryTest.java`, add to `ALLOWED_PREFIXES` (after the `"/api/webhooks/email/"` entry):

```java
        // 032: the Freemius billing webhook is unauthenticated-by-design -- the real gate is the
        // in-controller HMAC signature (X-Signature, product secret), not a session/role. Routed by
        // the same @Order(3) webhook chain; the handler additionally carries @PreAuthorize("permitAll()").
        "/api/webhooks/billing/",
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.BillingWebhook*' --tests 'com.cadence.rbac.RbacEndpointInventoryTest' --tests 'com.cadence.emaildelivery.WebhookSecurityChainTest'`
Expected: PASS (the email webhook chain test must stay green after the matcher edit).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cadence/api/FreemiusWebhookController.java backend/src/main/java/com/cadence/security/SecurityConfig.java backend/src/test/java/com/cadence
git commit -m "feat(billing): HMAC-verified Freemius webhook, poke-then-refetch (032 T5)"
```

---

### Task 6: Nightly reconciliation scheduler

**Files:**
- Create: `backend/src/main/java/com/cadence/scheduler/EntitlementReconciliationScheduler.java`
- Test: `backend/src/test/java/com/cadence/billing/EntitlementReconcileIT.java`

**Interfaces:**
- Consumes: `BillingService.refresh(WorkspaceEntitlement)` (T4 — throws `BillingApiException` on provider failure), `WorkspaceEntitlementRepository.findAll()`, `SchedulerCheckpointService.registerReplayAction/start/complete`.
- Produces: `EntitlementReconciliationScheduler` with `TASK_NAME = "billing-entitlement-reconcile"`, `public void sweep()` (also the replay action), `@Scheduled(cron = "0 0 4 * * *", zone = "UTC")` (03:00 and 03:30 are taken).

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/cadence/billing/EntitlementReconcileIT.java
package com.cadence.billing;

import com.cadence.domain.EntitlementStatus;
import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.scheduler.EntitlementReconciliationScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 032 Task 6 -- nightly re-verify: self-heal, error isolation, never-downgrade-on-error (US3). */
class EntitlementReconcileIT extends BillingItBase {

    @Autowired
    EntitlementReconciliationScheduler scheduler;

    @Test
    void sweep_selfHeals_missedCancellation() {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programLicense("L1", "{\"id\":\"L1\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":\"2026-08-30 00:00:00\",\"is_cancelled\":true}");
        scheduler.sweep();
        assertThat(mongoTemplate.findAll(WorkspaceEntitlement.class).get(0).getStatus())
            .isEqualTo(EntitlementStatus.CANCELLED);
    }

    @Test
    void sweep_providerDown_leavesStateUntouched_andCompletes() {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programStatus(500);
        scheduler.sweep(); // must not throw -- per-row isolation
        WorkspaceEntitlement e = mongoTemplate.findAll(WorkspaceEntitlement.class).get(0);
        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.ACTIVE);
        assertThat(e.getLastVerifiedAt()).isNull(); // untouched, not stamped
    }

    @Test
    void sweep_oneBadRow_doesNotStarveOthers() {
        seedTeam("ws-a", "LA", Instant.now(clock).plus(Duration.ofDays(30)));
        seedTeam("ws-b", "LB", Instant.now(clock).plus(Duration.ofDays(30)));
        // LA missing from the stub -> 404 (fatal) on that row; LB programmed and cancelled.
        stub.programLicense("LB", "{\"id\":\"LB\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":null,\"is_cancelled\":true}");
        scheduler.sweep();
        WorkspaceEntitlement b = mongoTemplate.findOne(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("workspaceId").is("ws-b")),
            WorkspaceEntitlement.class);
        assertThat(b.getStatus()).isEqualTo(EntitlementStatus.CANCELLED);
    }

    @Test
    void sweep_isIdempotent_onDoubleRun() {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programLicense("L1", "{\"id\":\"L1\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":null,\"is_cancelled\":false}");
        scheduler.sweep();
        scheduler.sweep(); // replay proxy -- no further effect
        assertThat(mongoTemplate.findAll(WorkspaceEntitlement.class)).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.EntitlementReconcileIT'`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Write the scheduler**

```java
// backend/src/main/java/com/cadence/scheduler/EntitlementReconciliationScheduler.java
package com.cadence.scheduler;

import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.repository.WorkspaceEntitlementRepository;
import com.cadence.service.BillingService;
import jakarta.annotation.PostConstruct;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 032 -- nightly entitlement re-verify against Freemius truth (FR-011/SC-002): missed webhooks
 * self-heal within 24h. 04:00 UTC (03:00 retention-scan and 03:30 interest-retention are taken).
 * Wrapped in the shared SchedulerCheckpointService (RUNNING -> COMPLETED + missed-fire replay);
 * the replay action is registered in @PostConstruct (before ApplicationReadyEvent -- the F00.2
 * lesson). Per-row isolation: a provider failure on one row never changes that row's state
 * (FR-011) and never starves the rest. Row count is bounded by paying workspaces, so findAll()
 * matches the SlaNudgeScheduler precedent.
 */
@Component
public class EntitlementReconciliationScheduler {

    public static final String TASK_NAME = "billing-entitlement-reconcile";

    private static final Logger log = LoggerFactory.getLogger(EntitlementReconciliationScheduler.class);

    private final SchedulerCheckpointService checkpoints;
    private final WorkspaceEntitlementRepository entitlements;
    private final BillingService billing;

    public EntitlementReconciliationScheduler(SchedulerCheckpointService checkpoints,
                                              WorkspaceEntitlementRepository entitlements,
                                              BillingService billing) {
        this.checkpoints = checkpoints;
        this.entitlements = entitlements;
        this.billing = billing;
    }

    @PostConstruct
    void registerReplay() {
        checkpoints.registerReplayAction(TASK_NAME, this::sweep);
    }

    /** 04:00 UTC nightly. The checkpoint makes it idempotent + replay-safe. */
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    public void scheduled() {
        sweep();
    }

    /** One re-verify pass (also the registered missed-fire replay action). */
    public void sweep() {
        checkpoints.start(TASK_NAME);
        try {
            int verified = 0;
            for (WorkspaceEntitlement e : entitlements.findAll()) {
                try {
                    billing.refresh(e);
                    verified++;
                } catch (RuntimeException ex) {
                    // Transient provider failure or one bad row: state untouched (FR-011), sweep continues.
                    log.warn("billing reconcile iteration failed (isolated) {}",
                        StructuredArguments.kv("workspaceId", e.getWorkspaceId()));
                }
            }
            if (verified > 0) {
                log.info("billing reconcile sweep {}", StructuredArguments.kv("verified", verified));
            }
        } finally {
            checkpoints.complete(TASK_NAME);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.EntitlementReconcileIT'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cadence/scheduler/EntitlementReconciliationScheduler.java backend/src/test/java/com/cadence/billing/EntitlementReconcileIT.java
git commit -m "feat(billing): nightly checkpointed entitlement reconciliation sweep (032 T6)"
```

---

### Task 7: Backend feature gates at the four initiation points

**Files:**
- Modify: `backend/src/main/java/com/cadence/service/AtsConnectionService.java` (gate `connect`; derived `pausedForPlan` on `Health`)
- Modify: `backend/src/main/java/com/cadence/api/AtsDtos.java` (`HealthResponse` gains `pausedForPlan`)
- Modify: `backend/src/main/java/com/cadence/api/AtsConnectionController.java` (map the new field in `toHealth`)
- Modify: `backend/src/main/java/com/cadence/api/AtsExceptionHandler.java` (specific 402 handler — see placement 6)
- Modify: `backend/src/main/java/com/cadence/scheduler/AtsSyncScheduler.java` (skip non-entitled)
- Modify: `backend/src/main/java/com/cadence/scheduler/NoShowDefenseScheduler.java` (gate stage 1 only)
- Modify: `backend/src/main/java/com/cadence/service/SlaNudgeService.java` (gate `scanWorkspace`)
- Test: `backend/src/test/java/com/cadence/billing/BillingGatesIT.java`

**Interfaces:**
- Consumes: `EntitlementService.requireFeature/hasFeature` (T3), `GatedFeature` (T1). Existing signatures: `AtsConnectionService.connect(String workspaceId, AtsProvider provider, String apiKey)`, `AtsSyncScheduler.sweep()` loop over `findByStatus(CONNECTED)`, `NoShowDefenseScheduler.sweep()` stage-1 loop calling `cascade.requestConfirmation(req, now)`, `SlaNudgeService.scanWorkspace(WorkspaceConfig cfg, Instant now)`.
- Produces: 402 `upgrade_required` on `POST /api/internal/ats/{provider}/connection` for Free workspaces; sweeps skip Free workspaces at initiation only. `AtsConnectionService.Health` and `AtsDtos.HealthResponse` records gain a trailing `boolean pausedForPlan` component.

**Gate placements (exact):**
1. `AtsConnectionService.connect(...)` — first statement: `entitlements.requireFeature(workspaceId, GatedFeature.ATS_INTEGRATIONS);` (inject `EntitlementService entitlements` via constructor). `disconnect` stays ungated — removing config is always allowed.
2. `AtsConnectionService` health projection — where `Health` records are built (in `listHealth`/`health`), compute `boolean pausedForPlan = conn.getStatus() == AtsConnectionStatus.CONNECTED && !entitlements.hasFeature(workspaceId, GatedFeature.ATS_INTEGRATIONS);` and pass it as the new trailing record component. Add the component to both records and update every constructor call site (compiler-driven).
3. `AtsSyncScheduler.sweep()` — first statement inside the `for (AtsConnection conn : connected)` loop, before the per-connection try: `if (!entitlements.hasFeature(conn.getWorkspaceId(), GatedFeature.ATS_INTEGRATIONS)) { continue; }` (inject `EntitlementService`). The gate goes inside the loop, never around `checkpoints.start/complete`.
4. `NoShowDefenseScheduler.sweep()` — stage-1 loop only, next to the existing `leadTime(...)` lookup, with a per-sweep memo so N rows in one workspace cost one query (the `wsCache` pattern):
```java
    Map<String, Boolean> noShowEntitled = new HashMap<>();
    // ... inside the stage-1 for loop, before cascade.requestConfirmation(req, now):
    if (!noShowEntitled.computeIfAbsent(req.getWorkspaceId(),
            ws -> entitlements.hasFeature(ws, GatedFeature.NO_SHOW_DEFENSE))) {
        continue;
    }
```
   Stages 2 (escalateUnconfirmed) and 3 (stampNoShow) are NOT gated — in-flight cascades complete (spec US2-AS3).
5. `SlaNudgeService.scanWorkspace(...)` — immediately after the existing `String ws = cfg.getWorkspaceId();` line: `if (!entitlements.hasFeature(ws, GatedFeature.SLA_NUDGES)) { return; }` (inject `EntitlementService`). Draft `approve(...)` stays ungated — an already-created draft is in-flight work. This composes with (does not replace) the `isConfigured()` skip.
6. `AtsExceptionHandler` — add a SPECIFIC handler so the 402 is never swallowed by that advice's scoped `@ExceptionHandler(RuntimeException)` catch-all (scoped advice can win the ordering tie against the global `EntitlementExceptionHandler`, and `RuntimeException` matches `UpgradeRequiredException` by superclass):

```java
    @ExceptionHandler(BillingExceptions.UpgradeRequiredException.class)
    public ResponseEntity<Map<String, Object>> upgradeRequired(BillingExceptions.UpgradeRequiredException e) {
        return envelope(HttpStatus.PAYMENT_REQUIRED, "upgrade_required");
    }
```

   Apply the same one-method addition to any future scoped advice whose controller calls a gated service. `BillingGatesIT.atsConnect_onFreeWorkspace_is402UpgradeRequired` is the regression net for this exact failure mode.

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/cadence/billing/BillingGatesIT.java
package com.cadence.billing;

import com.cadence.domain.GatedFeature;
import com.cadence.service.EntitlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 032 Task 7 -- the four initiation gates (US2). The ATS connect endpoint is asserted end-to-end
 * (402 envelope, SC-003); the three sweep gates are asserted at the service seam here, with the
 * full sweep behavior covered by each feature suite staying green (no-show/SLA/ATS ITs).
 */
class BillingGatesIT extends BillingItBase {

    @Autowired
    EntitlementService entitlements;

    @Test
    void atsConnect_onFreeWorkspace_is402UpgradeRequired() throws Exception {
        mvc.perform(post("/api/internal/ats/greenhouse/connection").cookie(adminCookie()).with(csrf())
                .contentType("application/json").content("{\"apiKey\":\"gh-key\"}"))
            .andExpect(status().isPaymentRequired())
            .andExpect(jsonPath("$.error", is("upgrade_required")));
    }

    @Test
    void atsConnect_onTeamWorkspace_passesTheGate() throws Exception {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        // The gate must not refuse; downstream credential verification proceeds as before
        // (its own outcome depends on the ATS stubs, so assert only "not 402" here).
        int status = mvc.perform(post("/api/internal/ats/greenhouse/connection").cookie(adminCookie()).with(csrf())
                .contentType("application/json").content("{\"apiKey\":\"gh-key\"}"))
            .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(402);
    }

    @Test
    void gateChecks_flipWithEntitlement() {
        assertThat(entitlements.hasFeature(WS, GatedFeature.ATS_INTEGRATIONS)).isFalse();
        assertThat(entitlements.hasFeature(WS, GatedFeature.NO_SHOW_DEFENSE)).isFalse();
        assertThat(entitlements.hasFeature(WS, GatedFeature.SLA_NUDGES)).isFalse();
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        assertThat(entitlements.hasFeature(WS, GatedFeature.ATS_INTEGRATIONS)).isTrue();
        assertThat(entitlements.hasFeature(WS, GatedFeature.NO_SHOW_DEFENSE)).isTrue();
        assertThat(entitlements.hasFeature(WS, GatedFeature.SLA_NUDGES)).isTrue();
    }
}
```

Also extend the EXISTING sweep suites with one skip test each (same file style as their current tests; each seeds its usual fixture but no entitlement, runs the sweep, and asserts the initiation did not happen — and for no-show, that an in-flight cascade still progresses):
- `backend/src/test/java/com/cadence/ats/` sync suite: a `CONNECTED` connection with no entitlement → after `scheduler.sweep()`, the ATS stub received zero sync requests; with `seedTeam`-equivalent insert → requests observed. (Reuse that suite's existing fixtures; insert the entitlement row via `mongoTemplate.insert(new WorkspaceEntitlement(){...})` following Task 1's setters.)
- `backend/src/test/java/com/cadence/noshow/` (or the suite housing `NoShowDefenseScheduler` tests): BOOKED request inside lead time, no entitlement → `confirmationRequestedAt` stays null; a request with `confirmationRequestedAt` already set and deadline passed → escalation still fires without entitlement.
- `backend/src/test/java/com/cadence/sla/` suite: configured workspace, silent candidate, no entitlement → no `SlaNudgeDraft` created; with entitlement → draft created (their existing creation test already covers the positive half — parameterize or add the negative twin).

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.BillingGatesIT'`
Expected: FAIL — connect returns non-402 (gate absent). New sweep skip-tests FAIL (initiation still happens).

- [ ] **Step 3: Apply the five gate placements listed above**

Constructor-inject `EntitlementService` into `AtsConnectionService`, `AtsSyncScheduler`, `NoShowDefenseScheduler`, `SlaNudgeService`. Add the gate lines exactly as specified in the placement list. Add `boolean pausedForPlan` as the trailing component of `AtsConnectionService.Health` and `AtsDtos.HealthResponse`, fix all constructor call sites (the compiler lists them), and populate it only in the health-projection paths (`false` wherever a Health is built for a non-CONNECTED row).

- [ ] **Step 4: Run tests to verify they pass — including the untouched feature suites**

Run: `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test --tests 'com.cadence.billing.*' --tests 'com.cadence.ats.*' --tests 'com.cadence.noshow.*' --tests 'com.cadence.sla.*'`
Expected: PASS. If an existing ATS/no-show/SLA test seeds no entitlement and now fails on the gate, that test represents a Team-plan behavior: seed a Team entitlement in its fixture (one `mongoTemplate.insert` in the base class `@BeforeEach` of that suite is acceptable and keeps the suite's intent).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(billing): enforce Team gates at the four initiation points (032 T7)"
```

---

### Task 8: Frontend — Billing service, page, route, nav

**Files:**
- Create: `frontend/src/app/features/admin/billing/billing.service.ts`
- Create: `frontend/src/app/features/admin/billing/billing.component.ts`
- Create: `frontend/src/app/features/admin/billing/billing.component.scss`
- Test: `frontend/src/app/features/admin/billing/billing.component.spec.ts`
- Modify: `frontend/src/app/app.routes.ts` (add `admin/billing` route)
- Modify: `frontend/src/app/core/nav/nav.config.ts` (Administration group entry)

**Interfaces:**
- Consumes: `environment.apiBaseUrl`, `ToastService` (`shared/ui/toast.service.ts`: `success/error/info(message)`), `authGuard`/`roleGuard` (`core/auth`), route `data: { seo: PRIVATE, shell: true }`.
- Produces: `EntitlementView { plan: 'FREE' | 'TEAM'; status: 'ACTIVE' | 'CANCELLED' | 'EXPIRED' | null; expiresAt: string | null; boundAt: string | null; }`; `BillingService.getEntitlement(): Observable<EntitlementView>`, `.createCheckoutSession(): Observable<{ checkoutUrl: string }>`, `.claim(licenseId: string): Observable<EntitlementView>`. Route `/admin/billing` reads the `license_id` query param for claim-on-return.

- [ ] **Step 1: Write the failing spec**

```ts
// frontend/src/app/features/admin/billing/billing.component.spec.ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';
import { BillingComponent } from './billing.component';
import { BillingService, EntitlementView } from './billing.service';
import { ToastService } from '../../../shared/ui/toast.service';

const FREE: EntitlementView = { plan: 'FREE', status: null, expiresAt: null, boundAt: null };
const TEAM: EntitlementView = { plan: 'TEAM', status: 'ACTIVE', expiresAt: '2027-01-15T10:30:00Z', boundAt: '2026-07-30T00:00:00Z' };

describe('BillingComponent (032 US1/US4)', () => {
  let billing: jasmine.SpyObj<BillingService>;
  let toast: jasmine.SpyObj<ToastService>;

  function create(queryParams: Record<string, string> = {}): ComponentFixture<BillingComponent> {
    TestBed.configureTestingModule({
      imports: [BillingComponent],
      providers: [
        { provide: BillingService, useValue: billing },
        { provide: ToastService, useValue: toast },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } } }
      ]
    });
    const fixture = TestBed.createComponent(BillingComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    billing = jasmine.createSpyObj<BillingService>('BillingService', ['getEntitlement', 'createCheckoutSession', 'claim']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
    billing.getEntitlement.and.returnValue(of(FREE));
  });

  it('shows the Free plan card with an upgrade action', () => {
    const el: HTMLElement = create().nativeElement;
    expect(el.textContent).toContain('Free');
    expect(el.querySelector('[data-test=upgrade]')).toBeTruthy();
  });

  it('upgrade fetches a checkout session and redirects externally', () => {
    billing.createCheckoutSession.and.returnValue(of({ checkoutUrl: 'https://checkout.example/x' }));
    const fixture = create();
    spyOn(fixture.componentInstance, 'navigateExternal');
    (fixture.nativeElement.querySelector('[data-test=upgrade]') as HTMLButtonElement).click();
    expect(fixture.componentInstance.navigateExternal).toHaveBeenCalledWith('https://checkout.example/x');
  });

  it('claims the license from the return query param and toasts success', () => {
    billing.claim.and.returnValue(of(TEAM));
    billing.getEntitlement.and.returnValue(of(TEAM));
    create({ license_id: 'L1' });
    expect(billing.claim).toHaveBeenCalledWith('L1');
    expect(toast.success).toHaveBeenCalled();
  });

  it('shows the typed claim error inline on refusal', () => {
    billing.claim.and.returnValue(throwError(() => ({ status: 409, error: { error: 'license_already_bound' } })));
    const fixture = create({ license_id: 'L1' });
    expect(fixture.componentInstance.error()).toBeTruthy();
  });

  it('on TEAM shows status and the customer-portal link instead of upgrade', () => {
    billing.getEntitlement.and.returnValue(of(TEAM));
    const el: HTMLElement = create().nativeElement;
    expect(el.querySelector('[data-test=upgrade]')).toBeFalsy();
    expect(el.querySelector('[data-test=portal-link]')).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run spec to verify it fails**

Run (from `frontend/`): `npx ng test --watch=false --include='**/billing/billing.component.spec.ts'`
Expected: FAIL (module not found).

- [ ] **Step 3: Write service and component**

```ts
// frontend/src/app/features/admin/billing/billing.service.ts
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface EntitlementView {
  plan: 'FREE' | 'TEAM';
  status: 'ACTIVE' | 'CANCELLED' | 'EXPIRED' | null;
  expiresAt: string | null;
  boundAt: string | null;
}

/** 032 -- billing API (spec US1/US4). Workspace scoping is server-side from the session cookie. */
@Injectable({ providedIn: 'root' })
export class BillingService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/internal/billing`;

  getEntitlement(): Observable<EntitlementView> {
    return this.http.get<EntitlementView>(`${this.base}/entitlement`);
  }

  createCheckoutSession(): Observable<{ checkoutUrl: string }> {
    return this.http.post<{ checkoutUrl: string }>(`${this.base}/checkout-session`, {});
  }

  claim(licenseId: string): Observable<EntitlementView> {
    return this.http.post<EntitlementView>(`${this.base}/claim`, { licenseId });
  }
}
```

```ts
// frontend/src/app/features/admin/billing/billing.component.ts
import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { BillingService, EntitlementView } from './billing.service';
import { ToastService } from '../../../shared/ui/toast.service';

/**
 * 032 Billing page (US1/US4). Admin-only internal screen (roleGuard; the F50/F51 internal-screen
 * precedent). Upgrade redirects to the Freemius HOSTED checkout -- no third-party script ever loads
 * (FR-005/SC-005). Returning from checkout lands here with ?license_id=..., which is claimed
 * server-side; the recovery field feeds the same claim for buyers who closed the tab (FR-007).
 * Payment methods / invoices / cancellation live in the Freemius customer portal (FR-015).
 */
@Component({
  selector: 'app-billing',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="page">
      <header class="page__head">
        <h1 i18n="@@billing.title">Billing &amp; plan</h1>
      </header>

      <p class="error alert alert--danger" role="alert" *ngIf="error()">{{ error() }}</p>

      <section class="card" *ngIf="entitlement() as e">
        <ng-container *ngIf="e.plan === 'FREE'; else team">
          <h2 i18n="@@billing.free.title">Free plan</h2>
          <p i18n="@@billing.free.body">
            Core scheduling, calendar sync, candidate links, dashboards, and GDPR tooling are always
            free. The Team plan adds ATS integrations, no-show defense, and SLA nudges.
          </p>
          <button class="btn btn--primary" data-test="upgrade" (click)="upgrade()" [disabled]="busy()"
                  i18n="@@billing.upgrade">Upgrade to Team</button>

          <h3 i18n="@@billing.recover.title">Already purchased?</h3>
          <p i18n="@@billing.recover.body">
            Paste the license ID from your Freemius receipt email to finish linking your purchase.
          </p>
          <div class="field">
            <label for="license" i18n="@@billing.recover.label">License ID</label>
            <input id="license" name="license" [(ngModel)]="recoveryLicenseId" />
          </div>
          <button class="btn btn--outline" data-test="recover" (click)="claim(recoveryLicenseId)"
                  [disabled]="busy() || !recoveryLicenseId" i18n="@@billing.recover.submit">Link license</button>
        </ng-container>

        <ng-template #team>
          <h2 i18n="@@billing.team.title">Team plan</h2>
          <p>
            <span class="badge badge--ok" i18n="@@billing.team.status">Status</span>
            {{ e.status }}
            <ng-container *ngIf="e.expiresAt">
              <span i18n="@@billing.team.renews">- current period ends</span>
              {{ e.expiresAt | date: 'mediumDate' }}
            </ng-container>
          </p>
          <p i18n="@@billing.team.portal.body">
            Invoices, payment methods, and cancellation are managed in the Freemius customer portal.
          </p>
          <a class="btn btn--link" data-test="portal-link" href="https://users.freemius.com"
             target="_blank" rel="noopener" i18n="@@billing.team.portal">Open customer portal</a>
        </ng-template>
      </section>
    </div>
  `,
  styleUrls: ['./billing.component.scss']
})
export class BillingComponent implements OnInit {
  private readonly billing = inject(BillingService);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);

  readonly entitlement = signal<EntitlementView | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  recoveryLicenseId = '';

  ngOnInit(): void {
    const licenseId = this.route.snapshot.queryParamMap.get('license_id');
    if (licenseId) {
      this.claim(licenseId);
    }
    this.load();
  }

  load(): void {
    this.billing.getEntitlement().subscribe({
      next: (e) => this.entitlement.set(e),
      error: () => this.error.set($localize`:@@billing.load.error:Could not load your plan. Retry shortly.`)
    });
  }

  upgrade(): void {
    this.busy.set(true);
    this.billing.createCheckoutSession().subscribe({
      next: (r) => this.navigateExternal(r.checkoutUrl),
      error: () => {
        this.busy.set(false);
        this.error.set($localize`:@@billing.checkout.error:Could not start checkout. Retry shortly.`);
      }
    });
  }

  claim(licenseId: string): void {
    this.busy.set(true);
    this.error.set(null);
    this.billing.claim(licenseId).subscribe({
      next: (e) => {
        this.busy.set(false);
        this.entitlement.set(e);
        this.toast.success($localize`:@@billing.claim.ok:Your workspace is now on the Team plan.`);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(this.claimErrorText(err?.error?.error));
      }
    });
  }

  /** Extracted for testability -- full-page redirect to the hosted checkout. */
  navigateExternal(url: string): void {
    window.location.assign(url);
  }

  private claimErrorText(code: string | undefined): string {
    switch (code) {
      case 'license_already_bound':
        return $localize`:@@billing.claim.bound:This license is already linked to another workspace.`;
      case 'already_upgraded':
        return $localize`:@@billing.claim.upgraded:This workspace already has an active Team plan.`;
      case 'wrong_plan':
      case 'invalid_license':
        return $localize`:@@billing.claim.invalid:That license ID was not recognized. Check your receipt email.`;
      case 'license_inactive':
        return $localize`:@@billing.claim.inactive:This license is no longer active.`;
      default:
        return $localize`:@@billing.claim.error:Could not link the license. Retry shortly.`;
    }
  }
}
```

Create `billing.component.scss` with a minimal footprint (the global kit does the styling; keep under the 2 kB budget):

```scss
.field { max-width: 24rem; }
h3 { margin-top: var(--space-6); }
```

Add to `frontend/src/app/app.routes.ts`, next to the other `admin/*` routes (same shape as `admin/workspace`):

```ts
  {
    // 032 Billing & plan (US1/US4). Admin-only internal screen; checkout returns land here
    // with ?license_id=... for the claim (FR-006). No third-party script (FR-005).
    path: 'admin/billing',
    canActivate: [authGuard, roleGuard('ADMIN')],
    data: { seo: PRIVATE, shell: true },
    loadComponent: () =>
      import('./features/admin/billing/billing.component').then((m) => m.BillingComponent)
  },
```

Add to the `Administration` group in `frontend/src/app/core/nav/nav.config.ts` (after the ATS entry):

```ts
      { path: '/admin/billing', roles: ['ADMIN'],
        label: $localize`:@@launch.billing:Billing & plan`,
        desc: $localize`:@@launch.billing.desc:Your plan, upgrades, and the customer portal.` }
```

- [ ] **Step 4: Run spec to verify it passes**

Run: `npx ng test --watch=false --include='**/billing/billing.component.spec.ts'`
Expected: PASS (5 specs).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/features/admin/billing frontend/src/app/app.routes.ts frontend/src/app/core/nav/nav.config.ts
git commit -m "feat(billing): admin Billing page with hosted-checkout redirect + claim (032 T8)"
```

---

### Task 9: Frontend — upgrade prompt, gated surfaces, 402 toast

**Files:**
- Create: `frontend/src/app/shared/ui/upgrade-prompt.component.ts`
- Test: `frontend/src/app/shared/ui/upgrade-prompt.component.spec.ts`
- Modify: `frontend/src/app/core/auth/auth.interceptor.ts` (402 branch)
- Modify: `frontend/src/app/core/auth/auth.interceptor.spec.ts` (402 spec)
- Modify: `frontend/src/app/features/admin/ats/ats.service.ts` (`AtsHealth` gains `pausedForPlan: boolean`)
- Modify: `frontend/src/app/features/admin/ats/ats-integration.component.ts` (prompt when FREE; paused badge)
- Modify: `frontend/src/app/features/admin/workspace/workspace-settings.component.ts` (prompt beside the no-show timing settings when FREE)
- Modify: `frontend/src/app/features/scheduling/scheduling.component.ts` (prompt on the SLA-drafts panel when FREE)

**Interfaces:**
- Consumes: `BillingService.getEntitlement()` + `EntitlementView` (T8), `AuthService.member$` (`toSignal`, `m.role === 'ADMIN'`), `ToastService`.
- Produces: `<app-upgrade-prompt [featureLabel]="...">` — selector `app-upgrade-prompt`, single required input `featureLabel: string`.

- [ ] **Step 1: Write the failing specs**

```ts
// frontend/src/app/shared/ui/upgrade-prompt.component.spec.ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { UpgradePromptComponent } from './upgrade-prompt.component';
import { AuthService } from '../../core/auth/auth.service';
import { MemberSummary } from '../../core/auth/auth.models';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

function memberWith(role: MemberSummary['role']): MemberSummary {
  return { memberId: 'm1', workspaceId: 'ws1', role, displayName: 'M', email: 'm@x.com', workspaceConfigured: true };
}

describe('UpgradePromptComponent (032 FR-016)', () => {
  let member$: BehaviorSubject<MemberSummary | null>;
  let fixture: ComponentFixture<UpgradePromptComponent>;
  let el: HTMLElement;

  function create(role: MemberSummary['role']): void {
    member$ = new BehaviorSubject<MemberSummary | null>(memberWith(role));
    TestBed.configureTestingModule({
      imports: [UpgradePromptComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: { member$: member$.asObservable() } }]
    });
    fixture = TestBed.createComponent(UpgradePromptComponent);
    fixture.componentRef.setInput('featureLabel', 'ATS integrations');
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  }

  afterEach(() => detachFromBody(el));

  it('admins get a link to the Billing page', () => {
    create('ADMIN');
    const link = el.querySelector('a[data-test=upgrade-link]') as HTMLAnchorElement;
    expect(link).toBeTruthy();
    expect(link.getAttribute('href')).toContain('/admin/billing');
  });

  it('non-admins get the contact-your-admin notice, no link', () => {
    create('RECRUITER');
    expect(el.querySelector('a[data-test=upgrade-link]')).toBeFalsy();
    expect(el.textContent).toContain('workspace admin');
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    create('ADMIN');
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
```

Add to `frontend/src/app/core/auth/auth.interceptor.spec.ts` (same TestBed shape as its existing specs, with `ToastService` spy added to providers):

```ts
  it('402 surfaces a toast and rethrows without redirecting', (done) => {
    http.get('/api/internal/ats/greenhouse/connection').subscribe({
      error: () => {
        expect(toast.error).toHaveBeenCalled();
        expect(router.navigate).not.toHaveBeenCalled();
        done();
      }
    });
    ctrl.expectOne('/api/internal/ats/greenhouse/connection')
      .flush({ error: 'upgrade_required' }, { status: 402, statusText: 'Payment Required' });
  });
```

- [ ] **Step 2: Run specs to verify they fail**

Run: `npx ng test --watch=false --include='**/upgrade-prompt.component.spec.ts' --include='**/auth.interceptor.spec.ts'`
Expected: FAIL (component missing; toast spec fails).

- [ ] **Step 3: Write the component and wire the surfaces**

```ts
// frontend/src/app/shared/ui/upgrade-prompt.component.ts
import { Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { AuthService } from '../../core/auth/auth.service';

/**
 * 032 shared paywall prompt (FR-016). Rendered on gated surfaces when the workspace is FREE:
 * Admins get the path to Billing; everyone else gets the contact-your-admin notice. Presentational;
 * the consumer decides WHEN to show it (entitlement lookup stays in the feature component).
 */
@Component({
  selector: 'app-upgrade-prompt',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="card upgrade-prompt">
      <h2 i18n="@@upgrade.title">{{ featureLabel }} is part of the Team plan</h2>
      @if (isAdmin()) {
        <p i18n="@@upgrade.admin.body">
          Upgrade your workspace to turn this on. Everything configured here is kept and resumes on upgrade.
        </p>
        <a class="btn btn--primary" data-test="upgrade-link" routerLink="/admin/billing"
           i18n="@@upgrade.admin.cta">View plans</a>
      } @else {
        <p i18n="@@upgrade.member.body">
          This feature needs the Team plan. Ask your workspace admin about upgrading.
        </p>
      }
    </div>
  `,
  styles: [`
    .upgrade-prompt { text-align: center; padding: var(--space-6); }
    .upgrade-prompt h2 { margin-top: 0; font-size: var(--step-1); }
  `]
})
export class UpgradePromptComponent {
  @Input({ required: true }) featureLabel!: string;

  private readonly auth = inject(AuthService);
  private readonly member = toSignal(this.auth.member$, { initialValue: null });

  isAdmin(): boolean {
    return this.member()?.role === 'ADMIN';
  }
}
```

Interceptor: in `frontend/src/app/core/auth/auth.interceptor.ts`, inject `const toast = inject(ToastService);` at the top of `authErrorInterceptor` (import from `'../../shared/ui/toast.service'`) and add, between the 401 and 403 branches:

```ts
      } else if (err?.status === 402) {
        // 032: gated action on a FREE workspace (FR-013). Surface, never redirect --
        // proactive prompts are the primary UX; this is the safety net.
        toast.error($localize`:@@upgrade.toast:This feature requires the Team plan.`);
```

Gated surfaces (each component: inject `BillingService`, add `readonly plan = signal<'FREE' | 'TEAM' | null>(null);`, call `this.billing.getEntitlement().subscribe({ next: (e) => this.plan.set(e.plan), error: () => this.plan.set(null) });` in `ngOnInit`, import `UpgradePromptComponent` in the `imports` array):

1. **`ats-integration.component.ts`** — wrap the provider-connect/config section (the block rendering the per-provider connect forms) in
   `@if (plan() === 'FREE') { <app-upgrade-prompt featureLabel="ATS integrations" i18n-featureLabel="@@upgrade.ats" /> } @else { ...existing block... }`.
   Add `pausedForPlan: boolean` to the `AtsHealth` interface in `ats.service.ts`; where the connection status renders, add
   `@if (p.pausedForPlan) { <span class="badge badge--warn" i18n="@@ats.pausedForPlan">Paused - requires Team plan</span> }`
   so a previously configured connection reads retained-but-paused (US2-AS2).
2. **`workspace-settings.component.ts`** — directly above the no-show timing fields (`confirmationLeadTime` / escalation deadline inputs), add
   `@if (plan() === 'FREE') { <app-upgrade-prompt featureLabel="No-show defense" i18n-featureLabel="@@upgrade.noshow" /> }`
   (settings stay editable — config is retained; only initiation is off).
3. **`scheduling.component.ts`** — at the top of the SLA nudge drafts panel (where `SlaNudgeService` drafts render), add
   `@if (plan() === 'FREE') { <app-upgrade-prompt featureLabel="SLA nudges" i18n-featureLabel="@@upgrade.sla" /> }`.

If a surface's existing spec now needs `BillingService`, stub it with `{ getEntitlement: () => of({ plan: 'TEAM', status: 'ACTIVE', expiresAt: null, boundAt: null }) }` so existing behavior tests keep their meaning.

- [ ] **Step 4: Run the affected specs**

Run: `npx ng test --watch=false --include='**/upgrade-prompt.component.spec.ts' --include='**/auth.interceptor.spec.ts' --include='**/ats-integration.component.spec.ts' --include='**/workspace-settings.component.spec.ts' --include='**/scheduling.component.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app
git commit -m "feat(billing): upgrade-prompt on gated surfaces + global 402 toast (032 T9)"
```

---

### Task 10: Pricing page copy — Free + Team

**Files:**
- Modify: `frontend/src/content/pages/pricing/body.html`
- Modify: `frontend/src/content/pages/pricing/meta.json` (description mentioning early access → Free/Team)

**Interfaces:**
- Consumes: the marketing-pages build (`scripts/seo-build-articles.mjs` `loadMarketingPages`); authoring rules from `frontend/src/content/pages/AUTHORING.md` (fragment starts at `<h2>`, pure-ASCII punctuation, no `<h1>`/`<script>`/`on*=`, root-relative or https links only).

- [ ] **Step 1: Rewrite `body.html`** (replace the whole early-access fragment; keep the internal links that already exist on the page so no other page's links break):

```html
<h2>Two plans, one honest split</h2>
<p>
  Cadence has a Free plan and a Team plan, billed per workspace - never per candidate. Candidates
  never need an account and are never a billable seat. Checkout, invoices, VAT, and cancellation are
  handled by Freemius, our merchant of record; Cadence never sees or stores card details.
</p>

<h2>Free plan</h2>
<p>Everything a small pipeline needs to schedule without chaos, free for as long as you like:</p>
<ul>
  <li>Candidate self-scheduling, rescheduling, and cancellation links.</li>
  <li><a href="/integrations/google-calendar/">Google Calendar</a> and
      <a href="/integrations/microsoft-365/">Microsoft 365</a> sync.</li>
  <li>Private candidate status pages and consent-gated email with templates.</li>
  <li>Standalone CSV import, interviewer scorecards, pipeline views, and dashboards.</li>
  <li>GDPR tooling: encryption at rest, retention automation, one-click erasure - compliance is
      never paywalled.</li>
</ul>

<h2>Team plan</h2>
<p>For teams running a serious hiring operation, Team adds the automation layer:</p>
<ul>
  <li><a href="/integrations/greenhouse/">Greenhouse</a> and
      <a href="/integrations/lever/">Lever</a> ATS sync with scheduling write-back.</li>
  <li>No-show defense: confirmation cascades and recruiter alerts before interviews slip.</li>
  <li>SLA nudges that catch silent candidates before the pipeline stalls.</li>
</ul>
<p>
  Upgrading takes one checkout from workspace settings. Downgrading never deletes anything:
  connections and settings are kept, paused, and resume the moment you upgrade again.
</p>

<h2>Early-access workspaces</h2>
<p>
  Workspaces from the early-access program moved to the Free plan when billing launched, with
  nothing deleted and nothing switched on silently. Upgrading to Team is a single checkout from
  workspace settings whenever the automation layer earns its keep for your team.
</p>

<h2>Get started</h2>
<p>
  Sign in or request access from the <a href="/">Cadence home page</a>. If you want to evaluate the
  thinking before the tool, the <a href="/features/">feature overview</a> and our
  <a href="/resources/">recruiting guides</a> are the fastest way in, and the
  <a href="/vs/calendly/">comparison with Calendly</a> explains where a recruiting-native scheduler
  earns its keep over a general-purpose one.
</p>
```

- [ ] **Step 2: Update `meta.json`** — keep the existing JSON shape (read the file first); update only wording: title stays pricing-focused; description becomes e.g. `"Cadence pricing: a Free plan for core scheduling and a per-workspace Team plan that adds ATS sync, no-show defense, and SLA nudges."` Do not add fields.

- [ ] **Step 3: Run the content pipeline tests**

Run (from repo root): `node --test scripts/seo-build-articles.node.test.mjs`
Expected: PASS (lint rules: ASCII punctuation, no `<h1>`, allowed links).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/content/pages/pricing
git commit -m "feat(billing): pricing page describes the Free/Team split (032 T10)"
```

---

### Task 11: Full-suite verification

**Files:** none (verification only; fix regressions in place).

- [ ] **Step 1: Full backend suite**

Run (from `backend/`): `JAVA_HOME=C:/jdk-24.0.1 DOCKER_HOST=npipe:////./pipe/docker_engine ./gradlew test`
Expected: PASS — pay particular attention to `RbacEndpointInventoryTest`, `DenyByDefaultContractTest`, the ATS/no-show/SLA suites (gates), and any log/PII scan tests (the stub's SENTINEL fields must not appear in captured logs).

- [ ] **Step 2: Full frontend suite**

Run (from `frontend/`): `npx ng test --watch=false`
Expected: PASS.

- [ ] **Step 3: Content pipeline**

Run (from repo root): `node --test scripts/seo-build-articles.node.test.mjs`
Expected: PASS.

- [ ] **Step 4: Commit any fixes, then final commit**

```bash
git add -A
git commit -m "test(billing): full-suite verification fixes (032 T11)"
```

(Skip the commit if the tree is clean.)

---

## Rollout notes (operational, not code — for the release PR description)

1. Freemius dashboard (manual): create the product, one paid "Team" plan with its price, set the checkout return URL to `https://cadenceapp.cc/admin/billing`, register the webhook URL `https://cadenceapp.cc/api/webhooks/billing/freemius`, note the product secret key.
2. `fly secrets set FREEMIUS_PRODUCT_ID=... FREEMIUS_TEAM_PLAN_ID=... FREEMIUS_API_BEARER=... FREEMIUS_WEBHOOK_SECRET=...` before deploying.
3. Deploy = launch: every workspace without an entitlement is Free from the first request (FR-022, no migration). Pre-launch notice email to early-access workspaces is a comms task.
4. Promotion from the integration-pending stub shapes (license JSON fields, webhook payload shape, checkout `return_url` parameter name) to live Freemius is a separately-reviewed step, exactly like the F40/F41 ATS promotion.
