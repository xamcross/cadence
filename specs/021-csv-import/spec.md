# Feature Specification: Standalone CSV Import Mode (F42)

**Feature Branch**: `021-csv-import`
**Created**: 2026-06-18
**Status**: Draft
**Input**: User description: "F42 Standalone CSV Import Mode - import candidates via structured CSV so Cadence is usable without an ATS; async processing, per-row validation, duplicate detection, CSV-injection safety, PII encryption at rest"

## Overview

Not every customer runs Greenhouse or Lever. A small or mid-size recruiting team often keeps its candidate list in a spreadsheet. This feature lets a Recruiter populate Cadence's candidate pipeline by uploading a structured **CSV file** — name, email, stage, requisition — so the product delivers value on day one **without any ATS integration**. After import, those candidates behave exactly like ATS-synced candidates: they can be scheduled, sent status links, and tracked, and they are subject to the same consent, erasure, retention, and role-based-access rules.

Importing a candidate list is a potentially large, slow, and error-prone operation, so the design centres on three things: it must **not block** the web request (a 500-row file is accepted immediately and processed in the background), it must give the Recruiter **per-row feedback** (which rows imported, which were rejected and why, which look like duplicates) rather than an all-or-nothing failure, and it must be **safe** — uploaded cell content is untrusted data (CSV-injection, oversized files, PII in logs) and is handled defensively.

