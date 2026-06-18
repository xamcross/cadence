# Implementation Plan: Standalone CSV Import Mode (F42)

**Branch**: `021-csv-import` | **Date**: 2026-06-18 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/021-csv-import/spec.md`

## Summary

F42 lets a Recruiter populate the candidate pipeline by uploading a structured **CSV file** (name, email, optional stage/requisition/phone) so Cadence is usable with **no ATS**. The upload endpoint persists the raw file as an encrypted blob + a `csvImportJobs` record and returns `202 Accepted` with a job id immediately (no parsing on the request thread); a `@Scheduled` worker (the F22/F40 checkpoint+sweep pattern, **no queue broker**) parses and validates each row, imports valid rows through the existing `CandidateService.create`, flags email duplicates for an explicit recruiter **merge/skip** decision, and disposes the raw blob on every terminal path (incl. a TTL auto-expiry that defaults unresolved duplicates to skip). Imported candidates are ordinary `Candidate` records — same PII-encryption, consent gate, erasure, retention, and RBAC — carrying a CSV provenance marker so they are never confused with an ATS-linked record.

The feature is **pure orchestration of existing platform seams plus one small parsing library**. It reuses: `CandidateService.create` (encrypt name/email/phone, `emailHash`, audit), `PiiCrypto`/`PiiStringConverter`/`MongoPiiConfig` (PII + raw-blob encryption), `CandidateRepository.findByWorkspaceIdAndEmailHash` (duplicate lookup — never the encrypted email), `ContactPermissionGate` (consent stays gated), `CandidateErasureService`/`CandidateAuditService` (erasure + audit), `SchedulerCheckpointService` + `@Scheduled` + `registerReplayAction` (F00.2 missed-fire-safe worker), `DeadLetterService`/`RecruiterNotificationService` (operator/recruiter alerts), the F03 multipart-upload pattern (`MultipartFile` + container limit above the in-service gate), the F02/F40 `@PreAuthorize` + `RbacEndpointInventory` + `@Order(HIGHEST_PRECEDENCE)` no-oracle handler, the `CandidateRateLimiter`, and the internal-Admin Angular screen pattern (no §IX gate — the F50/F51 precedent).

**One new runtime dependency**: `org.apache.commons:commons-csv` (RFC-4180-safe parsing of untrusted candidate files; Dependency-Policy-justified — a library, not an infra SDK, not a stack substitution). The feature is delivered end-to-end (Angular upload → Spring async import → Mongo) with no external provider (it is the no-ATS path), so there is **no integration-pending stub**.

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web — incl. multipart, data-mongodb, security w/ method security, actuator, aop, **scheduling**); Mongock 5.4.4; logstash-logback-encoder 9.0. **One new backend runtime dependency: `org.apache.commons:commons-csv:1.11.0`** (RFC-4180 parsing of untrusted CSV — FR-009; not an infra SDK, no transitive infra, §III-compatible / C4 PASS with the Dependency-Policy one-line justification). No new frontend runtime dependency. PII + raw-blob crypto via `PiiCrypto`/`PiiStringConverter`/`MongoPiiConfig`; async via `@Scheduled`+`SchedulerCheckpointService` (F00.2). **No queue broker. No integration-pending stub** (no external provider).
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **Two new collections** — `csvImportJobs` (one doc/upload — lifecycle/counters/per-row results; PII-adjacent fields encrypted) and `csvImportFiles` (the raw uploaded bytes, **encrypted at rest**, one doc/job, disposed on terminal — the `workspaceLogo` separate-blob precedent so the hot job doc stays small). **Extends `candidates`** (F04) with two additive fields: `origin` (provenance enum) and `importJobId` (provenance link). **One new Mongock changeset** `ChangeUnit020_CsvImportIndexes` (order **"020"** off the highest applied **"019"**).
**Testing**: JUnit 5 + Mockito (unit: row validation, email normalization, >80% ratio math, injection-escape, intra-file dedup, idempotency-key), Testcontainers (integration: async happy-path, per-row validation, duplicate flag + merge/skip, restart idempotency, erasure-vs-merge race, TTL expiry, PII-at-rest, PII-log-scan, index bootstrap), MockMvc (upload/status/resolve contract + 5-role RBAC matrix + no-oracle), Jasmine (frontend upload+poll component). No provider stub (no external provider).
**Target Platform**: Single Fly.io Machine (backend JAR), Cloudflare Pages (Angular SPA), MongoDB Atlas — unchanged single-instance topology.
**Project Type**: Web application (Spring Boot backend + Angular frontend) — existing structure.
**Performance Goals**: SC-002 a 500-row upload returns `202` in < 2 s (the handler only stores the blob + inserts the job — no parse/validate/commit on the request thread; asserted structurally in SC-013). Background processing of 500 rows completes within the sweep window (one paginated read + per-row CAS create; ~seconds). Worker sweep `fixedDelay` short (default `PT5S`) for responsiveness.
**Constraints**: No queue broker / Redis / second service (§IV); the only new dependency is `commons-csv` (§III/C4 — justified); no tool download (C7); candidate PII + raw cell values never logged (§VIII / FR-017 / SC-005); raw uploaded bytes encrypted at rest + disposed on terminal/TTL (FR-021/021a / SC-015); CSV-injection neutralized at the export boundary, stored verbatim (FR-018); Mongock changeset Java source pure-ASCII (the F30 binary-detection lesson).
**Scale/Scope**: MVP single-workspace scale; default max file 5 MB / max 10 000 rows (configurable); per-sweep job + per-job row batch caps (the F12 `Pageable` lesson) so a large file cannot exhaust memory.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | **PASS** — "ATS integrations — Greenhouse and Lever; **standalone CSV import mode**" is explicitly named in constitution §I / spec §11 MVP. |
| **C2** | New service, queue, or replica? | **PASS** — none. Async import is `@Scheduled` + `SchedulerCheckpoint` + a MongoDB job/outbox collection (the F22/F40 precedent). Two new collections are data, not services. No broker. |
| **C3** | Exposes candidate PII to unauthorized roles? | **PASS** — upload/status/resolve are Admin+Recruiter only (FR-020); imported candidates inherit the existing candidate RBAC and add no visibility surface (HM/Interviewer/Read-only see nothing they could not already; full HM-requisition scoping is deferred to F51, the F40/F32 precedent — default closed). Job/file docs hold no role-readable PII beyond the importer's own scope. |
| **C4** | Dependency outside the fixed stack? | **PASS (with justification)** — one new library `commons-csv` (RFC-4180 parsing of untrusted input). It is a parsing utility, NOT an infrastructure SDK (no Kafka/Redis/K8s client), NOT a framework substitution. The Dependency Policy permits an additional library recorded in the plan with a one-line justification (done here + in build.gradle). |
| **C5** | New/modified Windows scripts contain non-ASCII? | **PASS (N/A)** — no new `.ps1`/`.cmd`/`.bat`. The CI PII-scan in `ci.yml` gains F42 sentinels (ASCII). The Mongock `.java` source is held pure-ASCII (the F30 NUL/binary lesson). |
| **C6** | Multi-role sub-agent review (>=3 roles) scheduled? | **PASS** — plan-phase 3-role review (Backend/DevOps, Security/GDPR, QA) is run at the end of this plan; a two-loop implementation review is scheduled at task close per the established cadence; the spec already passed a 3-role review. |
| **C7** | Downloads any build tool / runtime / CLI? | **PASS** — none. Cached Gradle 9.4.0, local JDK; `commons-csv` is a Maven-Central artifact resolved by the existing Gradle build (a project dependency, not a tool/runtime/CLI distribution — distinct from C7's tool-download prohibition). |

**Result: all gates PASS.** One Dependency-Policy library justification recorded (C4); no Complexity Tracking entries required.

## Project Structure

### Documentation (this feature)

```text
specs/021-csv-import/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions (async primitive, CSV lib, raw-blob storage, dedup, injection, provenance)
├── data-model.md        # Phase 1 — collections, fields, indexes, state machines
├── quickstart.md        # Phase 1 — how to run/demo the import flow + test-run flags
├── contracts/
│   └── import-api.md     # Internal REST endpoints (upload/status/resolve) + DTOs + role gates + error envelopes
├── checklists/
│   └── requirements.md  # Spec quality checklist (done)
└── tasks.md             # Phase 2 — /speckit.tasks (NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── domain/
│   ├── CsvImportJob.java                # NEW @Document(csvImportJobs) — lifecycle/counters/per-row results (no plaintext PII)
│   ├── CsvImportJobStatus.java          # NEW enum { ACCEPTED, PROCESSING, AWAITING_DUPLICATE_DECISION, COMPLETED, REJECTED, FAILED, EXPIRED }
│   ├── CsvImportRowResult.java          # NEW embedded — rowNumber, status, failingField, value-free reason (no raw values)
│   ├── CsvImportRowStatus.java          # NEW enum { IMPORTED, REJECTED, DUPLICATE_PENDING, MERGED, SKIPPED }
│   ├── CsvImportFile.java               # NEW @Document(csvImportFiles) — encrypted raw bytes, contentType, jobId (disposed on terminal)
│   ├── CandidateOrigin.java             # NEW enum { NATIVE, ATS, CSV_IMPORT } (provenance)
│   └── Candidate.java                   # EXTEND — additive origin (CandidateOrigin) + importJobId + importStageLabel (encrypted) + importRequisitionLabel
├── repository/
│   ├── CsvImportJobRepository.java      # NEW (findByWorkspaceIdAndId; findDue @Query+Pageable; findExpired @Query+Pageable)
│   └── CsvImportFileRepository.java     # NEW (findByJobId; deleteByJobId)
├── service/
│   ├── CsvImportService.java            # NEW accept(upload) → store blob + insert job; resolveDuplicates(); status()
│   ├── CsvImportProcessor.java          # NEW parse+validate+commit one job (per-row CAS create; dedup; merge/skip)
│   ├── CsvRowValidator.java             # NEW pure row validation (required fields, email format, value-free reasons)
│   ├── CsvInjectionEscaper.java         # NEW export-boundary formula-trigger escaper (FR-018) + structural test target
│   └── CandidateErasureService.java     # EXTEND — $set null importStageLabel (encrypted) + importRequisitionLabel; retain origin/importJobId
├── scheduler/
│   └── CsvImportScheduler.java          # NEW @Scheduled (checkpoint "csv-import-sweep") drain ACCEPTED/PROCESSING + reaper for EXPIRED/orphan
├── config/
│   ├── ImportProperties.java            # NEW @ConfigurationProperties(prefix="cadence.import")
│   └── MongoPiiConfig.java              # EXTEND — register CsvImportFile.dataBase64 + Candidate.importStageLabel (encrypted)
├── api/
│   ├── CsvImportController.java         # NEW /api/internal/import/** (Admin+Recruiter) — POST upload, GET status, POST resolve
│   └── CsvImportExceptionHandler.java   # NEW @Order(HIGHEST_PRECEDENCE) no-oracle envelope + multipart-too-large → 400 (F31/F03 lessons)
└── config/migration/
    └── ChangeUnit020_CsvImportIndexes.java  # NEW order "020" (pure ASCII)

