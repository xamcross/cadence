# Feature Specification: Workspace Setup & Configuration

**Feature Branch**: `004-workspace-config`
**Created**: 2026-06-14
**Status**: Draft
**Input**: User description: "find the first unimplemented task in the backlog start working on it. review with appropriate sub-agents" → resolved to **F03 — Workspace Setup & Configuration** (first unimplemented item in the delivery sequence after F00 scaffold, F01 authentication, and F02 RBAC; backlog Delivery Sequence: F00 → F01 → F02 → **F03** → F04).

## Overview

F00 stood up the platform, F01 established **who** a member is, and F02 established **what** each role may do. Up to now the *workspace* itself has existed only as a `workspaceId` string threaded through members and sessions — there is no place to configure the workspace, and no record that holds its settings.

F03 makes the workspace a first-class, configurable thing. It delivers two complementary surfaces, both **Admin-only** (per the F02 permission matrix row "Workspace configuration → Admin ✓, all others ✗"):

1. **First-run setup wizard** — the first time an Administrator signs in to an unconfigured workspace, they complete a short wizard (workspace name, time zone, working hours, default SLA silence window, and the **data-retention period**, which must be explicitly acknowledged as a GDPR gate). Completing the wizard transitions the workspace from *unconfigured* to *ready*.
2. **Ongoing configuration** — thereafter an Administrator can change those operational settings, set **branding** (logo, brand colour) used on candidate-facing surfaces, configure the **email-sending domain and provider credential**, and govern **template editability** (lock templates so Recruiters cannot edit them).

Settings are persisted so they survive restart, and they are the source of truth that **later features consume**: branding by every candidate-facing page (F14/F30), time zone and working hours by the scheduling rule engine (F12), the SLA silence window by the nudge engine (F31), the email-sending domain and provider credential by the email channel (F22), template locks by the template library (F21), and the data-retention period by the GDPR baseline (F04). Where the consuming surface does not yet exist, F03 defines the **binding configuration contract** that the owning feature MUST honour when it lands, and ships and enforces today everything that has a surface (the workspace record, the wizard, the settings management endpoints, RBAC enforcement, persistence, and secret-safe handling of the provider credential).

This is a configuration feature for **workspace members**; it touches no candidate surface directly (candidates only ever *see* the resulting branding on their pages, owned by later features).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Administrator completes first-run setup wizard (Priority: P1)

The first Administrator of a new workspace signs in and is guided through a short setup wizard: they enter the workspace name, choose the workspace time zone, set working hours, set the default SLA silence window (in days), and are shown and must explicitly acknowledge the data-retention period before finishing. On completion the workspace is marked configured and is ready to use.

**Why this priority**: Until the workspace is configured, downstream features have no time zone, no working hours, and no retention policy to operate against, and the GDPR retention acknowledgment is a legal gate. This is the foundational journey — everything else builds on a configured workspace. It is fully demonstrable end to end today: an Admin exists from F01, and completing the wizard produces a persisted, configured workspace.

**Independent Test**: Sign in as the first Administrator of an unconfigured workspace, complete the wizard with valid values, acknowledge the retention period, and confirm the workspace is persisted as configured with those values; confirm an unconfigured workspace cannot be marked ready without the retention acknowledgment.

**Acceptance Scenarios**:

1. **Given** an unconfigured workspace and a signed-in Administrator, **When** the Administrator submits the wizard with a valid name, time zone, working hours, SLA silence window, and retention acknowledgment, **Then** the workspace is persisted as *configured* with those values and the wizard is not shown again.
2. **Given** the wizard, **When** the Administrator attempts to finish without explicitly acknowledging the data-retention period, **Then** completion is refused with a clear message and the workspace remains *unconfigured* (GDPR gate).
3. **Given** the wizard, **When** the Administrator submits an invalid value (unknown/non-IANA time zone, working-hours end at-or-before start or an overnight window, SLA window outside 1–30 days, retention period outside 30–3650 days, including 0), **Then** the submission is refused with a per-field validation message and nothing is persisted.
4. **Given** an already-configured workspace, **When** any Administrator signs in, **Then** the first-run wizard is not presented again (it runs once per workspace lifecycle).
5. **Given** the workspace settings have been persisted, **When** the application is restarted, **Then** the same settings are read back unchanged.
6. **Given** an already-configured workspace, **When** the wizard-completion endpoint is called again directly (bypassing the UI), **Then** it is refused (no second transition, no re-write of the acknowledged retention period) — verified by a direct-API test.
7. **Given** an unconfigured workspace and two Administrators submitting the wizard concurrently, **When** both are processed, **Then** exactly one transition to *configured* succeeds (the other is a benign no-op or refused as already-configured, never a server error), the record is internally consistent, and both attempts are audited.

---

### User Story 2 - Administrator manages ongoing workspace settings (Priority: P1)

After setup, an Administrator opens workspace settings and changes operational configuration — workspace name, time zone, working hours, and the default SLA silence window — so the workspace adapts as the team's needs change. Only Administrators may view or change these settings; every other role is refused.

**Why this priority**: Configuration that cannot be corrected after first run is nearly useless; teams change time zones, hours, and SLA expectations. This is P1 alongside Story 1 because the settings surface and its RBAC enforcement are the core deliverable, and the wizard (Story 1) writes the same record this story reads and updates.

