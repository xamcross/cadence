# Phase 0 Research — GDPR Baseline (F04)

**Branch**: `005-gdpr-baseline` | **Date**: 2026-06-15 | **Spec**: [spec.md](./spec.md)

All decisions reuse the F00/F01/F02/F03 infrastructure; **no new runtime dependency, no topology change**. Each decision lists the spec FR/SC it satisfies and the review finding it closes (review log in `checklists/requirements.md`).

---

## D1 — Candidate GDPR record: PII encryption-at-rest + keyed-hash lookup (reuse F01)

**Decision**: Introduce `Candidate` (`@Document("candidates")`) with `name`/`email`/`phone` encrypted at rest by registering the existing `PiiStringConverter` for `(Candidate.class, "name"/"email"/"phone")` in `MongoPiiConfig` — exactly the F01 member-PII pattern. Lookup by email uses `emailHash = PiiCrypto.emailHash(email)` (`HMAC-SHA-256(lowercased email, PII_PEPPER)`), never a query on the encrypted field. The `{workspaceId, emailHash}` index is **non-unique** (B3).

**Rationale**: Reuses a proven, audited control (FR-002); the converter is a per-`(class,field)` registration on the single existing `MongoCustomConversions` bean — no new bean, no new dependency (C2/C4). Non-deterministic AES-256-GCM (random IV per write) means a raw read is ciphertext (SC-006). The keyed hash is the only query-able representation.

**Alternatives rejected**: A unique `{workspaceId, emailHash}` index (as members use) — rejected because (a) a person may legitimately be a candidate for multiple requisitions, so candidate-email uniqueness is not an invariant, and (b) erasure must clear `emailHash`, and multiple nulls would collide on a unique index (the F01 partial-index/`write=NON_NULL` footgun, CLAUDE.md). De-duplication is a later-feature concern (F42). Querying a deterministic-encrypted email — rejected (deterministic crypto leaks equality and is a §VIII regression).

**Closes**: Backend B3 (unique-index null collision), Security S1 (residual hash, see D2). **Spec**: FR-001, FR-002, SC-006.

---

## D2 — Shared wipe: idempotent guarded `findAndModify`, destroy the email-derived key, CAS-winner-only audit

**Decision**: One `CandidateErasureService.wipe(candidateId, reasonCode, actor)` used by all three erasure paths. It performs a single **targeted `$set`** via a guarded `findAndModify(query = {_id, erasureState: ACTIVE}, update = { name/email/phone → "[ERASED]", emailHash → null, erasureState → ERASED, erasedAt → now })`. The erasure audit (`ERASURE_COMPLETED`) is appended **only when `matchedCount == 1`** (the CAS winner); a no-match (already-erased / missing id) writes nothing and returns the same response (indistinguishable, no existence oracle).

**Rationale**:
- **De-identification is real, not cosmetic (S1 BLOCKER)**: the stored `emailHash` is a deterministic HMAC — leaving it would let anyone with DB access recompute it from a known email and re-identify the "erased" subject. The wipe **sets `emailHash` to null**; with `@Field(write = NON_NULL)` the key is **omitted from BSON entirely** (the raw doc has no `emailHash` field, not `emailHash:null` — a stored null is still queryable and a weak correlator). `name`/`email`/`phone` are `$set` to the marker `"[ERASED]"`.
  - **Converter caveat (BE/SEC-MAJOR)**: `name`/`email`/`phone` are converter-managed, and **Spring Data converters apply to `$set` values** (the F03 cold-reload lesson), so `$set("email","[ERASED]")` stores the **ciphertext of `[ERASED]`**, not the literal string. The SC-002/SC-006 raw-driver test therefore asserts: (a) the `emailHash` field **key is absent**; (b) reading back **through the decrypting converter** (a cold `MongoTemplate`) yields the marker `"[ERASED]"` for name/email/phone (the original PII is destroyed); (c) the raw bytes are ciphertext (never the original plaintext). It does NOT assert the literal `[ERASED]` appears in the raw BSON (that would contradict encryption-at-rest).