backend/src/main/resources/
├── application.yml                       # EXTEND — cadence.import.* block; multipart max-file-size raised for the import path
└── application-test.yml                  # EXTEND — fast TTL / small limits / sweep PT0S-ish for deterministic tests

backend/src/test/java/com/cadence/csvimport/   # NEW test package
└── CsvImportItBase + *IT / *Test / contract / PII-scan / index tests + CSV fixtures

frontend/src/app/
├── features/admin/csv-import/
│   ├── csv-import.component.ts           # NEW standalone Admin+Recruiter screen (upload, progress poll, per-row results, merge/skip)
│   ├── csv-import.component.spec.ts      # NEW Jasmine (upload, poll states, duplicate resolve)
│   └── csv-import.service.ts             # NEW HttpClient (FormData upload, status poll, resolve) → /api/internal/import/**
└── app.routes.ts                          # EXTEND — admin/csv-import route (authGuard + roleGuard('ADMIN','RECRUITER'))
```

**Structure Decision**: Existing web-app layout (`backend/` Spring Boot + `frontend/` Angular). CSV parsing is wrapped in `service/` helpers (`CsvImportProcessor`/`CsvRowValidator`/`CsvInjectionEscaper`); no provider `integration/` adapter is needed (CSV has no external provider). The recruiter surface is an internal screen (no §IX WCAG/Lighthouse gate — the F50/F51 internal-screen precedent).

## Architecture & Key Decisions

1. **Async = `@Scheduled` sweep over a Mongo job table, NOT `@Async`/`TaskExecutor` (FR-001/002/005).** The codebase has no `@EnableAsync` and the constitution §IV names `@Scheduled`/`TaskScheduler` persisting job state to Mongo as the async rule. The upload handler does the minimum on the request thread: enforce limits, store the raw bytes as an **encrypted** `CsvImportFile`, insert a `CsvImportJob{status:ACCEPTED}`, return `202 {jobId}` (SC-002/SC-013 — asserted structurally that no parse/validate/commit happens pre-`202`). `CsvImportScheduler` (`@Scheduled fixedDelay`, checkpoint `"csv-import-sweep"`, `registerReplayAction`, `@Query`+`Pageable` due-finder — the `EmailDispatchScheduler` shape) claims a job via `findAndModify {status:ACCEPTED → PROCESSING}` (single-winner; a double-pick is a no-op) and runs `CsvImportProcessor`.

2. **Restart-safe, exactly-once-per-row (FR-005/SC-010).** Each row commit is keyed by a **per-(jobId, rowNumber) idempotency marker**: the processor records each row's terminal `CsvImportRowResult` on the job before/with the candidate write and skips a row whose result is already recorded, so a worker crash + replay (checkpoint replay action) resumes mid-job without double-creating. The idempotency marker is written **before/with** the candidate insert so a replay that finds the result already recorded skips the row (the test asserts row-count parity after a second sweep). New-CSV-candidate creation is additionally guarded by a **partial-unique index** `{workspaceId, emailHash}` over `origin:"CSV_IMPORT"` (data-model §4): the processor resolves by `findByWorkspaceIdAndEmailHash`, inserts, and catches `DuplicateKeyException` (the F40 precedent), so two concurrent import jobs racing the same new email collapse to **exactly one** candidate — SC-013 is a hard guarantee, not best-effort. (Honest bounds, the F22/F31/F40 precedent: the restart test is a double-sweep idempotency proxy, not a true kill-9, labelled in tasks; a CSV-vs-NATIVE concurrent same-new-email create remains best-effort — there is no cross-origin unique constraint — but the two-import-jobs case SC-013 names is guaranteed.)

3. **Per-row validation + >80% whole-file reject (FR-007/008/009/010).** `CsvRowValidator` validates each logical row independently (required `name`/`email`, email format) and returns a **value-free** reason (field name + rule, never the cell value — the F12 lesson). A missing/!`name`/`email` header column or absent header row is a *schema* reject (distinct from per-row). The >80% gate is computed as `failures / N > 0.80` over `N` data rows, `N>0`, **duplicates excluded from the numerator** (a duplicate is a warning, not a failure); a 0-row file → "0 imported" (no divide-by-zero). On schema reject or >80%, **no candidate is committed** and the job → `REJECTED` (the blob is disposed). Logical-row numbering (not physical line) is taken from the Commons-CSV record number so a quoted multi-line field yields a deterministic number; any parser exception is reduced to a value-free category before it is stored/logged (the F22 dead-letter lesson).

4. **Duplicate flag → explicit per-row merge/skip, with TTL skip-default (FR-011–015, FR-021a).** A row whose normalized-email `emailHash` matches an existing **active** workspace candidate is recorded `DUPLICATE_PENDING` (not committed). Clean rows commit immediately and independently (FR-015). If any duplicates remain, the job → `AWAITING_DUPLICATE_DECISION`. The recruiter resolves per-row (or whole-set) via `POST …/resolve`; resolution is allowed only in that state (else 409), is idempotent, and is RBAC-gated (FR-012). **Merge is an atomic active-state-guarded `updateFirst({_id, workspaceId, erasureState:ACTIVE} → $set non-empty content fields)`** — name/phone/`importStageLabel`/`importRequisitionLabel` from non-empty cells only; it never touches email identity / emailHash / consent / erasure / retention / status token / provenance (`origin`) / ATS keys (`atsProvider`/`atsExternalRef`/`atsStageLabel`) (FR-014; the F40 resurrection-guard + F03 `$set`-converter precedents). A merge that races an erasure no-ops (matchedCount 0). An unresolved job past `cadence.import.job-ttl` is auto-expired by the reaper: unresolved duplicates default to **skip**, raw blob disposed, job → `EXPIRED` (SC-015).

5. **Raw-blob storage + disposal (FR-021/021a).** The uploaded bytes live in a separate `csvImportFiles` collection (the `workspaceLogo` **separate-collection** precedent — keeps the job doc small; note `workspaceLogo` itself is stored cleartext, so it is NOT the encryption precedent). The payload is held as a **base64 `String`** field (`dataBase64`) precisely because the `PiiStringConverter` converts `String`→`String` and cannot encrypt a raw `byte[]`; so a `MongoPiiConfig` registration on `dataBase64` encrypts it at rest exactly like `emailProviderCredential`/`statusToken` (the real encryption precedent). Any email retained on the job for dedup/merge is the **`emailHash` (and/or encrypted email)** — never plaintext (FR-021). The blob is deleted on **every** terminal path (COMPLETED/REJECTED/FAILED/EXPIRED) and by the orphan reaper; the job record (counts + value-free per-row results) is retained for the status surface/audit.

6. **Provenance (FR-024/SC-014).** `Candidate` gains `origin` (`CandidateOrigin{NATIVE,ATS,CSV_IMPORT}`, `@Field(write=NON_NULL)`, null treated as NATIVE for legacy docs) + `importJobId`. A CSV candidate has **no `atsProvider`**, so the F40/F41 reconcile lookup (`findByWorkspaceIdAndAtsProviderAndAtsExternalRef`) can never match it (SC-014); a CSV merge onto an existing ATS-linked record never alters its ATS keys/consent/erasure (decision #4). `CandidateService.create` gains an `origin`/`importJobId` overload (or a post-set) so the import path stamps provenance while reusing the encryption+audit body; native/ATS callers are unaffected.

7. **Consent stays gated (FR-019/SC-008).** Import calls `CandidateService.create` with **no `LawfulBasis`** (fail-closed default), so `ContactPermissionGate.evaluate` denies `NO_BASIS` until basis is recorded through the existing flow — import never sends and never establishes contact basis (verified by attempting a send post-import).

8. **Upload safety (FR-004/SC-009) — the F03 logo lesson.** **Both** `spring.servlet.multipart.max-file-size` **and** `spring.servlet.multipart.max-request-size` are raised just **above** the in-service `cadence.import.max-file-size` (e.g. 5 MB gate → 6 MB multipart caps) so an over-size file still reaches the handler for a clean `400 invalid_import` (mapped from `MaxUploadSizeExceededException`/`MultipartException`, not a container 500). Raising the **shared** caps does NOT weaken the F03 logo control: `BrandingService.validateLogo` enforces its own unconditional 1 MB gate independent of the multipart cap (verified — a 1–6 MB logo still hits that gate → clean 400), and `WorkspaceExceptionHandler` stays scoped to the workspace controllers while `CsvImportExceptionHandler` owns the import controller's multipart-too-large mapping. The in-service gate also caps row count and per-field/total parsed-cell size so a small-but-pathological file cannot exhaust memory. The upload endpoint is rate-limited via the existing `CandidateRateLimiter` (advisory).

9. **RBAC + no-oracle (FR-003/020, SC-011).** `CsvImportController` is class-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (the `CandidateEmailController` precedent); HM/Interviewer/Read-only are refused (verified by the 5-role matrix + `RbacEndpointInventoryTest`). `GET …/status/{jobId}` resolves via `findByWorkspaceIdAndId` → an unknown or cross-workspace job id is an **indistinguishable 404** (no count/existence oracle); `CsvImportExceptionHandler` is `@Order(HIGHEST_PRECEDENCE)`, re-throws `AccessDeniedException`/`AuthenticationException` from its catch-all (the F31 lesson), and renders value-free envelopes.

10. **PII discipline.** Logs reference only `jobId`, `rowNumber`, field name, internal `candidateId` — never name/email/phone/raw cells (FR-017; enum→`kv` footgun avoided — log `.name()` Strings only). `DeadLetterService.recordFailure` (PII-sanitizing) records terminal job failures; a render/parse exception is reduced to its cause class. The CI PII scan gains F42 sentinels (candidate name/email/phone + a formula-injection sentinel).

## Phase 0 — Research

See [research.md](./research.md). Resolves: the async-primitive choice (`@Scheduled` sweep vs `@Async`/`TaskExecutor`), the CSV library decision (`commons-csv` vs hand-roll — resolved to commons-csv by the user, with the Dependency-Policy justification), raw-blob encrypted-storage shape, the `emailHash` dedup + intra-file dedup mechanics, the export-boundary injection-escape technique (store verbatim, escape on emit), the provenance-field design, the >80% ratio definition, and the TTL/disposal reaper. No `NEEDS CLARIFICATION` remain (the spec clarify session + the plan-phase parsing-library question closed them).

## Phase 1 — Design & Contracts

- [data-model.md](./data-model.md) — the two new collections + the `candidates` extension, every field, the `ChangeUnit020` indexes (`csvImportJobs`: `{workspaceId,_id}` scoped read, `{status,createdAt}` due-sweep, `{status,updatedAt}`/`{status,expiresAt}` reaper; `csvImportFiles`: unique `{jobId}`; `candidates`: a non-unique `{workspaceId,origin}` for later pipeline reads), and the job + row state machines.
- [contracts/import-api.md](./contracts/import-api.md) — `POST /api/internal/import/csv` (multipart, 202+jobId), `GET /api/internal/import/{jobId}/status`, `POST /api/internal/import/{jobId}/resolve` (merge/skip decisions) with role gates, status codes, and value-free error envelopes.
- [quickstart.md](./quickstart.md) — run the backend, upload a sample CSV as Recruiter, watch the job complete, resolve a duplicate, observe an imported candidate gated from email until consent, and the test-run flags.
- Agent context updated via `update-agent-context.ps1 -AgentType claude`.

## Multi-role review (constitution C6) — 2026-06-18 (plan phase)

Three role reviewers (Backend/DevOps, Security/GDPR, QA) reviewed the plan + Phase-1 design against the **real codebase**. **All three: APPROVE-WITH-NITS, zero BLOCKERS.** Every load-bearing seam claim was verified accurate against real source (`SchedulerCheckpointService`/`EmailDispatchScheduler` shape; `CandidateService.create` signature + its Javadoc that already pre-names F42; `MongoPiiConfig` String-converter registration; `findAndModify` CAS + non-`@Version` outbox; `ContactPermissionGate.NO_BASIS` fail-closed; `CandidateErasureService.wipe` `erasureState:ACTIVE` guard; `RbacEndpointInventoryTest` will cover `/api/internal/import/**`; Mongock highest applied 019 → new 020; `build.gradle` has no CSV lib; `MailTransportSwapTest` constant-pool structural-test precedent).

**Applied to the plan/design now (correctness-affecting):**
- **Multipart `max-request-size` (Backend+Security SHOULD-FIX)**: raise **both** `max-file-size` AND `max-request-size` above the in-service gate — a 5 MB part trips `max-request-size` first regardless of `max-file-size`. Confirmed the F03 logo's own 1 MB `BrandingService` gate stays authoritative so the shared raise does not weaken it. — decision #8, research D9, data-model §5.
- **Blob-encryption precedent corrected (Security SHOULD-FIX)**: `workspaceLogo` stores `byte[]` **cleartext** (separate-collection precedent only); the encryption precedent is `emailProviderCredential`/`statusToken`, and the payload is a base64 **`String`** because `PiiStringConverter` cannot encrypt a `byte[]`. — decision #5, research D3, data-model §2.
- **CSV stage/requisition fields resolved (Backend SHOULD-FIX, was deferred-and-contradictory)**: two additive candidate fields — `importStageLabel` (encrypted, registered in `MongoPiiConfig`, `$set null` on erasure) + `importRequisitionLabel` (plaintext, `$set null` on erasure); `CandidateErasureService.wipe` is now EXTENDED (not "unchanged"). Distinct from ATS fields so SC-014 holds. — structure, data-model §3, decision #4.
- **SC-013 made provable (QA SHOULD-FIX, the substantive one)**: the existing `{workspaceId,emailHash}` index is non-unique, so "two concurrent jobs → one candidate" was only best-effort. Added a **partial-unique** `{workspaceId,emailHash}` index over `origin:"CSV_IMPORT"` (the F23 partial-unique precedent, sidesteps the F01 null-collision footgun) + `insert`-catch-`DuplicateKeyException`, making SC-013 a hard guarantee (CSV-vs-NATIVE concurrent same-new-email remains a labelled best-effort residual). — decision #2, research D4, data-model §4.

**Carried to `/speckit.tasks` (test-enumeration NITs — not plan defects):** the idempotency marker is written before/with the candidate insert (the restart double-sweep proxy only proves something given that ordering); the `>80%` unit test pins a 5-row/4-malformed = exactly-80%-commits boundary fixture (malformed counts in `N`); a re-POST `resolve` idempotency test asserts `mergedCount`/`skippedCount` do not double-increment; the structural "no parse before 202" is backed by a MockMvc malformed-CSV-still-returns-202 behavioural test; the `CsvInjectionEscaper` also RFC-4180 field-quotes on emit (not just the formula prefix) and covers `\t`/`\r`/post-BOM triggers; the CI PII sentinel scan covers `csvImportJobs.rowResults` + a malformed-cell-embedded sentinel + excludes `originalFilename` deliberately; the no-PII-in-logs obligation forbids passing any `CSVException.getMessage()` into `recordFailure`/logs/`reason` (reduce to cause-class first).

## Complexity Tracking

No constitution gate failed. **One Dependency-Policy justification** (not a gate failure): `org.apache.commons:commons-csv` — safe RFC-4180 parsing of untrusted candidate CSV (FR-009). It is a parsing library (not an infrastructure SDK, not a stack substitution), recorded here and in `build.gradle` per the Dependency Policy; hand-rolling a correct/secure parser for quoted-field/embedded-newline/unterminated-quote/BOM cases was judged the higher risk.

**Scope note (honest deferral, not a violation)**: Full Hiring-Manager → requisition scoping of imported candidates is **deferred to F51** (no candidate→requisition→assignment link exists yet — the F40/F32 precedent). F42 stores the `requisition` as a raw label and does not widen any role's visibility (imported candidates are reachable exactly as native candidates are; HM/Interviewer/Read-only gain nothing). Reported, mirroring the F40 precedent.