**Independent Test**: As an Administrator, change the SLA silence window and time zone, reload, and confirm the new values persisted; as each non-Administrator role, attempt to read and to change settings and confirm both are refused (HTTP 403) with no change.

**Acceptance Scenarios**:

1. **Given** a configured workspace and an Administrator, **When** the Administrator updates a setting (e.g. SLA silence window from 5 to 7 days), **Then** the new value is validated, persisted, and reflected on the next read.
2. **Given** a non-Administrator (Recruiter, Hiring Manager, Interviewer, or Read-only), **When** they attempt to read or change any workspace setting, **Then** the request is refused (HTTP 403) and no value is changed (per F02 deny-by-default).
3. **Given** any settings change, **When** it is applied, **Then** an audit entry is recorded (actor, the setting changed, timestamp) using non-PII identifiers only.
4. **Given** an invalid update (e.g. working-hours end before start, unknown time zone), **When** it is submitted, **Then** it is refused with a per-field validation message and no partial update is persisted.
5. **Given** two Administrators concurrently updating the same workspace settings, **When** both are processed, **Then** the record ends in a single internally-consistent state (no torn/merged document) and both attempts are audited.

---

### User Story 3 - Administrator configures candidate-facing branding (Priority: P2)

An Administrator uploads or sets the workspace logo and brand colour so that candidate-facing pages (scheduling, status, feedback) render in the company's brand rather than a generic default.

**Why this priority**: Branding is a stated differentiator for the candidate experience, but the workspace is fully operable without it (a sensible default brand applies until set). It is P2 because the candidate surfaces that consume branding are delivered by later features; F03 owns the branding configuration and the binding contract those features read.

**Independent Test**: As an Administrator, set a logo and brand colour, confirm they persist and are returned by the workspace-branding read; confirm an over-size or wrong-type logo is rejected; confirm a malformed brand colour is rejected; confirm the value is exposed in the shape later candidate-facing features will consume.

**Acceptance Scenarios**:

1. **Given** an Administrator, **When** they set a valid logo (PNG or JPEG, ≤ 1 MB) and a valid brand colour (`#RRGGBB`), **Then** both are persisted and returned by the branding read used by candidate-facing features.
2. **Given** an Administrator, **When** they upload a logo exceeding 1 MB, of an unsupported type, or of an active-content/markup type (e.g. SVG) — including a file whose actual bytes do not match a permitted raster format regardless of the declared content-type — **Then** it is rejected with a clear message and no change is persisted.
3. **Given** an Administrator, **When** they submit a brand colour that does not match `^#[0-9A-Fa-f]{6}$` (e.g. 3-digit shorthand, a named colour, or `rgb(...)`), **Then** it is rejected with a validation message.
4. **Given** no branding has been set, **When** a candidate-facing surface reads branding, **Then** the documented default brand is returned (never an error or broken asset); branding resolves **per attribute** — an unset logo falls back to the default placeholder even when a custom colour is set, and vice versa.
5. **Given** a non-Administrator, **When** they attempt to change branding, **Then** the request is refused (HTTP 403).
6. **Given** a previously-set logo, **When** an Administrator unsets it, **Then** the branding read returns the documented default placeholder and the change is audited.

---

### User Story 4 - Administrator configures the email-sending domain and provider credential (Priority: P2)

An Administrator configures the email-sending domain (the "from" domain candidates see) and the email-provider API credential, so that the email channel (delivered in F22) sends from the company's own domain. The credential is handled as a secret — stored encrypted at rest, never returned in any read, and never written to logs.

**Why this priority**: The email channel is a critical path for the product, but its actual delivery is F22; F03's job is to capture and safely store the configuration F22 will consume. It is P2 because no email is sent until F22 lands; the secret-handling requirement, however, is a hard security gate that F03 must get right today.

**Independent Test**: As an Administrator, set the sending domain and provider credential; confirm the domain is format-validated and persisted; confirm a subsequent read returns the domain but **never** the credential value (masked or omitted); confirm the credential is stored as ciphertext (a raw datastore read shows no plaintext) and appears in no log line; confirm a non-Administrator is refused.

**Acceptance Scenarios**:

1. **Given** an Administrator, **When** they set a syntactically valid sending domain and a provider credential, **Then** the domain is persisted and the credential is stored encrypted at rest.
2. **Given** a configured provider credential, **When** any settings read is performed (by any role, including Admin), **Then** the response never contains the credential value — only a masked/placeholder indicator that one is set.
3. **Given** the full configure-and-read flow, **When** application logs are scanned, **Then** zero occurrences of the credential value (or `api[_-]?key`/`secret`/`password` token values) appear at any level.
4. **Given** an Administrator, **When** they submit a malformed sending domain, **Then** it is rejected with a validation message and nothing is persisted.
5. **Given** a non-Administrator, **When** they attempt to view or set the sending domain or credential, **Then** the request is refused (HTTP 403).
6. **Given** a configured provider credential, **When** an Administrator sets a new credential value (rotation), **Then** the stored ciphertext is replaced, the old value is unrecoverable, the masked indicator still reports "set", and the rotation is audited.
7. **Given** a configured provider credential, **When** an Administrator clears it (unset), **Then** the read reports "not set" and the change is audited.
8. **Given** a configured credential and branding, **When** the application is restarted, **Then** the credential still decrypts to its original value for F22's contract (the masked read still reports "set") and the branding is still returned — verified by an integration test that reads the credential field via the raw datastore driver (bypassing the decrypting property converter) and confirms ciphertext at rest.