- **Idempotent + race-safe (FR-009)**: the `erasureState: ACTIVE` guard makes a second/concurrent trigger a no-match no-op → exactly one wipe and exactly one `ERASURE_COMPLETED` audit (SC-005), and the **N−1 losers append nothing** (assert the candidate's audit-row count rises by exactly one). Mirrors F02 `guardedFlipAdmin`.
- **Unknown id returns success, not 404 (FR-009, QA-MAJOR)**: erasure on a missing / already-erased / just-erased id all return the **same `200 {"status":"erased"}`** — erasure does **NOT** use the F02 `ScopedNotFoundException`→404 path (that would be an existence oracle). The test asserts the full response entity (status line + body) is byte-identical across all three cases.
- **Targeted `$set`, not whole-doc save (F02/F03 lesson)**: avoids lost-update of fields a later feature adds to the same document.
- **Synchronous (D11)**: the wipe is O(1) (one `$set` + one append), so it satisfies the 2-second/non-blocking bound synchronously.
- **`emailHash → null` is safe** because the index is non-unique (D1) and the field is `@Field(write = NON_NULL)` (null omitted from BSON). Never a constant sentinel on the hash (a constant would collide and is itself a weak correlator).

**Alternatives rejected**: Whole-document delete — rejected: it would destroy the audit trail (FR-010 requires it survive) and the pseudonymous skeleton. `$unset` of converter-managed fields — rejected (the F03 `ClassCastException` lesson: the UpdateMapper feeds the unset-marker to the String converter); use `$set` to `[ERASED]`/null. A sentinel `emailHash` value — rejected (re-identifiable correlator).

**Closes**: Security S1 (BLOCKER), Backend B3; QA "exactly-one audit under concurrency". **Spec**: FR-006, FR-009, FR-010, SC-002, SC-005, SC-006.

---

## D3 — Append-only candidate audit log: closed-enum codes, `occurredAt` + `_id` order, survives erasure

**Decision**: New `CandidateAuditEvent` (`@Document("auditLog")`) and `CandidateAuditService` (mirrors `AuthAuditService`: injected `Clock`, non-PII fields). Fields: `candidateId`, `eventType` (closed enum), `outcome` (closed enum), `actorMemberId` (nullable → system), `occurredAt` (`Instant`). **No free-text value column; the single append method takes enum params only.** The deterministic order tiebreaker is the **`_id` ObjectId** (per-process monotonic counter), not a separate `seq` field — removing the unspecified-counter hazard (QA-MAJOR). Expose an append/insert method + an Admin read ordered by `(occurredAt, _id)`. The repository extends a narrow `Repository<>` (no `delete*`/`update*`).

**Rationale**:
- **Non-PII by construction (S4)**: closed-enum codes only means an entry structurally cannot carry a candidate name/email/phone or message content (FR-017); a content test asserts it. Distinct from the member-keyed `authAuditLog` (`AuthAuditEvent`'s own Javadoc anticipates this separate candidate-keyed `auditLog`).
- **Index already exists**: ChangeUnit001 created `auditLog { candidateId: 1, occurredAt: -1 }` — pin the timestamp field name to **`occurredAt`** so the pre-built index is used (B5); do NOT recreate it in ChangeUnit005.
- **Deterministic order (QA)**: `(occurredAt, _id)` makes the ordered-read test non-flaky when two events in one flow share a clock tick (the injected `Clock` can return identical instants); same-process ObjectIds are monotonically increasing, so insertion order is recovered.
- **Survives erasure (FR-010)**: the log references `candidateId` only; the wipe never touches it. It is the legitimate-interest accountability record; the MVP applies no max-retention ceiling (consciously recorded, FR-015).
- **Append-only proof (FR-015, concrete)**: `CandidateAuditEventRepository` extends a narrow `org.springframework.data.repository.Repository<>` (NOT `CrudRepository`/`MongoRepository`, which expose `delete*`) declaring only insert + finders; a self-test (a) reflectively asserts no `delete*`/`update*`/`remove*` method and (b) asserts no controller maps `DELETE`/`PUT`/`PATCH` to an audit path (`RequestMappingHandlerMapping`).

**Alternatives rejected**: Reusing `authAuditLog` — rejected (it is member-keyed and survives *member* erasure semantics; candidate audit is a separate concern + separate index). A free-text `details` column — rejected (it is the exact PII-leak vector S4 flagged).

**Closes**: Security S4, Backend B5, QA "ordering tiebreaker / empty read". **Spec**: FR-014, FR-015, FR-016, FR-017, SC-007, SC-008, SC-012.

---

## D4 — Contact-permission gate: quad-state, fail-closed, fixed precedence

**Decision**: `ContactPermissionGate.evaluate(candidateId) → Decision{ permit | deny(reason) }`. **Positive evaluation (deny-unless-explicitly-permitted)**: return `permit` **only if** `erasureState == ACTIVE` (explicit equals, not `!= ERASED`) **and** `retentionFlagged == false` (explicit) **and** `lawfulBasis != null` **and** `basisWithdrawn == false`. **Any** other, missing, null, or unreadable value → `deny`. When more than one deny condition holds, the reason follows **precedence `erased > over_retention > withdrawn > no_basis`**; an exception / missing candidate / unrecognized state → `deny(unavailable)`.

**Rationale**: This is the single decision point F22 will consult before every dispatch (FR-004); it must never permit by default. Evaluating **positively** (permit only on an explicitly-good state) means a future/corrupt enum value can never fall through to permit (SEC-MAJOR) — the F02 precedent ("null/unknown persisted role → least privilege") generalized. Precedence is fixed so SC-001's overlapping-state rows have one correct expected reason (QA Q3). The gate is a pure read (no write), cheap and side-effect-free.

**Alternatives rejected**: Fail-open on error — rejected (would email candidates whose state could not be confirmed; a §VIII / GDPR violation). Returning a boolean only — rejected (F22 needs the reason for its non-PII dead-letter, US1 AS-6).

**Closes**: Security S6 (fail-closed), QA Q3 (precedence). **Spec**: FR-004, SC-001.

---

## D5 — Lawful-basis model: closed enum, withdrawal, operator surface, fail-closed default

**Decision**: Consent state lives on the `Candidate` as `lawfulBasis` (closed enum, nullable), `basisRecordedAt`, `basisActorMemberId`, `basisWithdrawn` (boolean), `basisWithdrawnAt`. F04 ships an **operator surface** (`PUT /api/internal/candidates/{id}/basis` to record, `DELETE` to withdraw — Admin or Recruiter) so US1 is demonstrable end-to-end via HTTP today. The canonical-create contract (D6) records **no** basis unless one is supplied → gate `deny: no_basis` until set (**fail-closed**, confirmed in Clarifications 2026-06-15). The candidate-facing self-service withdrawal is F30 (forward contract).

**Rationale**: Closes the BA "FR-003 requires record/withdraw but nothing exposes it" gap (BA3) and makes US1's "demonstrable end to end today" true via a real endpoint, not test seeding. Fail-closed default (no auto-basis) is the safest GDPR posture and is now stakeholder-confirmed; a workspace-default basis remains a one-line future change with no architecture impact. Recording the actor by **internal id only** keeps the consent record non-identifying so it can be retained as Art. 7(1) evidence through erasure (S5).

**Alternatives rejected**: Auto-recording a workspace-default basis on create — rejected by clarification (fail-closed chosen). A separate consent collection — rejected (the basis is 1:1 with the candidate and read on the same hot path as the gate; embedding avoids a join).

**Closes**: BA3 (no record surface), BA2 / QA Q12 (default-basis decision), Security S5 (consent record on erasure). **Spec**: FR-003, FR-004, FR-021.

---

## D6 — Canonical candidate-create contract: a service seam, no HTTP create endpoint

**Decision**: `CandidateService.create(workspaceId, name, email, phone, Optional<lawfulBasis>, actor)` is the single seam that constructs a `Candidate`: sets `erasureState=ACTIVE`, `basisWithdrawn=false`, computes `emailHash`, records the optional initial basis, sets `lastContactAt = now`, and appends a `RECORD_CREATED` audit entry. **F04 exposes no HTTP candidate-create endpoint.** F04's own tests invoke this method production-path (not hand-built fixtures); F13/F40/F41/F42 call it on their create surfaces (forward contract, FR-005).

**Rationale**: F04 is P0 Foundation, *before* any candidate-creating feature; the create contract is a real §II forward contract (its consumers — F13/F40/F42 — land immediately after in the delivery sequence and call it for real), not a stub. Making the seam explicit and exercising it via an integration test (SC-016) prevents a planner from inferring a candidate-management API is in scope (over-scope) or that the path is untested (under-scope) — BA1.

**Alternatives rejected**: A `POST /api/internal/candidates` create endpoint in F04 — rejected (candidate creation is owned by F13/F40/F42; adding it here is scope creep and would overlap F42's duplicate-detection/CSV semantics). Persisting test candidates by raw `mongoTemplate.save` — rejected (would not exercise the GDPR-default invariants the contract guarantees).

**Closes**: BA1 (create-path demonstrability). **Spec**: FR-005, SC-016.

---

## D7 — Erasure-request: entity, guarded transitions, PII-free intake

**Decision**: `ErasureRequest` (`@Document("erasureRequests")`): `candidateId`, `status` (`PENDING|RESOLVED_CONFIRMED|RESOLVED_REJECTED`), `reasonCode` (closed enum, **no free text**), `createdAt`, `decidedByMemberId`, `decidedAt`. Intake primitive `requestErasure(candidateId, reasonCode)` accepts **only** a candidate id + bounded non-PII reason code (sanitize/reject any free text). Admin endpoints list pending, confirm (→ `wipe`, D2), reject. Decisions use a guarded `findAndModify(status: PENDING → …)` so double/concurrent confirm resolves to a single wipe.

**Rationale**: The request record must not itself re-identify an erased subject (S3) — so no candidate-submitted free text is stored. Guarded transitions (FR-012, QA Q6) mirror the F02 last-Admin CAS so an already-resolved or concurrently-decided request cannot double-wipe. The candidate-facing submission (status-page token, rate-limited per F14/F30) is wired by F30 to this primitive (forward contract, FR-013) — F04 ships **no `/api/candidate/**` controller** (B5).

**Alternatives rejected**: A free-text reason field on the request — rejected (S3 PII-survival). Performing the wipe inline at request time — rejected (US-F04-3 requires Admin confirmation routing).

**Closes**: Security S3, QA Q6. **Spec**: FR-011, FR-012, FR-013, SC-015.

---

## D8 — Retention scan: `@Scheduled` + `SchedulerCheckpoint`, `lastContactAt` basis, strict boundary, self-clearing

**Decision**: `RetentionScanTask` (`@Scheduled`, daily) wraps work in `SchedulerCheckpointService.start/complete` and **registers its replay action in `@PostConstruct`** (BEFORE `ApplicationReadyEvent`, or `replayMissedFires` would find a stale `RUNNING` row with no registered action and silently `markReplayed` it — BE-MINOR). It reads the F03 retention period from `WorkspaceConfig`, then flags candidates where `erasureState==ACTIVE ∧ lastContactAt < (now − retentionDays)` (**strict** `<`; a record exactly at the boundary is not flagged) by setting `retentionFlagged=true, retentionFlaggedAt=now`. The same scan **clears** a stale flag when a previously-flagged record is no longer over-age (period lengthened or `lastContactAt` refreshed; emits `RETENTION_FLAG_CLEARED`). Deletion is Admin-confirmed via `POST /api/internal/retention/{candidateId}/delete`, which runs the shared wipe **only via a guarded update on `retentionFlagged==true`** (BE-MAJOR — never wipe an unflagged ACTIVE candidate); a not-flagged/unknown id returns the same shape without wiping. The scan queries on the pre-existing `{workspaceId, lastContactAt}` index with an `erasureState`/`retentionFlagged` **residual filter** (small fan-out).

**Rationale**:
- **Age basis = `lastContactAt`** (B1): GDPR storage-limitation is measured from last processing/contact, not record creation; and `lastContactAt` is the F00.1 pre-declared index field, so the existing index supports the scan without a new index or a wrong legal basis.
- **Strict boundary** (`>` semantics via `<` predicate) resolves the QA Q4 off-by-one; SC-014 fixtures sit exactly at / just past the period.
- **Self-clearing flag** (QA Q5): without it, a lengthened period would leave the gate denying forever.
- **Clock caveat (B4)**: `SchedulerCheckpointService` uses `Instant.now()` directly (verified — not a `Clock`), so the missed-fire/idempotency test (SC-009) seeds a **stale `RUNNING` checkpoint** (the F00.2 approach) rather than advancing a `MutableClock`. The scan's *age* comparison uses an injected `Clock` so the over-age fixtures are deterministic. Refactoring `SchedulerCheckpointService` to take a `Clock` is a larger optional change, deliberately **not** taken in F04.

**Alternatives rejected**: `createdAt` age basis — rejected (wrong GDPR semantics + needs a new index). Auto-delete on flag — rejected (FR-019 requires Admin confirmation; ISSUE-10 forbids display-only but also implies human-confirmed deletion). Neutralizing `lastContactAt` on erasure to drop erased rows from the range — noted as an acceptable alternative to the residual filter; the residual filter is chosen for simplicity (erased rows are few).

**Closes**: Backend B1/B2/B4, QA Q4/Q5. **Spec**: FR-018, FR-019, FR-020, SC-009, SC-014.

---

## D9 — RBAC: internal prefix, per-handler `@PreAuthorize`, no candidate controller

**Decision**: All authenticated F04 endpoints live under `/api/internal/**` and carry method security: erasure trigger + record/withdraw-basis → `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`; audit read, erasure-request view/confirm/reject, retention review/confirm-delete → `@PreAuthorize("hasRole('ADMIN')")`. Reuses the F02 `@Order(3)` authenticated chain + `RestAccessDeniedHandler` unchanged. F04 ships **no `/api/candidate/**` controller** (the erasure-request intake is a service bean only).

**Rationale**: The F02 `RbacEndpointInventoryTest` fails the build on any `/api/internal/**` handler lacking method security — so every handler must declare its role (deny-by-default). Mounting under the internal prefix (not an allow-listed public/candidate/actuator prefix) keeps the auth chain and the inventory test in force. The indistinguishable-not-found pattern (F02 `ScopedNotFoundException`) is reused for the erasure response (D2).

**Alternatives rejected**: A class-level `@PreAuthorize` only — usable where all handlers share a role (audit/retention/erasure-request controllers can be `hasRole('ADMIN')` at class level), but the erasure + basis controller mixes `ADMIN,RECRUITER` and `ADMIN`-only reads, so per-method annotations are used there.

**Closes**: Backend B5 (no unannotated handler). **Spec**: FR-021, FR-022, SC-004.

---

## D10 — Zero-PII logs: candidate patterns in the CI scan + `toString` safety

**Decision**: Extend the CI PII/secret log-scan via **high-entropy sentinels**, not generic regexes (free-form names/phones are unmatchable by pattern — the F03 secret-sentinel lesson). The `GdprLogPiiScanTest` seeds a candidate with sentinel PII (name `ZZSENTINELNAME_DONOTLOG`, email `sentinel@dont.log`, phone `+15550101010`), sets the root logger to `TRACE`, drives the full run **including a `GET …/audit` read (the decrypt path) and a validation-error path**, and asserts those literal sentinels (plus the email regex) are **absent** at any level. A **positive vacuity guard** asserts the run actually emitted GDPR output (e.g. at least one `candidateId=` kv) so a silently-skipped test cannot pass. `Candidate` gets a hand-written `toString()` omitting name/email/phone (F03 credential precedent); all GDPR logging uses `StructuredArguments.kv("candidateId", id)` + non-PII codes only. `ci.yml` greps the same sentinels in `test-output.txt`.

**Rationale**: FR-023/FR-024/SC-010 require the zero-PII guarantee machine-verified and **non-vacuous**. A name/phone regex would pass vacuously (no pattern matches a random name); a seeded sentinel + literal grep is the only reliable check. The decrypting converter means a careless `log.debug(candidate)` emits plaintext — the `toString()` override + structured-args discipline + the read/decrypt-path drive prevent and detect it.

**Alternatives rejected**: A generic candidate name/phone regex — rejected (vacuous). Relying on the existing member-only scan — rejected (passes vacuously for candidate PII, the exact hole F03's review caught).

**Closes**: Security/QA log-scan coverage. **Spec**: FR-023, FR-024, SC-010.

---

## D11 — Erasure is synchronous (reconciles the backlog "202/async")

**Decision**: F04 performs the wipe **synchronously**. The "non-blocking on history volume" guarantee is verified **structurally, not by wall-clock** (QA-BLOCKER): the SC-003 test seeds a large audit history, then asserts the wipe is **O(1)** — exactly **one** `findAndModify`/`$set` on `candidates` and exactly **one** audit append, **independent of history size** (the audit log is preserved, never cascaded). The timing assertion, if any, is on the **service call** `erasureService.wipe(...)`, never on the MockMvc round-trip (which includes container/jitter — flaky). The backlog's "202/async" is treated as a performance bound met trivially; the success status is 200 (contracts). A future genuine candidate-PII cascade (via FR-006a) would move erasure to `TaskScheduler` (no broker, §IV) + the 202 shape.

**Rationale**: There is nothing large to cascade in F04, so an async/202 path is YAGNI carrying a phantom threading model. The real, testable guarantee is O(1) writes regardless of history — a structural assertion, not a brittle wall-clock bound.

**Alternatives rejected**: A 202 + background `TaskScheduler` wipe now — rejected (no work to background; over-engineering). **Spec**: FR-008, SC-003.

---

## D12 — Indexes & Mongock `ChangeUnit005`

**Decision**: `@ChangeUnit(id = "005-gdpr-baseline-indexes", order = "005", author = "system")` creates only the **new** indexes via the native driver API: **non-unique** `candidates { workspaceId: 1, emailHash: 1 }` (lookup), and `erasureRequests { workspaceId: 1, status: 1 }` (pending-queue read). The `auditLog { candidateId, occurredAt }` and `candidates { workspaceId, lastContactAt }` indexes **already exist from ChangeUnit001** — do NOT recreate them. `@RollbackExecution` uses targeted `dropIndex(...)` (never `dropIndexes()`). `order="005"` is correct after 001–004.

**Rationale**: Follows the F00.1 Mongock conventions (CLAUDE.md): zero-padded order, native driver, targeted rollback, `id`/`order` never renamed once applied. Reusing the pre-declared indexes avoids a duplicate-index changeset.

**Alternatives rejected**: A unique candidate email index — rejected (D1). Recreating the F00.1 indexes — rejected (already applied; Mongock would not reapply and a duplicate changeset is an error).

**Closes**: Backend B1/B3 index decisions. **Spec**: FR-001, FR-018.

---

## D13 — Frontend: minimal Admin/Recruiter GDPR surface

**Decision**: A small Admin/Recruiter GDPR feature keyed by candidate **internal id**. Concrete corrections from the FE review:
- **Guard API is `roleGuard(...roles: Role[])`** (varargs) — there is **no** `requireRole`/`hasAnyRole` (those names were fiction); it already supports the mixed case. Per-surface mapping is explicit in `app.routes.ts`: `candidate-erasure-action` + basis → `roleGuard('ADMIN','RECRUITER')`; `candidate-audit`, `erasure-queue`, `retention-review` → `roleGuard('ADMIN')`.
- **Shell nav must gain entries** (`shell.component.ts` currently shows nav only under `@if (m.role === 'ADMIN')`): add the three Admin GDPR links under the Admin block, **and** a `@if (m.role === 'ADMIN' || m.role === 'RECRUITER')` entry for the erasure action — otherwise the Recruiter erasure surface is unreachable (US7 AS-2).
- **Candidate-id acquisition**: F04 ships **no candidate browser** (that is F51), so each surface takes a **known candidate internal id via a paste/text field**; "browse/select a candidate" is explicitly deferred to F51. Documented so the surfaces are not a usability dead-end.
- **Per-route guard tests (SC-011)**: a routes-level/per-component spec covers **all four** surfaces and **both** guard-arg sets — assert RECRUITER **passes** the erasure route but **redirects** from the Admin-only routes, and HM/Interviewer/Read-only redirect everywhere (the F03 single-route hole).
- **Localization**: template strings `i18n="@@gdpr.*"` **and** programmatic strings (confirm dialog, HTTP-error text) via `$localize`.
- **Destructive UX/a11y**: erasure requires a **confirmation step** before dispatch; error/result regions use `role="alert"`; `erasure-queue` and `retention-review` define empty states.
- **Defense-in-depth**: controls are hidden for UX only; the `/api/internal/**` 403 (F02) is the boundary. `auth.models.ts`/`MemberSummary` needs **no change** (role already exposed via `member$`).

**Rationale**: The server is the boundary (FR-021); the frontend is usability + defense-in-depth (US7, P3). Keying by internal id keeps F04 from shipping a candidate-list UI it does not own (F51).

**Alternatives rejected**: A full candidate browser/search — rejected (owned by F51). A `MemberSummary` change like F03's `workspaceConfigured` — unnecessary (role already client-side). **Spec**: US7, SC-011.

---

## D14 — Erasure-participant forward contract (FR-006a)

**Decision**: Document the binding contract that every later feature storing candidate PII outside the `Candidate` record (rendered email bodies — F22; ATS payloads — F40/F41; dead-letters; scheduling artifacts) MUST be reachable by the shared wipe (D2). F04 itself holds no such auxiliary store, so there is nothing to wire today; the contract binds the future.

**Rationale**: Art. 17 erasure is structurally incomplete the moment a later feature persists a second copy of candidate PII (Security S2). Naming the contract now (consumed by F22/F40 when they land) keeps the shared wipe authoritative and prevents per-feature ad-hoc deletion.

**Alternatives rejected**: Silently scoping erasure to F04 fields only — rejected (would leave a known Art. 17 hole undocumented). **Spec**: FR-006a.

---

## Summary of new/changed components

| Area | New | Modified |
|---|---|---|
| domain | `Candidate`, `CandidateAuditEvent`, `ErasureRequest`, `CandidateEventType` (enum), `LawfulBasis` (enum) | — |
| repository | `CandidateRepository`, `CandidateAuditEventRepository`, `ErasureRequestRepository` | — |
| service | `CandidateService` (create seam), `CandidateErasureService` (shared wipe), `CandidateAuditService`, `ContactPermissionGate`, `ErasureRequestService`, `RetentionService` | — |
| api | `CandidateGdprController`, `ErasureRequestController`, `RetentionController` (all `/api/internal/**`), `GdprDtos`, `GdprExceptionHandler` | — |
| scheduler | `RetentionScanTask` | (reuses `SchedulerCheckpointService` unchanged) |
| config | — | `MongoPiiConfig` (+3 candidate fields), `migration/ChangeUnit005_GdprIndexes` (new) |
| security | — | (reuses F02 `@Order(3)` chain + `RestAccessDeniedHandler` unchanged) |
| frontend | `features/admin/gdpr/*` (audit view, erasure action, request queue, retention) | `app.routes.ts`, reuses `role.guard` |
| ci | — | `.github/workflows/ci.yml` (candidate PII patterns in the log scan) |
