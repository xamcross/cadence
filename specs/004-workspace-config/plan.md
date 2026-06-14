# Implementation Plan: Workspace Setup & Configuration

**Branch**: `004-workspace-config` | **Date**: 2026-06-14 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/004-workspace-config/spec.md`

## Summary

Make the workspace a first-class, configurable thing. Today the workspace exists only as a `workspaceId` string threaded through `members`/`sessions`; F03 introduces a single persisted **`workspaceConfig`** document per workspace plus a separate **`workspaceLogo`** document for branding bytes, and exposes an **Admin-only** configuration surface plus a **public** candidate-facing branding read.

Two journeys ship as one increment: (1) a **first-run setup wizard** (name, IANA time zone, working hours, default SLA silence window, data-retention period with a mandatory GDPR acknowledgment) that atomically transitions the workspace *unconfigured → configured*; (2) **ongoing configuration** of those operational settings, branding (logo + brand colour), the email-sending domain + provider credential, and per-template lock state. Settings are the source of truth later features consume (F04 retention, F12 hours/tz, F14/F30 branding, F21 template locks, F22 email, F31 SLA window).

The load-bearing engineering decisions: the provider **credential is two separate controls** — encryption-at-rest by registering the existing `PiiStringConverter` for the new field in `MongoPiiConfig` (non-deterministic AES-256-GCM, randomized IV), and never-return by making it a **write-only field never mapped onto any read DTO**; the **logo lives in its own collection** (not inlined) so the per-request config doc stays small and well under the 16 MB BSON limit; writes use **targeted single-document `$set`** (no whole-doc read-modify-write → no lost update), and first-run completion uses a **conditional upsert keyed by `workspaceId`** so concurrent wizard submissions resolve to exactly one configured record. Authorization reuses F02 method security (class-level `@PreAuthorize("hasRole('ADMIN')")`); all config endpoints sit under the internal prefix so the F02 `RbacEndpointInventoryTest` enforces their role. **No new runtime dependency, no topology change** — single Spring Boot instance + MongoDB only.

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security w/ method security, actuator, aop); Mongock 5.4.4; logstash-logback-encoder 9.0. **No new backend or frontend runtime dependency** — logo validation uses JDK `javax.imageio.ImageIO` + magic-byte inspection; credential crypto reuses the existing `PiiCrypto`/`PiiStringConverter`. Test-only: `spring-security-test` (already present) for per-role authority post-processors.
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). Adds two collections — **`workspaceConfig`** (one doc/workspace) and **`workspaceLogo`** (one doc/workspace, raster bytes ≤ 1 MB). Reuses `authAuditLog` (extended) and the F01 `members`/`sessions` for the actor context.
**Testing**: JUnit 5 + Testcontainers (integration, incl. raw-driver ciphertext assertion + restart-persistence), MockMvc (per-role 5×4 contract matrix + secret log scan), Mockito (unit — validation bounds), Jasmine (frontend unit — settings/wizard guards per role), Playwright (E2E — wizard + non-admin redirect).
**Target Platform**: Fly.io single Machine (backend JAR), Cloudflare Pages (Angular SPA), MongoDB Atlas.
**Project Type**: Web application (Angular frontend + Spring Boot backend).
**Performance Goals**: No added per-request cost on hot paths; the operational config doc is small and read on demand. `/me` gains exactly one cheap `exists`-style read for the `workspaceConfigured` boolean (shell routing). Logo bytes are fetched only by the branding/logo reads, never on the config read.
**Constraints**: Single instance + MongoDB only — no Redis/queue/cache, no object store, no in-process settings cache (constitution §IV / C2); provider credential encrypted at rest (non-deterministic, no derived value) and structurally never returned/logged (§VIII, FR-016..FR-018); logo from a raster allow-list validated by magic byte, SVG rejected (FR-012); zero secret/PII in logs incl. DEBUG/TRACE (FR-018/FR-025); append-only audit (FR-026); zero tool downloads (§X); any new `.ps1` pure ASCII (§V).
**Scale/Scope**: MVP single workspace (tens–hundreds of members); 6 user stories, 26 FRs, 13 SCs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ PASS — F03 is Tier-0 Foundation MVP (§5.4 FR-20). Two FR-20 elements explicitly deferred & stakeholder-confirmed (retention *enforcement* → F04; workspace *language* → later), recorded in spec + backlog. |
| **C2** | New service, queue, or replica? | ✅ PASS — two new MongoDB collections on the existing instance; logo bytes in Mongo (not an object store); credential encrypted in-doc; no cache/broker/replica, no in-process settings cache. |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ PASS — F03 stores no candidate PII. The one **public** read (branding) exposes only logo + brand colour (public-by-design for candidate pages); all settings reads are Admin-only (FR-008). The retention *policy* strengthens later candidate-data minimization (F04). |
| **C4** | Dependency outside the fixed stack? | ✅ PASS — **zero new dependencies**; reuses Spring Security method security, the F01 PII crypto, and JDK `ImageIO`. |
| **C5** | New/modified Windows scripts contain non-ASCII? | ✅ PLANNED — no new `.ps1` expected; any change byte-scanned before done. |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ DONE+SCHEDULED — spec reviewed by 4 roles; this plan reviewed by ≥3 roles (user-requested) before tasks; final review at task close. |
| **C7** | Downloads any build tool/runtime/CLI? | ✅ PASS — cached Gradle 9.4.0, installed JDK 21, existing Angular CLI; no downloads. |

**Initial gate: PASS** (no new dependency, no topology change, no stack change).

### Post-Design Re-Check (after Phase 1 + §VI plan review)

Re-evaluated after the multi-role plan review (Security/GDPR, Backend/DevOps, QA, Front-End — all APPROVE-WITH-CHANGES / CHANGES-REQUESTED). **Result: PASS, unchanged gate status.** Disposition of all findings logged in `checklists/requirements.md`; load-bearing corrections folded into `research.md`/`data-model.md`/`contracts/`. Two review **BLOCKERs** resolved in the artifacts:
1. **Upsert idempotency hole** (BE-1) — a pre-existing *unconfigured* doc would let two concurrent setups both `$set`/double-audit. Fixed by the invariant that `GET /config` and `/me` reads are strictly **read-only** (never get-or-create), so the wizard upsert is the only inserter and the concurrent loser always hits the unique-index `DuplicateKeyException` → 409 (research D4).
2. **CI secret log-scan does not exist** (SEC-1) — the current `ci.yml` greps emails only. SC-005 now requires **extending** the CI scan with a secret pattern set + a sentinel credential token (research D8); tasked as a `ci.yml` change.

Key gate confirmations:
- **C2 holds** — credential converter is a per-`(class,field)` registration on the existing `MongoCustomConversions` bean (no new bean/infra); logo in its own collection (not GridFS, ≤ 1 MB << 16 MB); singleton via conditional upsert + read-only reads; no cache introduced.
- **C3 holds (disposition refined)** — the public branding endpoint serves only the two brand attributes (logo + colour), which are public-by-design on candidate pages; verified it cannot serialize the credential (structurally `@JsonIgnore`/write-only) or any other setting/state. The logo response carries `nosniff` + bounded cache headers (SEC-2).
- **C4 / C7 unchanged** — zero new runtime dependencies (logo validation/headers are JDK + Spring), zero downloads.
- Other corrections folded in: structural never-return via `@JsonIgnore` + non-record request DTO (BE-3/SEC); header-first logo dimension check to defeat decompression bombs (BE-4c/QA-2); immutable `configuredAt`/`retentionAcknowledgedAt` with the audit row as the authoritative GDPR artifact (SEC-2); cold-template restart-persistence test mechanism + CountDownLatch concurrency tests (QA-1/QA-5); frontend `auth.models.ts`/`role.guard.spec` updates + ngOnInit-subscribe redirect + multipart upload a11y (FE-1..5). No constitution gate moved to FAIL.

## Project Structure

### Documentation (this feature)

```text
specs/004-workspace-config/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions & rationale (D1..D12)
├── data-model.md        # Phase 1 — entities, indexes, audit extension, validation rules
├── quickstart.md        # Phase 1 — local run + manual verification
├── contracts/
│   └── workspace-api.md # Phase 1 — REST endpoint contracts
├── checklists/
│   └── requirements.md  # Spec quality + spec/plan review logs
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── api/
│   ├── WorkspaceConfigController.java   # NEW — class @PreAuthorize("hasRole('ADMIN')"); /api/internal/workspace/**
│   │                                    #   GET config; POST setup; PATCH config; PUT branding; POST/DELETE logo;
│   │                                    #   PUT email; DELETE email/credential; PUT templates/{key}/lock
│   ├── PublicBrandingController.java     # NEW — GET /api/public/workspace/branding + /logo (no session; non-PII only)
│   ├── WorkspaceDtos.java               # NEW — request/response records; credential is WRITE-ONLY (request only),
│   │                                    #   response carries credentialSet:boolean (never the value)
│   ├── WorkspaceExceptionHandler.java   # NEW — @RestControllerAdvice → {error,message} for validation /
│   │                                    #   already-configured (409) / not-found; reuses F01/F02 envelope shape
│   ├── AuthController.java              # MODIFIED — /me adds workspaceConfigured boolean (shell routing, D3)
│   └── AuthDtos.java                    # MODIFIED — MemberSummary gains workspaceConfigured
├── domain/
│   ├── WorkspaceConfig.java            # NEW — @Document("workspaceConfig"); scalar settings, brandColor,
│   │                                    #   hasLogo flag, emailSendingDomain, emailProviderCredential (write-only,
│   │                                    #   encrypted), templateLocks Map<String,Boolean>, retention + ack, configuredAt
│   ├── WorkspaceLogo.java              # NEW — @Document("workspaceLogo"); byte[] bytes, contentType, size
│   ├── WorkingHours.java               # NEW — embedded value (LocalTime start, end)
│   ├── AuthEventType.java              # MODIFIED — add WORKSPACE_CONFIGURED, WORKSPACE_CONFIG_CHANGED
│   └── AuthAuditEvent.java             # MODIFIED — add nullable oldValue,newValue (non-PII strings; retention old/new)
├── repository/
│   ├── WorkspaceConfigRepository.java  # NEW — findByWorkspaceId; existsByWorkspaceIdAndConfiguredAtNotNull
│   └── WorkspaceLogoRepository.java     # NEW — findByWorkspaceId
├── service/
│   ├── WorkspaceConfigService.java     # NEW — completeSetup (conditional upsert + ack gate, D4), updateSettings
│   │                                    #   (targeted $set, D4), validation (D7), isConfigured, email set/rotate/unset,
│   │                                    #   templateLock; audits via AuthAuditService (D8)
│   ├── BrandingService.java             # NEW — validate logo (size+magic-byte+ImageIO dims, D6), store/replace/clear,
│   │                                    #   resolve per-attribute defaults (D5), serve bytes
│   └── AuthAuditService.java           # MODIFIED — workspaceConfigured(...) + configChanged(setting,old,new) (D8)
├── config/
│   ├── MongoPiiConfig.java             # MODIFIED — register PiiStringConverter for WorkspaceConfig credential field (D2)
│   └── migration/
│       └── ChangeUnit004_WorkspaceConfigIndexes.java  # NEW — unique {workspaceId} on both new collections
└── (no security/* change — reuses the F02 @Order(3) chain + RestAccessDeniedHandler unchanged)

backend/src/test/java/com/cadence/
└── workspace/
    ├── WorkspaceConfigServiceTest.java          # Unit (Mockito): PARAMETERIZED boundary cases (SC-008, QA-4) — SLA {0,31 reject;
    │                                             #   1,30 accept}; retention {0,29,3651 reject; 30,3650 accept}; hours {end<=start reject};
    │                                             #   tz non-IANA reject; colour shorthand/named/rgb reject; ack gate
    ├── WorkspaceSetupIntegrationTest.java        # US1: persist+configured; ack-missing refused→unconfigured (SC-003);
    │                                             #   re-completion via direct API refused 409 (FR-006); concurrent first-run via
    │                                             #   CountDownLatch (F02 last-admin pattern, N>=20) → exactly one configuredAt +
    │                                             #   BOTH attempts audited (SC-009, QA-5); restart-persistence via a COLD MongoTemplate
    │                                             #   (new MongoClient + fresh MongoPiiConfig converter, NOT a JVM restart) reads back
    │                                             #   unchanged incl. credential decrypt (SC-004, QA-1)
    ├── WorkspaceSettingsIntegrationTest.java     # US2: update persist; invalid→per-field, no partial (SC-008); PATCH-on-unconfigured 409;
    │                                             #   concurrent DIFFERENT-field edits via CountDownLatch → BOTH preserved, no lost update (SC-009, QA-5)
    ├── WorkspaceRbacContractTest.java            # SC-001/SC-002: loop 4 non-admin roles × {POST setup, PATCH config, PUT branding,
    │                                             #   POST logo, PUT email, PUT template-lock} (write) + GET config (read) → 403 AND
    │                                             #   re-read shows no state change; configured AND unconfigured states (QA-6/QA-9)
    ├── BrandingIntegrationTest.java              # US3: PNG/JPEG ok; SVG, renamed-svg-as-png (magic mismatch), >1MB, corrupt-but-valid-magic
    │                                             #   (ImageIO null), >2048^2 dims, decompression-bomb fixture → 400, nothing persisted (SC-008, QA-2);
    │                                             #   colour regex; per-attribute default (SC-011); unset logo→placeholder + AUDITED (US3 AS-6, QA-6);
    │                                             #   public branding read (set/unset)
    ├── EmailConfigIntegrationTest.java           # US4: ciphertext-at-rest via RAW driver (SC-007); credential never returned, any role
    │                                             #   incl. serializing the ENTITY (SC-006, BE-3); rotate replaces (old unrecoverable), unset clears;
    │                                             #   domain validation (ASCII-LDH); restart via cold template (SC-004)
    ├── TemplateLockIntegrationTest.java          # US5: lock persist + read-shape; admin-only (F21 forward-contract note)
    ├── WorkspaceAuditIntegrationTest.java        # SC-003/SC-010/SC-013: WORKSPACE_CONFIGURED.newValue non-null = acknowledged days;
    │                                             #   retention change records old/new; NON-retention change has null old/new + no credential
    │                                             #   value (QA-8); append-only (no update/delete API)
    └── WorkspaceLogPiiScanTest.java              # SC-005: root logger set to TRACE; drive set→rotate→unset→validation-error; assert neither
    │                                             #   the literal sentinel credential value NOR api-key/secret/password patterns appear (QA-3, BE/SEC)

frontend/src/app/
├── features/admin/workspace/
│   ├── workspace-setup-wizard.component.ts   # NEW — first-run wizard (ADMIN route); i18n="@@workspace.setup.*"
│   ├── workspace-settings.component.ts        # NEW — ongoing settings + branding + email + template locks (ADMIN route)
│   └── workspace.service.ts                   # NEW — GET/POST/PATCH/PUT config, branding upload, email, locks
├── core/auth/
│   ├── auth.models.ts                         # MODIFIED — MemberSummary interface gains workspaceConfigured (FE-1)
│   ├── auth.service.ts                        # MODIFIED — workspaceConfigured rides the existing member$ stream; reuse hasRole()
│   └── role.guard.ts                          # REUSED — requireRole('ADMIN') (F02; no change)
├── features/shell/shell.component.ts          # MODIFIED — Admin-only nav link; redirect logic in ngOnInit SUBSCRIBE (not
│                                              #   template side-effect): unconfigured Admin → /workspace/setup; non-admin on
│                                              #   unconfigured → neutral "setup pending" panel (US6 AS-5); filter null first (FE-2/FE-3)
└── app.routes.ts                              # MODIFIED — /workspace/setup + /admin/workspace (both [authGuard, roleGuard('ADMIN')]);
                                               #   non-admins are NEVER routed to /workspace/setup (neutral panel is the shell's job)

frontend/src/app/core/auth/role.guard.spec.ts                                  # MODIFIED — member() factory gains workspaceConfigured; covers BOTH guarded routes per role (SC-012, FE-5)
frontend/src/app/features/admin/workspace/workspace-settings.component.spec.ts  # Jasmine: admin passes, each non-admin → /not-authorized (SC-012)
frontend/src/app/features/shell/shell.component.spec.ts                         # MODIFIED/NEW — non-admin + workspaceConfigured=false renders "setup pending" (US6 AS-5)
frontend/e2e/workspace-config.spec.ts                                          # Playwright: wizard completion; non-admin redirect; public branding renders

.github/workflows/ci.yml                                                       # MODIFIED — extend the PII log-scan with a SECRET pattern set +
                                                                               #   sentinel-credential check (the existing scan greps emails only — SEC-BLOCKER-1)
```

**Test isolation note (new contamination vector, QA)**: the unique `{workspaceId}` index makes the singleton collections sensitive to cross-test state. Every new test class MUST clean `workspaceConfig`, `workspaceLogo`, AND `authAuditLog` in `@BeforeEach` with `mongoTemplate.remove(new Query(), Type.class)` — never `dropCollection` (which would drop the Mongock `004` indexes; CLAUDE.md F00.1 lesson). Otherwise a leftover configured doc fails the next class's setup with a `DuplicateKeyException`.

**Structure Decision**: Web-application layout (constitution Reference Source Layout). F03 *extends* the F01/F02 scaffold — two controllers (one Admin, one public), two new collections + repositories, two services, one Mongock changeset (`004`), and a small Admin frontend feature. It reuses the F02 `@Order(3)` security chain and `RestAccessDeniedHandler` unchanged, and the F01 PII crypto + audit. It modifies exactly two existing behaviours: `/me` gains the `workspaceConfigured` flag, and `MongoPiiConfig` registers one more converted field. No new top-level structure, no new dependency.

## Complexity Tracking

| Decision | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Separate `workspaceLogo` collection instead of inlining logo bytes in `workspaceConfig` | The config doc is read on demand and must stay small; inlining up to 1 MB of base64 bloats every config read and edges toward the 16 MB BSON cap as settings grow (review BE finding) | Inlining is simpler to write but couples a hot small document to a cold large blob and re-writes the blob on every targeted `$set`; GridFS was rejected as over-engineering for a single ≤ 1 MB asset (chunking adds a collection pair + driver complexity for no benefit under the cap). |
| One **public** branding read shipped now (`/api/public/workspace/branding` + `/logo`) | FR-011/FR-013/SC-011 require a real, testable branding read that candidate-facing pages (no session) consume; shipping it now is a non-stub increment (§II) verified end-to-end today | Deferring it to F14 would leave FR-011/SC-011 unimplementable in F03 and ship a write-only branding setting with no read to test. Making it Admin-only contradicts its candidate (session-less) consumer. It exposes only non-PII brand assets, so public is correct and C3-safe. |
| Provider credential = encryption-at-rest **and** a separate write-only field (two controls, not one) | The reused `PiiStringConverter` decrypts on read, so it provides at-rest protection only; never-return (FR-017/SC-006) is a distinct guarantee that must be structural, surviving any future whole-document read path incl. the public branding read | Relying on the converter alone would still surface the decrypted value on any DTO that maps the field — a latent leak. A masked fragment was rejected (FR-016 forbids any derived value of a possibly-low-entropy key); the indicator is a pure boolean. |