This is the third and final inbound candidate path in the MVP (after F40 Greenhouse and F41 Lever) and the only one that requires no external provider. It reuses the existing candidate record, PII-encryption-at-rest, consent/erasure/retention model, and role-based access control rather than introducing new infrastructure; it introduces no queue broker (background processing uses the platform's existing scheduling/async primitive).

## Clarifications

### Session 2026-06-18

- Q: How is the import processed so a large file does not hold the HTTP request thread? → A: **Asynchronous job** — the upload endpoint persists the raw file/rows to a pending-import record and returns `202 Accepted` with a job id immediately; a background worker processes the rows and the Recruiter polls a status endpoint for progress and per-row results. No queue broker (constitution §IV); the platform's existing async/scheduling primitive is used.
- Q: What is the required CSV schema and which columns are mandatory? → A: **Required: `name`, `email`. Optional: `stage`, `requisition` (external job/requisition reference label), `phone`.** A header row is required; columns are matched by header name (case-insensitive, order-independent); unknown columns are ignored (not an error). This keeps the minimum viable import to the two fields the platform truly needs (a contactable identity) while letting a team carry stage/requisition/phone context if they have it.
- Q: When duplicates (by email) are detected against existing candidates, what happens? → A: **The import pauses for a Recruiter decision per the backlog AC** — duplicate rows are flagged and the import does not silently merge or silently skip; the Recruiter chooses, per flagged row (or for the whole flagged set), to **merge** (update the existing record) or **skip** (leave the existing record unchanged) before those rows commit. Non-duplicate rows are not blocked by an unresolved duplicate decision (they commit on their own). An unresolved duplicate job auto-expires after a configurable window, defaulting the unresolved duplicates to the safe **skip** action and disposing the raw rows (see FR-021a).
- Q: Does importing a candidate authorize emailing them? → A: **No.** Import never establishes lawful basis to contact a candidate; outbound candidate email stays gated by the existing consent/lawful-basis checks until basis is recorded through the existing flow. (Same posture as F40/F41.)
- Q: What file size / row-count limits apply, and what is the whole-file reject threshold? → A: **Configurable max file size, default 5 MB**, enforced before processing; and a whole-file reject if **more than 80% of rows fail validation** (a summary error, no rows committed) — both per the backlog AC. A configurable max row count bounds a pathological small-but-many-rows file.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Import a candidate list from CSV without an ATS (Priority: P1)

As a Recruiter with no ATS, I can upload a CSV file of candidates and have them appear in my Cadence pipeline, so that I can start scheduling and tracking candidates without integrating an ATS.

**Why this priority**: This is the entire reason the feature exists — it is the no-ATS on-ramp that makes Cadence usable for an SMB team on day one. Without it the product is unusable for any team that does not run a supported ATS. It is the smallest independently valuable slice: upload a clean file, candidates appear.

**Independent Test**: Upload a small, well-formed CSV (valid names/emails, optional stage/requisition) and confirm every row becomes a candidate in the workspace with name/email encrypted at rest, an associated requisition/stage label where provided, and that the imported candidates are indistinguishable from natively-created candidates in the pipeline (same scheduling, status, consent, erasure behaviour). No ATS connection is involved.

**Acceptance Scenarios**:

1. **Given** a Recruiter with a well-formed CSV (header row plus N valid rows), **When** they upload it, **Then** the system accepts the file immediately, returns a job identifier, and within the processing window every valid row appears as a candidate in the workspace with name/email stored encrypted at rest.
2. **Given** a CSV row carrying an optional stage and/or requisition reference, **When** it is imported, **Then** the candidate is associated with that requisition reference and stage label exactly as provided (raw label, no internal taxonomy required).
3. **Given** imported candidates, **When** a Recruiter views the pipeline (or schedules / sends a status link), **Then** the imported candidates behave identically to natively-created candidates with respect to scheduling, consent, erasure, retention, and role-based access.
4. **Given** an imported candidate for whom no email-contact consent/lawful basis has been recorded, **When** an outbound candidate email is attempted, **Then** it is blocked until basis is recorded through the existing consent flow (import alone does not authorize contact).

---

### User Story 2 - Per-row validation with actionable feedback (Priority: P1)

As a Recruiter, when my CSV has problems (bad email format, missing required field, malformed rows), I want each problem reported per row rather than the whole file rejected, so that I can fix only the broken rows and keep the good ones.

**Why this priority**: Real-world candidate spreadsheets are messy. An all-or-nothing import on a 500-row file with three bad rows is unusable. Per-row validation is what makes the import practical, and it is independently testable and valuable even before duplicate handling exists.

**Independent Test**: Upload a CSV mixing valid and invalid rows (e.g., one missing email, one malformed email, one empty name) and confirm the valid rows import while each invalid row is reported with its row number and the field/reason — without exposing the row's personal values in logs — and the importable rows still commit.

**Acceptance Scenarios**:

1. **Given** a CSV where some rows are valid and some fail validation (missing/blank required field, malformed email), **When** the file is processed, **Then** valid rows are imported and each invalid row is reported individually with its row number and the failing field and reason.
2. **Given** a file where more than 80% of rows fail validation, **When** it is processed, **Then** the whole file is rejected with a summary error and **no rows are committed**.
3. **Given** an upload that exceeds the configured maximum file size (or maximum row count), **When** it is submitted, **Then** it is rejected before processing with a clear size/limit error and nothing is imported.
4. **Given** any validation failure, **When** it is reported or logged, **Then** the log/record references only the row number and field name — never the field value (no candidate name/email/phone in logs).
5. **Given** a completed (or partially completed) job, **When** the Recruiter requests its status, **Then** they see overall progress, counts of imported / rejected / duplicate rows, and the per-row error list.

---

### User Story 3 - Duplicate detection with merge-or-skip resolution (Priority: P2)

As a Recruiter, when a row matches a candidate already in my workspace, I want to be warned and asked whether to merge or skip, so that I do not accidentally create duplicate candidate records or silently overwrite existing data.

**Why this priority**: Duplicate candidates corrupt the pipeline and the downstream metrics, but the import is already valuable without this (US1/US2). Duplicate handling builds on a working import and a working validation/status surface, so it is correctly P2.

**Independent Test**: Pre-seed a candidate, then upload a CSV containing a row with the same email; confirm the row is flagged as a duplicate warning (not auto-committed), the Recruiter is offered merge or skip, and the chosen action is applied (merge updates the existing record; skip leaves it unchanged) while non-duplicate rows in the same file commit independently.

**Acceptance Scenarios**:

1. **Given** a CSV row whose email matches an existing candidate in the same workspace, **When** the file is processed, **Then** the row is flagged as a duplicate warning and is **not** committed as a new candidate before the Recruiter decides.
2. **Given** flagged duplicate rows, **When** the Recruiter chooses **merge**, **Then** the existing candidate record is updated from the row (without creating a second record and without overwriting protected fields such as consent/erasure state).
3. **Given** flagged duplicate rows, **When** the Recruiter chooses **skip**, **Then** the existing candidate record is left unchanged and no new record is created.
4. **Given** a file containing both duplicate and non-duplicate rows, **When** it is processed, **Then** the non-duplicate valid rows commit without waiting on the duplicate decision.
5. **Given** two rows **within the same file** that share an email, **When** they are processed, **Then** they resolve to at most one candidate (intra-file de-duplication), not two.
6. **Given** a row whose email matches a candidate that is in an erased state, **When** it is processed, **Then** the erased record's personal data is not resurrected by the import (the merge does not re-populate erased PII).

---

### Edge Cases

- **CSV / formula injection**: a cell beginning with `=`, `+`, `-`, `@` (or tab/carriage-return variants) is stored as a literal string and never interpreted as a spreadsheet formula on later export; ingestion never evaluates cell content.
- **Oversized file**: a file above the configured size limit is rejected before any parsing/processing (protects the worker and memory).
- **Pathological row count**: a small file with an enormous number of rows is bounded by the configured max-row-count.
- **Malformed CSV structure**: rows with the wrong number of fields, unterminated quotes, embedded newlines inside quoted fields, or a stray delimiter are handled gracefully — a structurally broken row becomes a per-row error, not a whole-file crash.
- **Missing or wrong header**: a file with no header row, or missing the required `name`/`email` columns, is rejected with a clear schema error.
- **Encoding / BOM / whitespace**: leading/trailing whitespace is trimmed; a UTF-8 BOM and non-Latin names are handled without corruption; an email is normalized (e.g., lowercased/trimmed) for duplicate matching consistent with the existing candidate email-hash lookup.
- **Empty file / header-only file**: an empty or header-only file is reported as "0 rows imported", not an error/crash.
- **Duplicate email appearing many times in one file**: collapses to a single candidate (intra-file de-dup) and counts once.
- **Concurrent imports**: two import jobs for the same workspace running at once must not double-create or corrupt records; reconciliation is idempotent by the existing candidate keying.
- **Worker restart mid-job**: a background worker restart during processing must not double-import already-committed rows nor lose un-processed rows; the job resumes/completes idempotently.
- **Retention conflict**: an imported candidate is immediately subject to the workspace retention clock and existing retention flagging like any other candidate.
- **Role boundary**: a role not permitted to import (e.g., Interviewer, Read-only, Hiring Manager) cannot upload or trigger an import; access is refused server-side.

## Requirements *(mandatory)*

### Functional Requirements

**Upload & async processing**

- **FR-001**: The system MUST accept a candidate CSV upload, persist the submission as a pending-import job, and return a job identifier with `202 Accepted` immediately — without processing the rows on the request thread.
- **FR-002**: The system MUST process the import asynchronously using the platform's existing scheduling/async primitive, introducing no queue broker or additional infrastructure service.
- **FR-003**: The system MUST expose a job-status query that returns, for a given job: overall state (accepted / processing / awaiting-duplicate-decision / completed / rejected / failed / expired), progress, counts of rows imported / rejected / flagged-as-duplicate, and the per-row error and per-row duplicate lists. The status query MUST be scoped to the caller's workspace: an unknown job id and a job id belonging to another workspace MUST return an indistinguishable not-found, so the status surface is not a cross-workspace existence/count oracle.
- **FR-004**: The system MUST enforce a configurable maximum file size (default 5 MB) and a configurable maximum row count, rejecting an over-limit upload before processing with a clear limit error and importing nothing from it. The over-size rejection MUST occur at the upload-stream boundary (so an over-size file does not have to be fully buffered into memory and yields a clean limit error, not an internal-server error), and the system MUST bound per-field length and total parsed-cell size so a small file with pathologically long quoted fields cannot exhaust memory.
- **FR-005**: Background processing MUST be idempotent and restart-safe: a worker restart mid-job MUST NOT double-import already-committed rows or drop un-processed rows. Each data row MUST be committed at most once, keyed by a per-(job, row-number) idempotency marker so that re-processing an already-committed row is a no-op; the job completes exactly once per row.

**Schema & validation**

- **FR-006**: The system MUST require a header row and parse columns by header name (case-insensitive, order-independent), requiring `name` and `email`, accepting optional `stage` and `requisition` (external job/requisition reference label) and an optional `phone`, and ignoring unknown columns without error.
- **FR-007**: The system MUST validate each row independently and import valid rows even when other rows in the same file fail (per-row, not whole-file, validation) — except where the whole-file reject threshold (FR-008) applies.
- **FR-008**: The system MUST reject the entire file (committing no rows) with a single summary error when the validation-failure ratio exceeds 80% — defined as (count of rows failing validation) / (count of data rows N) > 0.80, evaluated only when N > 0. Duplicate-flagged rows are warnings, NOT validation failures, and MUST NOT count toward the failure numerator. Exactly 80% commits; above 80% rejects. A 0-data-row (empty or header-only) file is not a rejection — it resolves to "0 rows imported" (no divide-by-zero).
- **FR-009**: The system MUST report each invalid row with its row number, the failing field, and a value-free reason, and MUST report a structurally malformed row (wrong field count, unterminated quote, embedded newline) as a per-row error rather than failing the whole file. The reported row number MUST be the logical (record) row, not the physical line, so a quoted field spanning multiple physical lines yields a deterministic row number. Any parser/library exception MUST be reduced to a value-free category/cause before being logged or stored in a row result — the raw exception text (which may echo the offending cell) MUST NOT be logged or surfaced.
- **FR-010**: The system MUST validate email format and required-field presence at minimum; a row missing a required field or carrying a malformed email is a per-row validation failure. A file with no header row or missing the required `name`/`email` columns is rejected with a clear schema error (distinct from a per-row failure).

**Duplicate handling**

- **FR-011**: The system MUST detect a row whose (normalized) email matches an existing candidate in the same workspace using the existing keyed email-hash lookup (never the encrypted email value), flag it as a duplicate warning, and NOT auto-commit it as a new candidate before the Recruiter resolves it.
- **FR-012**: The system MUST let the Recruiter resolve flagged duplicates with a **merge** (update the existing record from the row) or **skip** (leave the existing record unchanged) decision, applicable **per flagged row** as well as to the whole flagged set (the Recruiter MAY merge some and skip others). The resolution action MUST: succeed only when the job is in the `awaiting-duplicate-decision` state and return a conflict response otherwise; be idempotent (re-submitting the same decision is a no-op, not a double-apply); and be restricted to the same roles permitted to import (FR-020). The resolution actor MUST be captured in the audit even when they differ from the original uploader.
- **FR-013**: The system MUST de-duplicate rows **within the same file** that share a normalized email so that a single candidate results (intra-file de-dup), not multiple. Two concurrent jobs importing the same new email MUST also resolve to exactly one candidate (idempotency anchored on the existing per-workspace email-hash keying, not on single-threaded processing).
- **FR-014**: A merge MUST be an atomic update guarded on the existing candidate being in the active (non-erased) state, so a merge that races an erasure resolves in favour of erasure (no TOCTOU resurrection of erased PII). A merge MAY overwrite only the candidate's content fields from **non-empty** CSV cells (name, phone, stage label, requisition reference) — a blank CSV cell leaves the existing value unchanged. A merge MUST NOT overwrite or downgrade: the email identity / email-hash, consent/lawful-basis state, erasure state, retention state, the candidate's status token/fields, the provenance marker, or any ATS reconciliation key (ATS provider / external reference) on an existing ATS-linked record.
- **FR-015**: The system MUST allow non-duplicate, valid rows to commit independently of any unresolved duplicate decision (a pending duplicate decision MUST NOT block clean rows). This refines the backlog AC wording "before the import commits": only the flagged duplicate subset waits for the decision; clean rows are not held.

**Security, privacy & safety**

- **FR-016**: The system MUST store imported candidate personal data (name, email, phone if present) encrypted at rest, consistent with the workspace's existing candidate data protection.
- **FR-017**: The system MUST NOT write CSV row content or any field value (name, email, phone, stage, requisition) to application logs at any level; validation and processing logs MUST reference only the row number, field name, internal candidate id, and job id — never field values.
- **FR-018**: The system MUST neutralize CSV/formula-injection. Ingestion MUST NEVER evaluate cell content. A cell value MUST be stored verbatim (so a legitimate value such as a `+`-leading phone number or a `-`-leading name is not corrupted); the formula-injection neutralization (escaping a leading formula-trigger character `=`, `+`, `-`, `@`, `|`, or a tab/CR/leading-whitespace-then-trigger variant) MUST be applied at the point any value is emitted into a spreadsheet/CSV context (e.g., a future export), not by mutating the stored value. The result is that such a cell is never executed as a formula in any consumer.
- **FR-019**: Importing a candidate MUST NOT by itself establish lawful basis to contact the candidate; outbound candidate email MUST remain gated by the existing consent/lawful-basis checks until basis is recorded.
- **FR-020**: The system MUST restrict uploading and triggering an import (and resolving its duplicate decisions) to the roles permitted to manage candidates (Admin, Recruiter); Hiring Manager, Interviewer, and Read-only roles MUST be refused server-side. Imported candidates MUST inherit the same role-based access control as natively-created candidates and MUST NOT widen any role's candidate visibility. (Honest bound: no candidate→requisition→assignment link exists in the codebase yet — Hiring-Manager requisition scoping is deferred to F51. Until then the imported `requisition` is a raw label only and an imported candidate is NOT visible to Hiring Manager / Interviewer / Read-only — the default is closed, never default-open.)
- **FR-021**: The raw uploaded file/rows held for processing MUST be protected as candidate PII: encrypted at rest if persisted and never logged. Any email retained on the import job for duplicate matching/merge MUST be stored as the keyed email-hash and/or the encrypted email value — never as plaintext email on the job record.
- **FR-021a**: The system MUST dispose of the raw uploaded rows once they are no longer needed to process and report results, and MUST NOT retain raw PII indefinitely. An import job MUST have a configurable maximum lifetime (TTL): a job left in the non-terminal `awaiting-duplicate-decision` state past that window MUST auto-expire — its unresolved duplicates default to the safe **skip** action, its raw rows are disposed, and it moves to a terminal `expired` state. A job orphaned by a worker crash MUST likewise have its raw rows reaped. Disposal MUST occur on every terminal path (completed / rejected / failed / expired).

**Lifecycle parity & auditing**

- **FR-022**: An imported candidate MUST be subject to the same right-to-erasure and retention enforcement as any other Cadence candidate.
- **FR-023**: The system MUST record, for each import job, an audit-appropriate summary (job id, actor, timestamps, counts processed/imported/rejected/duplicate, outcome) without recording candidate PII or raw cell values; per-candidate creation/merge follows the existing candidate audit path.
- **FR-024**: The system MUST represent an imported candidate with the same candidate record shape used by natively-created and ATS-imported candidates, carrying a non-ATS provenance marker so it is not confused with an ATS-linked record (and the ATS reconciliation keys are not mis-applied to it), so downstream features (pipeline, scheduling, status, dashboard) treat them uniformly.

### Key Entities *(include if feature involves data)*

- **Import Job (Pending Import)**: One per upload. Represents the submitted file and its processing lifecycle — internal id, workspace, submitting actor, state (accepted / processing / awaiting-duplicate-decision / completed / rejected / failed / expired), totals and progress counters, the configured limits/TTL in force, and timestamps. Holds (or references) the parsed rows only as long as needed to process and report — any retained email is the keyed email-hash / encrypted value, never plaintext — then disposes of raw PII on any terminal path or at TTL expiry.
- **Import Row Result**: A per-row outcome — row number, status (imported / rejected / duplicate-pending / merged / skipped), and for failures the failing field and value-free reason. Drives the status surface; carries no raw field values in logs/audit.
- **Imported Candidate (the existing Candidate)**: The candidate created or updated from a row — identity (name, email, optional phone) encrypted at rest, an associated requisition/job reference and stage label where provided, a provenance marker distinguishing a CSV-imported candidate from an ATS-linked one, and full participation in consent/erasure/retention/RBAC.
- **Requisition / Job reference (external label)**: The free-text requisition/job reference a row may carry, used to group candidates and (later) to scope Hiring-Manager visibility — stored as a raw label, no internal taxonomy.
- **Duplicate Decision**: The Recruiter's merge-or-skip resolution applied to the flagged duplicate set of a job.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A Recruiter can upload a well-formed CSV and have every valid row become a schedulable candidate in the workspace, with name/email encrypted at rest, with no ATS connection involved.
- **SC-002**: An upload of a 500-row file returns a job identifier in under **2 seconds** (the request does not block on processing), and the rows are fully processed in the background.
- **SC-003**: A file mixing valid and invalid rows imports **100% of the valid rows** and reports **every** invalid row individually with row number and failing field/reason; a file with >80% invalid rows commits **zero** rows and returns one summary error.
- **SC-004**: A row whose email matches an existing workspace candidate is **never** silently committed as a duplicate; it is flagged and resolved by an explicit merge/skip decision, and intra-file duplicate emails collapse to a single candidate.
- **SC-005**: **Zero** occurrences of candidate name, email, phone, or raw cell values appear in application logs across the full upload → validate → import → duplicate-resolve flow (verified by log scan); validation logs reference only row number, field name, and ids.
- **SC-006**: A cell beginning with a formula-trigger character is stored and re-exported as a literal string and is never evaluated as a formula (verified by an injection fixture).
- **SC-007**: Reading an imported candidate document directly shows name, email, and phone only as ciphertext (encrypted at rest).
- **SC-008**: An imported candidate cannot be sent an outbound email until email-contact consent/lawful basis is recorded — verified by attempting a send and observing it blocked.
- **SC-009**: An over-limit file (size or row count) is rejected before processing and imports nothing.
- **SC-010**: A simulated worker restart mid-import produces no duplicate candidates and no lost rows (the job completes exactly once per row).
- **SC-011**: A role not permitted to import (Hiring Manager, Interviewer, Read-only) cannot upload or trigger an import; the request is refused server-side, and imported candidates do not widen any role's visibility versus natively-created candidates.
- **SC-012**: Imported candidates are indistinguishable from natively-created candidates to the scheduling, status, consent, erasure, and retention flows (full lifecycle parity).
- **SC-013**: Two concurrent import jobs in the same workspace importing the same new email produce exactly one candidate (no double-create), and the upload handler performs no row validation/commit before returning the `202` (verified structurally, independent of wall-clock timing).
- **SC-014**: A CSV-imported candidate carries a CSV provenance marker and is never matched by the ATS reconciliation lookup; a CSV merge onto an existing ATS-linked record never alters that record's ATS reconciliation key or consent/erasure state.
- **SC-015**: A job left unresolved in `awaiting-duplicate-decision` past the configured TTL auto-expires (unresolved duplicates default to skip) and its raw uploaded rows are disposed — verified by confirming no raw row PII remains for the job after the window. The job-status endpoint returns an indistinguishable not-found for an unknown or cross-workspace job id.

## Assumptions

- **Async mechanism** *(resolved — Clarifications 2026-06-18)*: Background processing uses the platform's existing async/scheduling primitive and idempotency/checkpoint pattern; no message broker or additional infrastructure (constitution §IV). The upload endpoint returns `202` with a job id and the Recruiter polls a status endpoint; no websocket/push channel is introduced.
- **CSV schema** *(resolved — Clarifications 2026-06-18)*: Required columns `name`, `email`; optional `stage`, `requisition`, `phone`. Header row required; header-name matching is case-insensitive and order-independent; unknown columns are ignored. Phone is not a required import column in the MVP (the candidate record supports phone, but the CSV minimum is name+email); a `phone` column, if present, is accepted and encrypted.
- **Duplicate semantics** *(resolved — Clarifications 2026-06-18)*: Duplicate detection is by normalized email within the workspace (the existing email-hash lookup). The default safe behaviour is to flag and require an explicit merge/skip decision (per-row or whole-set) rather than silently merging or skipping; an abandoned decision auto-expires to skip (FR-021a). "Merge" updates only content fields (name, phone, stage, requisition) from non-empty cells via an active-state-guarded atomic write; the email identity, consent/lawful-basis, erasure state, retention state, status token, provenance marker, and any ATS reconciliation key are never overwritten by a merge.
- **Provenance**: Imported candidates carry a provenance marker (CSV import) distinct from the ATS-link fields (F40/F41), so the existing ATS reconciliation keys are not mis-used for CSV candidates and the two paths do not collide.
- **Limits** *(resolved — Clarifications 2026-06-18)*: Max file size default 5 MB (configurable); a configurable max row count bounds row-count abuse; whole-file reject at >80% invalid rows. Exact numeric defaults for row count are pinned in the plan.
- **Lawful basis / data-protection posture**: As with the ATS connectors, the customer workspace is the controller and warrants a lawful basis to process the uploaded candidate data; Cadence stores the field set encrypted at rest, enforces retention, and does not establish contact basis on import. The legal basis is a customer responsibility.
- **No ATS dependency**: This feature is independent of F40/F41; it requires no external provider and is the no-ATS on-ramp. It does not import resumes/attachments/custom fields/EEO data — only the enumerated columns (data minimization, consistent with F40/F41 FR-029).
- **Scope of "pipeline view"**: The F42 backlog says imported candidates appear "in the pipeline view identically to ATS-synced candidates"; the pipeline view (F51) is not yet built. This feature guarantees the imported candidate **record** has full parity (same shape, lifecycle, RBAC) so F51 renders it uniformly; it does not build F51.
- **Existing platform reuse**: Reuses the existing candidate record, candidate PII encryption (`PiiStringConverter` / email-hash), consent/erasure/retention model, scheduled-task/checkpoint/async pattern, and role-based access control rather than introducing new infrastructure.

## Out of Scope

- Any ATS connector (F40 Greenhouse, F41 Lever) and their sync/write-back machinery.
- The core dashboard (F50) and pipeline view (F51), including bulk actions and SLA colour-coding of imported candidates.
- Importing anything beyond the enumerated columns — no resumes/attachments, recruiter notes, custom fields, social profiles, or demographic/EEO data.
- Non-CSV import formats (Excel `.xlsx`, JSON, Google Sheets connectors).
- A scheduled/automated recurring CSV import (this feature is a manual, on-demand upload).
- Export of candidates to CSV (this feature is import-only; the injection-safety requirement covers cells that may later be exported by another feature).
- Establishing email-contact consent as part of import (consent remains a separate, explicit step).
- A real-time push/progress channel (status is polled).
