# Research & Decisions: Workspace Setup & Configuration (F03)

**Date**: 2026-06-14 | **Feature**: `004-workspace-config` | **Input**: [spec.md](./spec.md), [plan.md](./plan.md)

All "NEEDS CLARIFICATION" from Technical Context are resolved here. Each decision is grounded in the existing F00/F01/F02 codebase and the constitution. Two product-scope questions (retention enforcement; workspace language) were escalated to the stakeholder during the spec stage and confirmed deferred — see spec Assumptions; not re-litigated here.

---

## D1 — Storage layout: one small config doc + a separate logo doc

**Decision**: Two new collections. `workspaceConfig` holds exactly one document per `workspaceId` (unique index) with all scalar settings, `brandColor`, a `hasLogo` boolean, `emailSendingDomain`, the encrypted `emailProviderCredential` (write-only), `templateLocks: Map<String,Boolean>`, retention period + acknowledgment fields, and `configuredAt` (null ⇒ unconfigured). `workspaceLogo` holds one document per `workspaceId` with the raster `bytes`, `contentType`, and `size`.

**Rationale**: The config doc is read on demand (and indirectly on `/me` for the configured flag), so it must stay small and cheap to `$set`. Logo bytes (≤ 1 MB) are cold and large relative to the rest; keeping them in a sibling collection keeps the config doc small, avoids re-writing the blob on every settings change, and keeps the per-request read free of image bytes. Both collections are tiny in cardinality (one doc each in the single-workspace MVP).

**Alternatives considered**: (a) *Inline logo bytes in the config doc* — simplest, but bloats every config read/write and edges toward the 16 MB BSON cap; rejected (review BE finding). (b) *GridFS for the logo* — designed for files > 16 MB with chunking; over-engineering for a single ≤ 1 MB asset, adds a `.files`/`.chunks` collection pair and driver ceremony; rejected per §I YAGNI. (c) *A denormalised settings blob on an existing doc* — there is no natural per-workspace home (no `Workspace` entity existed); a dedicated collection is cleaner and forward-compatible with multi-workspace.

---

## D2 — Provider credential: encryption-at-rest is a *separate* control from never-return

**Decision**: Two independent mechanisms.
1. **At rest** — register the existing `PiiStringConverter` for the new field in `MongoPiiConfig`: add `registrar.registerConverter(WorkspaceConfig.class, "emailProviderCredential", converter)` alongside the existing `Member`/`Invitation` registrations. The F01 converter is AES-256-GCM with a randomized IV (non-deterministic), satisfying FR-016; no hash/fingerprint/prefix of the credential is ever stored.
2. **Never-return** — the `emailProviderCredential` field is **structurally write-only**, not write-only by convention (review BE-3 / SEC-NIT-1):
   - On the `WorkspaceConfig` `@Document` POJO the field is annotated `@JsonIgnore` (so it can never serialize even if a controller returns the entity directly) **and** `toString()` is hand-overridden to omit it.
   - It appears on **no** response record; every read DTO exposes only `credentialSet: boolean`.
   - The request DTO `EmailConfigRequest` is a **class with a hand-written `toString()` that omits `credential`** (NOT a Java record — a record auto-generates a `toString()` that prints every component, which Spring may log in a `MethodArgumentNotValidException`/`BindingResult` on a validation failure — the single most likely real leak path).
   - A test serializes the `WorkspaceConfig` **entity itself** (not just the DTO) and asserts the credential is absent, and the secret log-scan drives a validation-error path with a credential present.

**Rationale**: `PiiStringConverter.read()` decrypts on every load, so the converter alone does **not** prevent the value reaching a serializer — it only protects the datastore. Never-return must therefore be structural (a field that no read path *can* serialize), so it survives any future feature that loads the whole `workspaceConfig` document (incl. the public branding read). This mirrors the CLAUDE.md principle "the safety guarantee must not depend on every caller being careful." A masked last-N fragment was rejected: FR-016 forbids any value *derived* from a possibly-low-entropy API key (offline brute-force oracle), so the indicator is a pure boolean.

**Registration note**: add the new line to the **existing** `MongoCustomConversions` bean lambda in `MongoPiiConfig` — `registrar.registerConverter(WorkspaceConfig.class, "emailProviderCredential", converter)` reusing the same `converter` instance — do NOT declare a second `MongoCustomConversions` bean (Spring permits only one). The converter is registered per-`(class, field)`, so it never touches `WorkspaceLogo.bytes` (`byte[]`, not a mapped String).