---

### User Story 5 - Administrator governs template editability (Priority: P2)

An Administrator marks specific email templates as "locked" so that Recruiters cannot edit them, ensuring brand-critical or compliance-critical messaging cannot be changed by operational users. The template content itself is owned by F21; F03 owns the lock state and the governance rule.

**Why this priority**: Template governance protects compliance and brand voice, but the template-editing surface it governs is delivered by F21. It is P2 because F03 establishes the lock state and the binding rule (a locked template is non-editable by Recruiters) that F21 MUST enforce, demonstrable today against the governance record without requiring the full template editor.

**Independent Test**: As an Administrator, set the locked state on a template identifier and confirm it persists; confirm the lock read exposes the state in the shape F21 will consume; confirm only an Administrator can change the lock state; confirm the binding rule (Recruiter edit of a locked template is refused) is specified and contract-tested against the governance primitive.

**Acceptance Scenarios**:

1. **Given** an Administrator, **When** they mark a template as locked, **Then** the locked state is persisted and returned by the governance read.
2. **Given** an Administrator, **When** they unlock a previously locked template, **Then** the state is updated and persisted.
3. **Given** a non-Administrator, **When** they attempt to change a template's lock state, **Then** the request is refused (HTTP 403).
4. **(F21 forward contract — not exercised by F03's test suite)** **Given** a template marked locked, **When** F21's template-edit action is later exercised by a Recruiter, **Then** the edit is refused — F03 specifies this binding rule and it is verified by F21's contract test against this governance state. F03 itself verifies only lock-state persistence and read-shape (AS-1/AS-2).

---

### User Story 6 - Administrator settings experience in the frontend (Priority: P3)

A signed-in Administrator reaches a dedicated workspace-settings area (and, on first run, the wizard) in the application; non-Administrators do not see the settings navigation and, if they navigate to it directly, are sent to the `/not-authorized` page (per F02), not a broken screen. All settings UI strings use the project's localization mechanism.

**Why this priority**: The server is the security boundary (Stories 2-5); the frontend is a usability and defense-in-depth layer. It is P3 polish that the Definition of Done requires but is never the sole gate.

**Independent Test**: Sign in as an Administrator and confirm the settings area and (for an unconfigured workspace) the wizard are reachable; sign in as each non-Administrator role, confirm the settings navigation is hidden and direct navigation redirects to `/not-authorized`; confirm the underlying settings API independently refuses the non-Administrator call.

**Acceptance Scenarios**:

1. **Given** an Administrator on an unconfigured workspace, **When** they sign in, **Then** they are routed to the setup wizard.
2. **Given** a non-Administrator, **When** they navigate directly to the settings route, **Then** they are redirected to `/not-authorized` (not a 404 or error page) and the settings API independently refuses the call.
3. **Given** any settings/wizard UI string, **When** it renders, **Then** it uses the project's localization mechanism (no hard-coded user-facing text).
4. **Given** a route guard for the settings route, **When** exercised in a frontend unit test, **Then** the Administrator role passes and **each** non-Administrator role is redirected to `/not-authorized`.
5. **Given** an unconfigured workspace, **When** a non-Administrator signs in, **Then** they see a neutral "workspace setup pending" state (not the wizard, not an error), and any direct call to a configuration endpoint still returns HTTP 403.

---

### Edge Cases

- **Wizard completion without retention acknowledgment**: Refused; the workspace stays *unconfigured* — the retention acknowledgment is a mandatory GDPR gate, not a default-checked box.
- **Re-running the wizard**: Once a workspace is configured, the first-run wizard is not shown again; subsequent changes go through the ongoing-settings surface (Story 2). The transition *unconfigured → configured* is one-way, enforced server-side: a direct re-call of the wizard-completion endpoint on a configured workspace is refused (no second transition, no re-acknowledgment).
- **Concurrent first-run completion**: Two Administrators completing the wizard simultaneously on an unconfigured workspace resolve to exactly one *configured* record (race-safe via an atomic upsert keyed by `workspaceId` + the unique index); the second is a benign no-op or "already configured", never a duplicate document or a server error. Both attempts are audited.
- **Credential rotation / unset**: Rotating the provider credential overwrites the stored ciphertext; the old value is unrecoverable. Clearing it returns the read to "not set". Both are audited; neither is ever returned in a read.
- **Concurrent settings edits**: Two Administrators editing the same workspace concurrently resolve to a single internally-consistent record (no lost-update producing a torn document); both attempts are audited.
- **Invalid operational values**: Unknown time zone, working-hours end-before-start, negative/zero SLA window, retention period outside allowed bounds — each is rejected with a per-field message and persists nothing.
- **Provider credential exposure**: The credential is never returned by any read and never logged; a settings read shows only whether a credential is set. Rotating it overwrites the stored ciphertext; the old value is not retrievable.
- **Branding absent**: Candidate-facing reads of branding before any is set return a documented default brand, never an error or a broken image.
- **Retention period shortened below existing data age**: Reducing the retention period does not itself delete data; it only changes the policy the F04 enforcement evaluates. Reductions are audited so a destructive policy change is traceable (actual age-based flag/block/wipe is owned by F04 — see Assumptions).
- **Non-Administrator access**: Every workspace-configuration read and write is Admin-only; a direct API call by any other role is refused server-side (HTTP 403) regardless of what the UI exposes.
- **Single workspace (MVP)**: The MVP runs one workspace; there is no self-service workspace *creation* flow (agency multi-client workspaces are deferred to v2). The single workspace is bootstrapped (see Assumptions) and F03 configures it.

## Requirements *(mandatory)*

### Functional Requirements

#### Workspace record & setup

- **FR-001**: System MUST persist a single workspace-configuration record per workspace (in MongoDB) holding the workspace's settings, and MUST read these settings back unchanged across application restarts.
- **FR-002**: System MUST represent a workspace's configuration state as either *unconfigured* (no completed setup) or *configured* (setup wizard completed), and MUST expose this state so the frontend can route a first-run Administrator to the wizard.
- **FR-003**: System MUST allow an Administrator to complete a first-run setup wizard that captures, at minimum: workspace name, workspace time zone, working hours, default SLA silence window, and data-retention period.
- **FR-004**: System MUST require explicit acknowledgment of the data-retention period before the workspace can be marked *configured*; an attempt to complete setup without acknowledgment MUST be refused with no state change (GDPR gate). The acknowledgment is the legal artifact and MUST be **evidentially recorded** (acknowledging Administrator, timestamp, and the exact retention-period value acknowledged), using non-PII identifiers only, for GDPR accountability.
- **FR-005**: System MUST validate every setup/settings value before persisting, and on any invalid value MUST refuse the whole submission with a per-field message and persist nothing (no partial write). The bounds are: **time zone** = a valid IANA zone ID (e.g. `Europe/London`); **working hours** = wall-clock local times in that zone with end strictly after start on the same day (overnight windows are rejected in the MVP; DST handling is F12's concern); **SLA silence window** = an integer 1–30 days inclusive; **data-retention period** = an integer 30–3650 days inclusive (0 is rejected).
- **FR-006**: System MUST present the first-run wizard only while the workspace is *unconfigured*; once *configured*, the wizard MUST NOT be presented again (the transition is one-way), and further changes occur through the ongoing-settings surface.

#### Ongoing configuration & access control

- **FR-007**: System MUST allow an Administrator to update workspace operational settings (name, time zone, working hours, default SLA silence window) after setup, with the same validation as setup (FR-005).
- **FR-008**: System MUST restrict every workspace-configuration read and write — wizard, operational settings, branding, email/domain, and template governance — to **Administrators only**; any non-Administrator attempt MUST be refused with **HTTP 403** and no state change, consistent with the F02 deny-by-default model and the permission-matrix row "Workspace configuration → Admin only".
- **FR-009**: System MUST enforce this Administrator-only authorization on the **server** for every configuration endpoint, so a direct API request by a non-Administrator cannot read or change settings regardless of what the frontend exposes (the frontend guard is defense-in-depth).
- **FR-010**: System MUST ensure concurrent Administrator edits do NOT lost-update each other across **different** fields: a configuration write MUST use a targeted single-document update of the changed fields (not a whole-document read-modify-write), so one Administrator's change to field A is preserved when another concurrently changes field B. Multi-field values that must be mutually consistent (e.g. working-hours start and end) MUST be applied as one atomic single-document update. (MongoDB already guarantees single-document write atomicity, so a torn document cannot occur; the requirement here is the no-lost-update discipline, per the F02 read-modify-write lesson.)

#### Branding

- **FR-011**: System MUST allow an Administrator to set a workspace logo and brand colour, and MUST expose them through a read that candidate-facing features (F14/F30) consume.
- **FR-012**: System MUST validate branding inputs and refuse invalid ones with a clear message, persisting nothing: the **logo** MUST be a raster format from an explicit allow-list (**PNG, JPEG**; WebP optional) — **SVG and any markup/active-content format MUST be rejected** because the logo renders on untrusted candidate-facing pages — with the type validated by inspecting the actual file bytes (magic number), **not** the client-supplied content-type or extension, and both the byte size (≤ 1 MB) and decoded dimensions bounded; the **brand colour** MUST match `^#[0-9A-Fa-f]{6}$` (3-digit shorthand, named colours, and `rgb(...)` are rejected).
- **FR-013**: System MUST return a documented default brand (logo placeholder + default colour) when no branding has been set, so candidate-facing surfaces never render an error or broken asset.

#### Email-sending domain & provider credential (secret-safe)

- **FR-014**: System MUST allow an Administrator to configure the email-sending domain and the email-provider credential that the email channel (F22) will consume.
- **FR-015**: System MUST validate the sending domain's format before persisting and refuse a malformed domain with no state change.
- **FR-016**: System MUST store the email-provider credential **encrypted at rest** using a non-deterministic authenticated cipher (unique IV per write, reusing the application's existing AES-256-GCM at-rest approach); a raw datastore read MUST show ciphertext, never plaintext. No hash, fingerprint, prefix, last-N characters, or any other value **derived** from the credential may be persisted (so a low-entropy key cannot be brute-forced offline against a stored derivative). Encryption-at-rest and the never-return guarantee (FR-017) are **two separate controls**: encryption protects the datastore; never-return is achieved structurally by FR-017, not by encryption.
- **FR-017**: System MUST NEVER return the email-provider credential value in any read response (by any role, including Administrators). The credential field MUST be **structurally non-serializable** — excluded at the persistence/serialization boundary (write-only, never mapped onto any read DTO) — so that **no** current or future read path, including the public candidate-facing branding read and any later feature that loads the whole configuration record, can serialize it independently of that path's authorization. A settings read MUST expose only an opaque **boolean** "credential is set" indicator (never a masked fragment of the actual value), enabling rotation without disclosure.
- **FR-018**: System MUST NOT write the email-provider credential value (nor any `api-key`/`secret`/`password` value), in any encoding, to application logs at any level (including DEBUG/TRACE), nor to exception messages, stack traces, or `toString()` output (per the F01 dead-letter sanitization precedent); the configure-and-read **and error** flows MUST pass the project's PII/secret log scan with zero matches.

#### Template governance

- **FR-019**: System MUST allow an Administrator to set and clear a "locked" state on an email template (identified by the template key F21 will own), and MUST persist and expose that state for F21 to consume.
- **FR-020**: System MUST restrict changing a template's lock state to Administrators (FR-008); the binding rule that a **locked template cannot be edited by a Recruiter** is owned and enforced by F21 against this state when F21 lands (forward contract), and MUST be verified by F21's contract test against this governance record.

#### Data-retention policy (configuration + forward contract)

- **FR-021**: System MUST persist the workspace data-retention period as configured and acknowledged in setup (FR-003/FR-004) and allow an Administrator to change it later (FR-007 validation applies).
- **FR-022**: System MUST treat the configured retention period as the **binding policy input** for the data-retention enforcement delivered by F04 (age-based flagging of over-retention candidate records, blocking of new outbound communications to them, and Admin-confirmed permanent deletion via F04's shared wipe mechanism). F03 owns the configured period and this contract; F03 does NOT itself scan, flag, block, or delete candidate records (no candidate-communication or wipe surface exists until F04/F22). Changing the retention period MUST NOT itself delete any data.
  > **Scope decision (stakeholder-confirmed 2026-06-14)**: backlog acceptance criterion ISSUE-10 lists retention *enforcement* (flag/block/wipe) under F03, but the comms path (F22) and shared wipe mechanism (F04) do not exist yet, so building it in F03 would require a stub (constitution §II) or pulling F22/F04 forward (§I YAGNI). The stakeholder confirmed enforcement **binds in F04** (F03's immediate successor); F03 ships the configured period, the GDPR acknowledgment, and this binding contract. The backlog F03 entry is annotated to record this move.
- **FR-023**: System MUST audit a change to the data-retention period (actor, old period, new period, timestamp) so that a destructive policy change is traceable, using non-PII identifiers only.

#### Audit & logging (constitution §VIII)

- **FR-024**: System MUST record an audit entry for every workspace-configuration change (actor member, the setting or area changed, timestamp), and specifically for setup completion, retention-period changes, branding changes (set/unset), email/domain/credential changes (set/rotate/unset), and template-lock changes, using **non-PII internal identifiers only** (no email or name). Before/after values are recorded for the **data-retention period** (FR-023) because it is the destructive policy lever; other changes record the setting name only (never the credential value, set or unset).
- **FR-025**: System MUST NOT write any plaintext personal data (member email or name), the email-provider credential, or other secret values to application logs when logging a configuration action; only anonymised identifiers and the non-sensitive setting name may be logged.
- **FR-026**: System MUST keep configuration audit entries **append-only** — no update or delete path exists for an audit record (consistent with F04's audit-log rule), so a destructive policy change (e.g. shortening retention) cannot be erased from the audit trail.

### Key Entities *(include if feature involves data)*

- **Workspace configuration**: The single per-workspace operational record (kept small, read per request) holding configuration state (*unconfigured*/*configured*), name, time zone, working hours, default SLA silence window, data-retention period (+ acknowledgment), brand colour, a **logo reference** (the logo bytes live in a separate document/store, not inlined — see Branding), email-sending domain, email-provider credential (encrypted at rest, write-only), and template lock states. Source of truth consumed by later features.
- **Branding**: The logo asset and brand colour applied to candidate-facing surfaces. The logo bytes are stored as a **separate document/asset referenced** from the config record (NOT inlined into the per-request config document, to avoid bloating it and to stay well under the BSON document-size limit); a documented default applies per attribute when unset.
- **Email-sending configuration**: The sending domain (returnable) and the provider credential (encrypted at rest, never returned, never logged).
- **Template lock state**: A per-template-key flag indicating an Administrator has locked the template against Recruiter edits; the template content itself is owned by F21.
- **Data-retention policy**: The configured retention period and its acknowledgment; the binding input to F04's enforcement.
- **Workspace-configuration audit event**: An append-only record of a configuration change, referencing only non-PII identifiers (reuses the F01/F02 audit pattern/collection or a sibling collection — to be confirmed in plan.md).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For **each** of the five configuration surfaces (setup-completion, operational settings, branding, email/domain+credential, template-lock) and **each** of the four non-Administrator roles (Recruiter, Hiring Manager, Interviewer, Read-only), a contract test asserts a write is refused with HTTP 403 and no state change — 0 surface/role pairs without a test (the full 5×4 matrix).
- **SC-002**: For **each** configuration **read** surface and **each** of the four non-Administrator roles, a contract test asserts HTTP 403 (settings are not readable below Admin) — 0 surface/role pairs untested.
- **SC-003**: 100% of attempts to complete the setup wizard without acknowledging the data-retention period are refused with the workspace remaining *unconfigured* (0 unacknowledged completions); and a successful completion records an acknowledgment audit entry capturing actor, timestamp, and the acknowledged period (FR-004).
- **SC-004**: Configured workspace settings are read back unchanged after an application restart in 100% of cases, including: the provider credential still decrypts to its original value for F22's contract (masked read still reports "set") and branding is still returned (persistence verified by an integration test).
- **SC-005**: 0 occurrences of the email-provider credential value (or `api[_-]?key`/`secret`/`password` token values) in application logs across the full configure-, read-, **and error** flows with logging at its most verbose level (DEBUG/TRACE) during the scanned run (verified by automated log scan).
- **SC-006**: 0 read responses contain the email-provider credential value across all roles, including Administrators (the credential is write-only/masked) — verified by an adversarial contract test.
- **SC-007**: The stored email-provider credential is ciphertext at rest in 100% of cases (a raw datastore read returns no plaintext) — verified by an integration test.
- **SC-008**: 100% of invalid configuration submissions are refused with no persisted change (0 partial writes) — verified by validation tests covering the boundary cases: non-IANA time zone; working-hours end at-or-before start; overnight window; SLA window = 0, 31 (rejected) vs 1, 30 (accepted); retention = 0, 29, 3651 (rejected) vs 30, 3650 (accepted); malformed sending domain; logo > 1 MB, SVG/active-content, content-type/magic-byte mismatch; brand colour not matching `^#[0-9A-Fa-f]{6}$`.
- **SC-009**: Under concurrent Administrator edits to **different** fields of the workspace record, neither change is lost (the edit to field B is preserved when field A is concurrently changed) and the final state is internally consistent (0 lost-update inconsistencies across a concurrent integration test); under concurrent **first-run wizard** completion by two Administrators, exactly one transition to *configured* succeeds with no duplicate document and no server error; all attempts are audited.
- **SC-010**: 100% of configuration changes produce an audit entry with non-PII identifiers (0 changes without an audit record; 0 audit entries containing email/name/secret values) — verified by audit and log-scan tests.
- **SC-011**: The branding read endpoint returns a non-null brand object — the configured value, or the documented default (default colour `#1F2937` + default logo placeholder) — with a 200 status in both set and unset (including partially-set) states; 0 error or null responses (the candidate-side "no broken asset" rendering is a forward contract verified by F14/F30).
- **SC-012**: 100% of Administrator-only frontend routes (settings + wizard) have a unit test asserting the Administrator passes and **every** non-Administrator role redirects to `/not-authorized` (0 guarded routes without a per-role unit test).
- **SC-013**: 100% of configuration audit entries are append-only — 0 update/delete paths exist for an audit record (verified by the absence of any mutation/deletion API and an append-only contract test).

## Assumptions

- **Single workspace (MVP)**: The MVP operates exactly one workspace; the `workspaceId` already threaded through members/sessions (F01/F02) identifies it. There is **no self-service workspace-creation flow** in F03 — agency multi-client workspaces are explicitly deferred to v2 (backlog Deferred table). The single workspace is bootstrapped (its `workspaceId` and first Administrator are provisioned by F01 invitation/setup); F03 *configures* that workspace, it does not create workspaces. This is a **stakeholder-reversible** MVP decision; the per-workspace configuration record is keyed by `workspaceId`, so multi-workspace support later does not re-architect this feature.
- **First-Administrator bootstrap**: At least one Administrator exists before F03 runs (provisioned via F01). F03 assumes an Admin is present to complete setup; it does not provision the first Admin itself.
- **Workspace language deferred (stakeholder-confirmed 2026-06-14)**: Product spec §5.4 FR-20 lists "languages" among workspace settings, but the F03 backlog one-liner omits it. The stakeholder confirmed the MVP ships **single-language (EN)**; an Admin-configurable per-workspace default language/locale is **deferred**, and candidate-facing template/string localization is owned by F14/F21 (Angular `$localize` is the developer i18n mechanism today, not an Admin setting). This is recorded so the FR-20 omission is deliberate, not silent.
- **Logo intentionally outside the first-run wizard**: backlog US-F03-1 lists "logo" in the wizard, but F03 places branding (logo + colour) in the ongoing branding surface (Story 3), not the wizard, because branding is non-blocking for workspace readiness (a documented default brand applies until set, FR-013). The wizard stays minimal; this deviation is deliberate.
- **Retention enforcement binds in F04**: F03 owns the configured retention period, its acknowledgment, and the binding policy contract (FR-021/FR-022); the **age-based scan, comms-block, and Admin-confirmed wipe are owned by F04** and ride on F04's shared erasure/wipe mechanism and the F22 `EmailSender` consent/erasure gate. F03 cannot enforce the comms-block today because no outbound-communication path exists until F22. This mirrors F02's forward-contract pattern (constitution §II — a real contract consumed by the immediate next feature, not stubbed code). **If the stakeholder requires the retention scanner/blocker to ship inside F03 rather than F04, this must be decided before planning**, as it materially expands F03's scope.
- **Email-domain verification deferred to F22**: F03 captures and format-validates the sending domain and securely stores the provider credential; the actual deliverability handshake (DKIM/SPF/domain-ownership verification and live send) is owned by the email channel (F22), which the backlog explicitly states tests this end-to-end. F03's responsibility is correct capture and secret-safe storage.
- **Credential storage reuses existing at-rest encryption**: The email-provider credential is stored using the application's existing app-level AES-256-GCM at-rest approach already used for member PII (F01, `MongoPiiConfig`/`PiiCrypto`), so no new dependency or service is introduced (constitution §IV / gate C2). Per-workspace provider credentials cannot be Fly secrets (they are runtime data, not deploy-time config), so encryption-at-rest + never-return + never-log is the secret-safety model; the deploy-time master key remains a Fly secret.
- **Branding asset storage**: The logo is stored within the existing MongoDB datastore as a **separate document/GridFS entry referenced** from the config record (NOT inlined), not via a new object-storage service (constitution §IV / gate C2). This keeps the *operational* config record small and read-per-request while the logo bytes are fetched only by the branding read; the ≤ 1 MB cap (FR-012) keeps it well under the 16 MB BSON limit. Exact mechanism (sibling document vs GridFS) is a plan.md decision.
- **Template governance vs. template content**: F03 owns only the *lock state* keyed by template identifier; the template content, editor, and the enforcement of "locked ⇒ not editable by Recruiter" are owned by F21 and bind to this state when F21 lands (FR-020).
- **No external state**: All configuration reads/writes use the single instance + MongoDB only; no cache, queue, broker, or replica is introduced (constitution §IV / gate C2). The *operational* configuration record is small and read per request (logo bytes are NOT in it — see Branding asset storage); no time-based cache tier is added.
- **RBAC reused, not redefined**: Administrator-only enforcement uses F02's existing method-security mechanism and `SecurityConfig` chain ordering; F03 adds no new authorization library and maps every configuration endpoint to the F02 matrix's "Workspace configuration → Admin only" row.
- **Audit reuse**: Configuration audit reuses the F01/F02 audit collection/pattern (or a sibling collection); the exact choice and any index are confirmed in plan.md.
- **HTTP semantics**: 401 = unauthenticated (F01), 403 = authenticated-but-unauthorized (F02). A non-Administrator hitting any configuration endpoint receives 403.

## Notes for Planning (backend / topology — to be confirmed in plan.md)

These are flagged so the plan's Constitution Check passes cleanly; exact mechanisms belong in `plan.md`, not this spec.

- **Authorization mechanism (C4)**: Reuse F02's `@PreAuthorize` Admin-role enforcement and the existing `@Order(3)` main authenticated chain; no new authorization dependency. All F03 config endpoints MUST be mounted under the **internal (non-allow-listed) prefix** (e.g. `/api/internal/workspace/**`), NOT under any prefix the F02 inventory test allow-lists (`/api/public/`, `/api/candidate/`, `/actuator`, `/oauth2`, `/login/oauth2/code`, `/error`) — otherwise they would be silently exempt from both `RbacEndpointInventoryTest` and the auth chain. A class-level `@PreAuthorize("hasRole('ADMIN')")` on the controller satisfies the inventory test for all its handlers. Every handler maps to the matrix's "Workspace configuration → Admin only" row; a new internal endpoint without a declared role fails the inventory test by design.
- **Credential & secret handling (§VIII)** — TWO separate controls: (a) **encryption-at-rest** requires registering the existing `PiiStringConverter` for the new credential property in `MongoPiiConfig` (the converter is registered per `(class, field)`, so a new `registerConverter(WorkspaceConfig.class, "<credentialField>", …)` line is needed — it is NOT transparent), and note the converter **decrypts on every read**, so the populated field must never be logged/serialized; (b) the **never-return** guarantee (FR-017) is achieved separately by making the field write-only — never mapped onto any read DTO and excluded from `toString()`/exception messages. The converter alone does NOT satisfy never-return. Confirm the PII/secret log-scan CI step covers the new fields. The SC-007 ciphertext-at-rest test MUST read the field via the **raw driver collection** (`mongoTemplate.getCollection(...).find()`), bypassing the decrypting converter, per the F01 pattern.
- **Nullable denormalised/index fields (F01 lesson)**: If any new index F03 introduces is over a field absent for some states (e.g. an optional branding or domain field), follow the F01 pattern — annotate `@Field(write = Field.Write.NON_NULL)` so persisted nulls do not collide on a partial/unique index.
- **MongoDB indexes (F00.1 pattern)**: Declare a **unique** index on `{ workspaceId: 1 }` for the configuration collection so exactly one config document exists per workspace; declare any audit index consistent with F01/F02. Add a new Mongock change unit `@ChangeUnit(id = "004-workspace-config-indexes", order = "004", author = "system")` — the `id`/`order` are never renamed once applied (persisted in `mongockChangeLog`, CLAUDE.md). Create indexes via the native driver API (`mongoTemplate.getCollection(...).createIndex(...)`) and use a targeted `dropIndex(...)` (never `dropIndexes()`) in `@RollbackExecution`, per the F00.1 lesson. `order="004"` is confirmed correct after existing 001/002/003.
- **Singleton creation = atomic upsert (FR-001/FR-006)**: Create the singleton via an atomic upsert keyed by `workspaceId` (`findAndModify(..., upsert=true)`) so a concurrent second wizard submission resolves cleanly (no-op or "already configured"), never a 500 from the unique-index `DuplicateKeyException`.
- **Atomic configuration write (FR-010)**: Use targeted single-document field updates (`findAndModify`/`$set` of changed fields), NOT a whole-document read-modify-write, so concurrent Admin edits to different fields do not lost-update each other (the F02 read-modify-write lesson). Multi-field consistent values (working-hours start+end) are set together in one update.
- **Logo storage (FR-011/Story 3, gate C2)**: Logo bytes MUST NOT inflate the per-request config document. Store the logo as a reference to a separate document or GridFS entry (no new object-storage **service** — gate C2), so the operational config document stays small and the per-request read does not transfer image bytes. Cap the logo at ≤ 1 MB, well under the 16 MB BSON limit.
- **Default brand (FR-013)**: Default brand colour `#1F2937` + an embedded default logo placeholder, returned per-attribute when unset.
- **Setup-state gating (FR-002/FR-006)**: Surface only the **boolean** `configured` flag on the existing F01 `/me` response (authenticated-any-role) so the SPA shell can route any role without a separate Admin-only call (a non-Admin on an unconfigured workspace sees a neutral "setup pending" state, US6 AS-5); the full settings payload stays Admin-only (FR-008). The wizard is presented exactly once per workspace lifecycle.
- **Validation bounds (already fixed in FR-005/FR-012)**: SLA window 1–30 days; retention 30–3650 days; logo PNG/JPEG ≤ 1 MB (magic-byte validated, SVG rejected); brand colour `^#[0-9A-Fa-f]{6}$`; time zone = IANA id; working hours wall-clock, end-after-start, no overnight. Restated here so plan.md fixtures match the spec.
- **Sending-domain note (§VIII)**: The domain is format-validated only in F03; ownership/deliverability (DKIM/SPF) and any homograph/IDN normalization are F22's gate before live send. F03 MUST NOT present the domain as verified anywhere.
- **Script encoding (C5)**: Any new/changed `.ps1`/`.cmd`/`.bat` will be byte-scanned for non-ASCII before done.

## Dependencies

- **F02 (RBAC)**: complete — provides the Administrator role, the deny-by-default method-security mechanism, the `SecurityConfig` chain ordering, the 403 access-denied envelope, the build-time endpoint-inventory test, and the permission-matrix row "Workspace configuration → Admin only" that F03 enforces.
- **F01 (Authentication & Session Management)**: complete — provides the authenticated Administrator identity, the per-request server-side checks, the app-level AES-256-GCM at-rest encryption (`MongoPiiConfig`/`PiiCrypto`) reused for the provider credential, and the audit baseline. Also provides the first-Administrator bootstrap F03 assumes.
- **F00 / F00.1 / F00.2 (scaffold)**: complete — provides Mongock index bootstrap, structured no-PII logging + the PII/secret log-scan CI step, and the audit/observability baseline.
- **Consuming later features (forward contracts)**: F04 (data-retention enforcement reads the configured retention period and acknowledgment), F12 (rule engine reads time zone + working hours), F14/F30 (candidate-facing pages read branding), F21 (template library enforces lock state), F22 (email channel reads sending domain + provider credential), F31 (SLA nudge engine reads the default silence window). Each MUST honour F03's configuration contract when it lands.

## Constitution Alignment (informational)

- **C1 — MVP scope**: In scope. F03 is an MVP backlog item (Tier 0 Foundation, §5.4 FR-20).
- **C2 — no new service/queue/replica**: Satisfied. Single instance + MongoDB only; provider credential and branding stored in MongoDB (credential encrypted), no object store, cache, or broker added.
- **C3 — candidate PII exposure to unauthorized roles**: Not weakened. F03 touches no candidate PII; it stores workspace settings (Admin-only) and the retention *policy* that strengthens candidate-data minimization once F04 enforces it.
- **C4 — fixed stack**: Satisfied. Reuses Spring Security method security and the existing PII-encryption approach; no new dependency.
- **C5 — script encoding**: Any new/changed `.ps1`/`.cmd`/`.bat` will be byte-scanned for non-ASCII before done.
- **C6 — multi-role sub-agent review**: Performed at spec stage — Security/GDPR, Backend/DevOps, QA, and Business Analyst perspectives (2026-06-14); findings applied (see `checklists/requirements.md`). A further review runs at implementation task close.
- **C7 — zero tool downloads**: No build tool/runtime/CLI will be downloaded; highest already-installed versions used.
- **§VIII Security & Privacy**: Admin-only configuration enforced server-side (FR-008/FR-009), provider credential encrypted at rest (non-deterministic, no derived value) and structurally never returned/logged (FR-016..FR-018), append-only non-PII audit/logging (FR-024..FR-026), data-retention policy captured with a mandatory, evidentially-recorded GDPR acknowledgment (FR-004), and candidate-facing logo hardened against active-content/upload abuse (FR-012).