**Test note**: the ciphertext-at-rest assertion (SC-007) MUST read the field via the raw driver collection (`mongoTemplate.getCollection("workspaceConfig").find(...)`), bypassing the decrypting converter — exactly as the F01 ciphertext tests do.

**Alternatives considered**: A new dedicated cipher/keystore — rejected (gate C4, reuse F01 crypto). Storing the credential as a Fly secret — impossible: it is per-workspace runtime data, not deploy-time config (the deploy-time master key remains a Fly secret).

---

## D3 — Surface the `configured` flag on `/me`, not a new Admin-only read

**Decision**: Add a `workspaceConfigured: boolean` to the existing `/api/internal/auth/me` response (`AuthDtos.MemberSummary`). `AuthController.me()` calls `WorkspaceConfigService.isConfigured(principal.workspaceId())` (a cheap `existsByWorkspaceIdAndConfiguredAtNotNull`). The full settings payload stays Admin-only (FR-008).

**Rationale**: The SPA shell loads for every role and must route a first-run Admin to the wizard and a non-Admin to a neutral "setup pending" state (US6 AS-5). `/me` is already `@PreAuthorize("isAuthenticated()")` and called once at bootstrap, so piggy-backing the boolean avoids a second round-trip and avoids exposing the configured-state behind an Admin-only call that non-Admins can't read. Only the boolean is exposed below Admin — no setting values.

**Alternatives considered**: A dedicated `GET /api/internal/workspace/status` — an extra endpoint + round-trip for one boolean; rejected. Putting it behind the Admin-only config read — non-Admins couldn't learn it for routing.

---

## D4 — Concurrency: conditional upsert for first-run, targeted `$set` for edits

**Decision**:
- **Read-only reads (load-bearing invariant, review BE-1)** — `GET /config` and `/me`'s `isConfigured` are strictly **read-only**: `findByWorkspaceId` / `existsByWorkspaceIdAndConfiguredAtNotNull`. They **never** get-or-create. Therefore **no `workspaceConfig` document exists until `POST /setup` creates it** — the wizard upsert is the *only* code path that inserts the singleton.
- **First-run completion** — `findAndModify` with query `{ workspaceId, configuredAt: null }`, update setting all wizard fields + `configuredAt = now` + `retentionAcknowledgedAt = now`, `upsert = true`. Because reads never create a doc, the three cases are: (a) *no doc, single caller* → insert, configured; (b) *no doc, concurrent callers* → exactly one inserts, the loser hits the unique `{workspaceId}` index and throws `DuplicateKeyException` → caught → **409 already_configured**; (c) *already-configured doc exists* → filter `{configuredAt: null}` misses → upsert attempts an insert → unique-index `DuplicateKeyException` → **409**. In all cases at most one transition occurs and at most one `WORKSPACE_CONFIGURED` audit is written. (The earlier text relied only on the DuplicateKey path; the corrected invariant — reads never create the doc — is what makes the concurrent case sound, since a pre-existing *unconfigured* doc would otherwise match the filter and allow a double-`$set`/double-audit. Mirrors `SchedulerCheckpointService`'s upsert+DuplicateKey precedent, except setup *refuses* on duplicate rather than recovering.)
- **Ongoing edits** — each PATCH/PUT issues a targeted `findAndModify`/`updateFirst` with `$set` of only the changed fields, keyed by `{workspaceId}`. A PATCH on an unconfigured workspace (no doc) → **409 not_configured** (settings require setup first). Mutually-consistent multi-field values (working-hours `start`+`end`) are set together in one update. `configuredAt` and `retentionAcknowledgedAt` are **never** mutated by any PATCH/PUT path (the GDPR evidence is immutable post-setup, review SEC-2).

**Rationale**: MongoDB single-document writes are atomic, so a torn document is impossible; the real hazard is **lost update** from whole-document read-modify-write (the exact F02 lesson). Targeted `$set` of disjoint fields lets two Admins edit different settings concurrently without clobbering each other (SC-009). The conditional upsert + read-only-reads invariant makes the singleton creation race-safe without a transaction or lock service (gate C2).

**Alternatives considered**: A multi-document transaction — unnecessary (single doc) and a topology smell on a single instance; rejected. Optimistic `@Version` — viable but adds retry handling for no benefit when field-scoped `$set` already avoids the conflict; noted as a fallback only if a future multi-field consistency rule needs it.

---

## D5 — Branding resolution & defaults (per attribute)

**Decision**: `BrandingService.resolve(workspaceId)` returns a brand object resolved **per attribute**: `brandColor` falls back to the documented default `#1F2937` when unset; the logo falls back to a documented embedded placeholder when `hasLogo == false` — independently. The public read returns a `logoUrl` pointing at `GET /api/public/workspace/logo` (which serves the stored bytes or a static placeholder), plus the resolved colour.

**Rationale**: FR-013/SC-011 require a non-null brand in set, unset, and partially-set states with no broken asset. Per-attribute resolution handles "colour set, no logo" cleanly. Serving the logo via a URL (not inline base64 in the branding JSON) keeps the branding read small and cache-friendly for candidate pages.

**Public-oracle disposition (review SEC-MAJOR-1)**: the public branding/logo reads are distinguishable between "custom logo/colour set" and "default" (different bytes/colour), so they technically reveal whether branding has been customised. This is **intentional and by-design, not a leak**: branding exists precisely to be shown publicly on candidate pages, so `hasLogo`/`brandColor-set` are public information. The public read exposes **only** these two brand attributes — never any other setting, the configured/unconfigured state, `credentialSet`, or the credential. The C3 disposition is updated to say this explicitly rather than claim "no setting state is observable".

**Alternatives considered**: All-or-nothing default — fails the partial-state case (review QA finding). Inlining base64 in the branding JSON — bloats the candidate-page payload; rejected.

---

## D6 — Logo validation: size → magic byte → ImageIO decode

**Decision**: Validate in four ordered gates before persisting, all with JDK-only tools. **Order matters** — dimensions are checked from the header *before* a full decode, to defend against decompression bombs (review BE-4c / SEC-MAJOR-3 / QA-BLOCKER-2):
1. **Size** — reject > 1 MB before reading bytes into anything heavy.
2. **Magic byte** — inspect the actual leading bytes: PNG (`89 50 4E 47 0D 0A 1A 0A`) or JPEG (`FF D8 FF`). Reject anything else — explicitly **SVG and any markup/active-content** type — regardless of the client-supplied `Content-Type` or filename.
3. **Header-only dimensions** — obtain an `ImageReader` via `ImageIO.getImageReaders(ImageInputStream)` and read `reader.getWidth(0)`/`getHeight(0)` **without decoding the pixels**; reject > 2048×2048 here. This prevents a small (< 1 MB) but enormously-dimensioned file from OOM-ing the JVM during a full decode (a 1 MB PNG can declare 30000×30000).
4. **Bounded decode** — only now `reader.read(0)` (or `ImageIO.read`) the bounded raster; treat a `null` return or any `IOException`/`CMMException` as invalid (400, never 500). This confirms a real, decodable raster (not a polyglot).

Store `bytes`, the *verified* `contentType` (`image/png` or `image/jpeg`), and `size`. The public logo response sets the verified `Content-Type`, `X-Content-Type-Options: nosniff` (the load-bearing anti-MIME-sniff header — a CSP on an image payload does little), `Content-Disposition: inline`, `Content-Security-Policy: default-src 'none'; sandbox` as defense-in-depth, and an explicit `Cache-Control: public, max-age=300` (with an ETag) so the candidate CDN serves it from the edge and the single ≤ 1 MB asset is not re-fetched from Mongo on every candidate page load (review SEC-BLOCKER-2). `-Djava.awt.headless=true` is set for the Fly JAR run (Spring Boot defaults it true; made explicit).

**Rationale**: The logo renders on untrusted candidate-facing pages, so SVG (active content/XSS), content-type spoofing (polyglots), and decompression bombs are real vectors. Magic-byte + header-dimension + bounded decode defeats spoofing, bombs, and polyglots; the raster-only allow-list excludes SVG entirely; `nosniff` + `Content-Disposition: inline` stop MIME-sniff XSS on the served bytes. No new dependency — `ImageIO`/`ImageReader` are in the JDK.

**Alternatives considered**: Trusting `Content-Type`/extension — trivially spoofable; rejected. Allowing sanitized SVG — sanitizers are a moving target and add a dependency; rejected for MVP (raster-only is sufficient for a logo).

---

## D7 — Validation bounds (fixed in the spec, restated for fixtures)

**Decision**: Enforced in `WorkspaceConfigService` (constants, not magic numbers spread around):
- **Time zone** — `ZoneId.of(value)` must succeed (valid IANA id); `ZoneRulesException`/`DateTimeException` ⇒ per-field error.
- **Working hours** — `start`,`end` are `LocalTime`; `end` strictly after `start` on the same day; overnight windows (end ≤ start) rejected in MVP (DST is F12's concern).
- **SLA silence window** — integer days, 1–30 inclusive.
- **Data-retention period** — integer days, 30–3650 inclusive (0 rejected).
- **Brand colour** — matches `^#[0-9A-Fa-f]{6}$` (no shorthand, named, or `rgb()`).
- **Email-sending domain** — RFC-1035-style hostname, **ASCII letters/digits/hyphen (LDH) only**, dot-separated labels; punycode-decoded Unicode and control characters are rejected (so a homograph/IDN cannot be stored — review SEC-MINOR-2). No DNS/ownership check (that is F22); the domain MUST NOT be presented as verified anywhere.

On any invalid value the whole submission is refused with a per-field message and nothing is persisted (no partial write).

**Rationale**: Concrete bounds make FR-005/FR-012/SC-008 testable at spec/plan stage (the QA BLOCKER). Bounds chosen as sane SaaS defaults; all stakeholder-reversible via constants.

---

## D8 — Audit: reuse `authAuditLog`, extend minimally, append-only

**Decision**: Reuse the F01/F02 `authAuditLog` collection + `AuthAuditService`. Add two `AuthEventType` values: `WORKSPACE_CONFIGURED` (setup completion — records actor, timestamp, and the acknowledged retention value in `newValue`, `outcome="setup_completed"`) and `WORKSPACE_CONFIG_CHANGED` (every later change — `outcome` = a non-PII setting code such as `sla_window`/`branding`/`email_config`/`template_lock`/`retention_period`). Add two nullable non-PII `String` fields `oldValue`,`newValue` to `AuthAuditEvent`, populated only for the **retention-period** change (FR-023); other changes record the setting code only (FR-024), never the credential value (set or unset). Append-only is intrinsic: `AuthAuditService` only `save()`s, and no update/delete path exists on the repository (FR-026/SC-013).

**Rationale**: Reusing the member-keyed, IP-HMAC, non-PII auth audit avoids a parallel mechanism and inherits its no-PII guarantees. Generic `oldValue`/`newValue` strings carry the destructive retention change without a typed schema explosion. The GDPR acknowledgment is captured evidentially via `WORKSPACE_CONFIGURED` (actor + timestamp + acknowledged period), satisfying FR-004/SC-003.

**Authoritative artifact (review SEC-2)**: the append-only `authAuditLog` row is the **legal artifact** for the retention acknowledgment; `WorkspaceConfig.retentionAcknowledgedAt` is convenience state only. `WORKSPACE_CONFIGURED.newValue` is **always non-null** (= acknowledged retention days), asserted by `WorkspaceAuditIntegrationTest`. For non-retention `WORKSPACE_CONFIG_CHANGED` events, `oldValue`/`newValue` are **null** (setting code in `outcome` only) — asserted negatively so a future change can never smuggle a setting value (or the credential) into the audit row. No PATCH path mutates `configuredAt`/`retentionAcknowledgedAt`.

**CI secret scan (review SEC-BLOCKER-1)**: the existing `.github/workflows/ci.yml` PII scan greps **only** the email regex — it does NOT cover secrets. SC-005/FR-018 require a secret scan, so this feature must **extend** (not merely reuse) the CI step with a secret pattern set (`(?i)(api[_-]?key|secret|password|credential)["'\s:=]+\S`) and a fixed high-entropy sentinel credential token (e.g. `SG.SENTINEL_DO_NOT_LOG`) used in the test fixtures, failing CI if the sentinel or a secret pattern appears in captured logs. This is a tasked CI change, called out so the SC-005 guarantee is actually enforced rather than passing vacuously.

**Alternatives considered**: A separate `workspaceAuditLog` collection — duplicates the no-PII writer for no benefit at MVP scale; rejected (spec allowed either). Typed old/new columns per setting — over-modelled; rejected.

---

## D9 — Authorization: class-level `@PreAuthorize` under the internal prefix

**Decision**: `WorkspaceConfigController` carries a class-level `@PreAuthorize("hasRole('ADMIN')")` and is mounted under `/api/internal/workspace/**`. The public branding controller is mounted under `/api/public/workspace/**` (the F02 `@Order(2)` permitAll chain) and exposes only logo + colour. No `SecurityConfig` change: the F02 `@Order(3)` main chain + `RestAccessDeniedHandler` already render the 403 `{error,message}` envelope for an authenticated-but-non-Admin caller, and the `@Order(2)` chain already permits `/api/public/**`.

**Rationale**: One source of truth for the role (the annotation); the F02 `RbacEndpointInventoryTest` enumerates handlers and fails the build if an internal handler lacks a declaration — so mounting under `/api/internal/**` makes the test enforce F03's Admin gate automatically. A class-level annotation covers all handlers in the controller. Mounting config under any allow-listed prefix (`/api/public/`, `/api/candidate/`, …) would silently exempt it from both the inventory test and auth — explicitly avoided.

**Alternatives considered**: Re-encoding the rule as an `authorizeHttpRequests` path matcher — duplicates the matrix and drifts (F02 complexity-tracking rationale); rejected.

---

## D10 — Template lock model (forward contract to F21)

**Decision**: `templateLocks: Map<String,Boolean>` on the config doc, keyed by the template key string F21 will own. F03 ships `PUT /api/internal/workspace/templates/{key}/lock` (Admin) to set/clear and exposes the map on the Admin config read. F03 validates only that the key is a non-empty bounded string; it does **not** validate the key against a template catalog (none exists yet). The binding rule "locked ⇒ not editable by Recruiter" is F21's to enforce against this state.

**Rationale**: A small map on the config doc is the minimal real primitive that makes the governance state persistable and testable today without inventing F21's template entity (§II non-stub, §I YAGNI). US5 AS-4 is explicitly tagged a forward contract not exercised by F03's suite.

**Alternatives considered**: A dedicated `templateGovernance` collection — premature until F21 defines templates; rejected.

---

## D11 — Frontend wizard/settings routing

**Decision**: Reuse the F02 `roleGuard('ADMIN')` (no change). Add two ADMIN-guarded routes: `/workspace/setup` (wizard) and `/admin/workspace` (settings). The shell reads `workspaceConfigured` from `auth.service` (`/me`): an Admin on an unconfigured workspace is routed to `/workspace/setup`; a non-Admin on an unconfigured workspace sees a neutral "setup pending" panel (US6 AS-5); the Admin nav link to settings is shown via `hasRole('ADMIN')`. All strings use `i18n="@@workspace.*"`.

**Rationale**: Maximises reuse of the F02 guard/redirect machinery and the F01 `/me` bootstrap; the server remains the security boundary (the guard is defense-in-depth, FR-009).

**Alternatives considered**: A separate unconfigured-workspace interceptor — redundant with the existing `/me` bootstrap; rejected.

---

## D12 — Mongock changeset `004`

**Decision**: `ChangeUnit004_WorkspaceConfigIndexes` with `@ChangeUnit(id = "004-workspace-config-indexes", order = "004", author = "system")`. Creates a **unique** index `{ workspaceId: 1 }` on both `workspaceConfig` and `workspaceLogo` via the native driver API (`mongoTemplate.getCollection(name).createIndex(new Document("workspaceId",1), new IndexOptions().unique(true))`). `@RollbackExecution` drops exactly those two indexes via targeted `dropIndex(...)` (never `dropIndexes()`). `id`/`order` never renamed once applied (persisted in `mongockChangeLog`).

**Rationale**: `order="004"` is the correct next zero-padded value after the existing `001`/`002`/`003` change units. Native driver index creation + targeted rollback follow the F00.1 lessons in CLAUDE.md. The unique `{workspaceId}` index enforces the singleton (D1/D4). No nullable partial-index field is introduced, so the F01 `@Field(write=NON_NULL)` footgun does not apply here.

**Alternatives considered**: `indexOps(...).createIndex(Index)` — the Spring Data 4.x interface method is not reliably accessible (CLAUDE.md); rejected in favour of the native API.

---

## Resolved unknowns summary

| Technical Context item | Resolution |
|---|---|
| Storage shape for config + logo | D1 — `workspaceConfig` + sibling `workspaceLogo` collections |
| Credential at-rest + never-return | D2 — reuse `PiiStringConverter` (encryption) **and** write-only field (never-return) |
| How frontend learns configured state | D3 — `workspaceConfigured` on `/me` |
| Concurrency (first-run + edits) | D4 — conditional upsert + targeted `$set` |
| Default brand + partial resolution | D5 — per-attribute defaults, colour `#1F2937` |
| Logo upload safety | D6 — size + magic-byte + `ImageIO` decode, raster-only, SVG rejected |
| Validation bounds | D7 — fixed (tz IANA, hours, SLA 1–30, retention 30–3650, colour regex, domain format) |
| Audit mechanism | D8 — reuse `authAuditLog`, +2 event types, +`oldValue/newValue`, append-only |
| Authorization wiring | D9 — class `@PreAuthorize("hasRole('ADMIN')")` under `/api/internal/workspace/**` |
| Template governance primitive | D10 — `templateLocks` map + Admin lock endpoint (F21 forward contract) |
| Frontend routing | D11 — reuse F02 `roleGuard('ADMIN')`; wizard/settings routes |
| Migration | D12 — Mongock `ChangeUnit004` unique `{workspaceId}` on both collections |
