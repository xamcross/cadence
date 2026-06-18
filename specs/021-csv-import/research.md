# Phase 0 Research — Standalone CSV Import Mode (F42)

All decisions below are constrained by: the fixed stack (§III), single-instance/no-broker topology (§IV), GDPR-by-default (§VIII), and the established Cadence implementation patterns (F00.2 scheduler, F04 candidate/PII, F03 multipart, F22/F40 outbox/sweep). No `NEEDS CLARIFICATION` remain after the spec clarify session and the plan-phase parsing-library question.

---

## D1 — Async processing primitive: `@Scheduled` sweep over a Mongo job table

**Decision**: The upload handler persists the job + raw blob and returns `202`; a `@Scheduled` worker (`CsvImportScheduler`, checkpoint `"csv-import-sweep"`, `SchedulerCheckpointService.start/complete` + `@PostConstruct registerReplayAction`, `@Query`+`Pageable` due-finder) claims and processes jobs via per-job `findAndModify` CAS — the `EmailDispatchScheduler`/`AtsSyncScheduler` shape.

**Rationale**: The codebase has **no `@EnableAsync`** and no `TaskExecutor`/`@Async` usage; the constitution §IV async rule explicitly names `@Scheduled`/`TaskScheduler` persisting job state to Mongo and prohibits queue brokers. The `@Scheduled`+checkpoint pattern is already the proven, missed-fire-safe primitive used by F22/F23/F31/F40. It gives restart recovery (the replay action) for free, which `@Async` (fire-and-forget on a thread pool, lost on crash) does not. A short `fixedDelay` (default `PT5S`) keeps perceived latency low.

**Alternatives considered**:
- **`@Async` + `ThreadPoolTaskExecutor`**: would process immediately after `202` but is **not crash-safe** (an in-flight import is lost on restart, violating FR-005), requires enabling a new primitive (`@EnableAsync`), and still needs a Mongo job record for status — so it adds a primitive without removing the job table. Rejected.
- **`TaskScheduler.schedule(...)` one-shot**: constitution-permitted but same crash-safety gap as `@Async` and no missed-fire replay. Rejected in favour of the uniform sweep pattern.
- **Synchronous processing in the request**: violates FR-001/SC-002 (500-row file would block the request thread). Rejected.

---

## D2 — CSV parser: Apache Commons CSV (new library)

**Decision**: Add `org.apache.commons:commons-csv:1.11.0` and parse with `CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreSurroundingSpaces(true)...`. Resolved by the user in the plan-phase question (Recommended option chosen).

**Rationale**: F42 parses **untrusted** files; FR-009 demands correct handling of quoted fields with embedded commas/newlines, escaped quotes, unterminated quotes, BOM, and a deterministic **logical** (record) row number for multi-physical-line records. Commons CSV is a small, mature, infra-free library that solves exactly these RFC-4180 cases and exposes `CSVRecord.getRecordNumber()` (the logical row number). The Dependency Policy (§Dependency Policy) permits an additional library recorded in the plan with a one-line justification; commons-csv is a parsing utility — **not** an infrastructure SDK (no Kafka/Redis/K8s client) and **not** a framework/stack substitution — so **C4 passes**. It is a Maven-Central artifact resolved by the existing Gradle build, distinct from the C7 tool-download prohibition (which targets build tools/runtimes/CLIs, not project dependencies).

**Alternatives considered**:
- **Hand-rolled RFC-4180 parser**: keeps the "no new runtime dependency" streak but owns a security-sensitive correctness surface (quoting/escaping/unterminated-quote recovery/BOM/logical-row numbering) on untrusted input — the highest-risk part of the feature. Rejected (user decision).
- **OpenCSV**: heavier, bean-binding-oriented, more opinionated defaults; commons-csv is leaner for the name/email/stage/requisition/phone subset. Rejected.
- **Jackson `jackson-dataformat-csv`**: pulls CSV through the Jackson stack — and the project has a documented Jackson-version sensitivity (the F01.1/logstash Jackson-3 footgun); avoid coupling untrusted CSV parsing to that surface. Rejected.

**Mitigations**: pin the version; bound per-field length and total parsed-cell bytes (FR-004) before/while reading so a malformed huge-quoted-field cannot exhaust memory; reduce any `CSVException`/`IOException` message to a value-free category before logging/storing (FR-009/FR-017).

---

## D3 — Raw uploaded bytes: encrypted, in a separate `csvImportFiles` collection, disposed on terminal

**Decision**: Store the uploaded bytes once, as a base64 string field on a `CsvImportFile` doc (one per job, unique `{jobId}`), **encrypted at rest** via a `MongoPiiConfig`-registered `PiiStringConverter` on that field. The worker reads it to parse; it is **deleted on every terminal path** (COMPLETED/REJECTED/FAILED/EXPIRED) and by the orphan reaper.

**Rationale**: The async design must hold the file between `202` and processing; FR-021 requires raw rows protected as PII (encrypted if persisted, never logged) and FR-021a requires disposal (no indefinite retention). A separate blob collection is the `workspaceLogo` precedent for **keeping the hot job doc small** (the F03 16 MB-BSON-bloat avoidance) — but `workspaceLogo.bytes` is stored **cleartext** (`byte[]`, not converter-registered), so it is NOT the encryption precedent. The encryption precedent is `emailProviderCredential`/`statusToken`: the `PiiStringConverter` is `MongoValueConverter<String,String>` and **cannot** encrypt a `byte[]`, so the payload is stored as a **base64 `String`** (`dataBase64`) and registered in `MongoPiiConfig` to be encrypted at rest with zero new crypto. (Choosing `String`-over-`byte[]` is the load-bearing design choice, not an arbitrary wrapper.) The job record itself never stores plaintext rows — only counts + value-free per-row results, and (for dedup) the **`emailHash`** per pending-duplicate row (never plaintext email — FR-021).

**Alternatives considered**:
- **Inline the bytes on the job doc**: bloats the frequently-read/updated job document and risks the 16 MB BSON cap for a 5 MB base64 (~6.7 MB) file plus per-row results. Rejected.
- **Parse fully on upload, store parsed rows (no raw blob)**: moves parsing onto the request thread (violates SC-002/SC-013) or requires holding parsed PII rows on the job (same encryption/disposal need, larger doc). Rejected.
- **Filesystem/temp storage**: the single Fly Machine has ephemeral disk and a restart would orphan files outside the Mongo-centric recovery model; also harder to encrypt/dispose consistently. Rejected (Mongo is the single data store, §IV).

---

## D4 — Duplicate detection + intra-file dedup via `emailHash`

**Decision**: Normalize email (trim + lowercase, matching `PiiCrypto.emailHash`), compute `emailHash`, and (a) within the file, collapse rows sharing an `emailHash` to one (intra-file dedup, first wins); (b) against the workspace, `findByWorkspaceIdAndEmailHash` — a match on an **active** candidate flags `DUPLICATE_PENDING`; no match → create. Concurrent jobs importing the same new email collapse to one candidate via the same per-workspace `emailHash` keying.

**Rationale**: FR-011/013 mandate email-based dedup using the keyed hash, never the encrypted email value (the ciphertext is randomized/non-queryable — the F01 lesson). `CandidateRepository.findByWorkspaceIdAndEmailHash` already exists (non-unique index from ChangeUnit005). Normalization must match `emailHash` exactly so the lookup is consistent.

**Concurrent-create guarantee (revised per QA review)**: the existing `{workspaceId,emailHash}` index (ChangeUnit005) is **non-unique** (an erased row `$unset`s `emailHash`; a full hard-unique index would collide on the present-as-null footgun — the F01 lesson). To make SC-013 ("two concurrent import jobs, same new email → exactly one candidate") **provable** rather than best-effort, ChangeUnit020 adds a **partial-unique** `{workspaceId,emailHash}` index over `{emailHash:{$exists:true}, origin:"CSV_IMPORT"}` (the F23 partial-unique precedent — the partial filter sidesteps the null-collision footgun). The CSV-create path then `insert`s and catches `DuplicateKeyException` (the F40 `reconcile` precedent), guaranteeing a single row. The existing-candidate duplicate-flag path (the primary control) catches the NATIVE/ATS match deterministically before insert.

**Honest residual**: a CSV-vs-NATIVE concurrent create of the same brand-new email is still best-effort (no cross-origin unique constraint), but the two-import-jobs case SC-013 names is now a hard guarantee.

**Alternatives considered**: a unique **full** `{workspaceId,emailHash}` index — rejected (erased/legacy candidates + `write=NON_NULL` erasure-omission collide; the F01 present-as-null lesson). A purely best-effort lookup-then-insert with no unique index — rejected because it cannot prove SC-013 (the QA finding). The partial-unique-over-CSV-origin index is the minimal change that makes SC-013 provable without re-introducing the null-collision footgun.

---

## D5 — CSV-injection neutralization at the export boundary (store verbatim)

**Decision**: Store every cell value **verbatim** (the candidate's real name/phone, even if it begins with `+`/`-`). Apply formula-injection escaping (prefix-neutralize a leading `=`,`+`,`-`,`@`,`|`, or tab/CR/leading-whitespace-then-trigger) **only at the point a value is emitted into a spreadsheet/CSV context** (a future export). Ingestion never evaluates a cell. A `CsvInjectionEscaper` utility owns the emit-time escape and is the structural-test target.

**Rationale**: Spec FR-018/SC-006 (refined in the spec's security review): mutating-on-store would corrupt legitimate data (a `+44…` phone, a `-`-led name) and those values are only dangerous when re-opened in a spreadsheet, i.e. on export. F42 has no export path (out of scope), so the escaper exists and is unit-tested now and is the single sink any future export must call; the storage layer stays lossless. This is the safer, data-preserving control.

**Alternatives considered**: mutate-on-store (prefix `'` before persisting) — corrupts real PII and is irreversible; rejected. Reject-on-trigger — would drop legitimate rows; rejected.

---

## D6 — Provenance: `Candidate.origin` + `importJobId`

**Decision**: Add `CandidateOrigin{NATIVE, ATS, CSV_IMPORT}` `origin` (`@Field(write=NON_NULL)`, null⇒NATIVE for legacy docs) and `importJobId` to `Candidate`. Import stamps `CSV_IMPORT` + the job id; native/ATS paths set `NATIVE`/`ATS` (or leave null⇒NATIVE).

**Rationale**: FR-024/SC-014 require an explicit, queryable provenance marker so a CSV candidate is never confused with — or matched by — the ATS reconcile lookup, and so F50/F51 can render uniformly. Because a CSV candidate has **no `atsProvider`**, the F40/F41 `findByWorkspaceIdAndAtsProviderAndAtsExternalRef` structurally cannot match it; `origin` makes the distinction explicit and auditable. Additive fields on `Candidate` follow the documented "later features extend Candidate" pattern; both are non-PII and retained on erasure.

**Alternatives considered**: overload `atsProvider=null` as "CSV" — implicit and fragile (null also means "native, not yet ATS-linked"); rejected. A separate provenance collection — unnecessary join for a one-enum fact; rejected.

---

## D7 — `>80%` whole-file reject ratio definition

**Decision**: Reject the whole file (commit nothing, job→REJECTED) when `failures / N > 0.80`, where `N` = count of data rows (a structurally **malformed** row IS a data row and counts in `N`, and is itself a validation failure → counts in `failures`) and `failures` = rows failing **validation** (missing-required / invalid-email / malformed / field-too-long). Duplicate-flagged rows are **excluded** from both `failures` and treated as warnings (they still count in `N`). Evaluate only when `N > 0`; a 0-data-row file → COMPLETED with 0 imported (no divide-by-zero). Exactly 80% commits; strictly above rejects (e.g. 5 rows, 4 malformed = exactly 80% → commits the 1 good row; 5 rows, 5 malformed = 100% → rejects).

**Rationale**: FR-008/SC-003 (sharpened in the spec's QA review) needs a crisp denominator and boundary so the gate is deterministically testable. Duplicates are warnings (a separate flow), not validation failures, so counting them would spuriously trip the reject. A schema-level failure (no header / missing required column) is a distinct whole-file reject evaluated before the ratio.

**Alternatives considered**: count duplicates as failures — would reject a clean-but-mostly-duplicate re-upload; rejected. `>=80%` — chosen `>80%` to match the spec wording ("more than 80%").

---

## D8 — Job TTL + disposal reaper

**Decision**: A `cadence.import.job-ttl` (default `PT24H`) bounds a job's life. `CsvImportScheduler`'s reaper pass finds jobs in `AWAITING_DUPLICATE_DECISION` older than the TTL (and orphaned `PROCESSING` jobs past a processing threshold), defaults their unresolved duplicates to **skip**, disposes the `CsvImportFile` blob, and CASes the job → `EXPIRED`. Blob disposal also runs on COMPLETED/REJECTED/FAILED.

**Rationale**: FR-021a/SC-015 — `AWAITING_DUPLICATE_DECISION` is non-terminal, so without a TTL an abandoned decision retains raw PII indefinitely. Defaulting to skip is the safe non-mutating action (never silently merges). The reaper reuses the scheduler's checkpoint sweep (no new infra). The TTL/threshold invariant: `processing-threshold > sweep-fixedDelay + max per-job processing time` so the reaper never races a live worker (the F22 `reaperThreshold` precedent).

**Alternatives considered**: a Mongo TTL index that hard-deletes the job — rejected: it would also delete the value-free status/audit record (FR-023) and can't run the skip-default logic; a TTL index on the **blob** collection alone is a viable backstop but the application reaper is needed anyway to flip job state + default skips, so disposal is driven by the reaper for one consistent path (a blob TTL index may be added as defense-in-depth in data-model).

---

## D9 — Upload-size safety (multipart limit above the in-service gate)

**Decision**: Set **both** `spring.servlet.multipart.max-file-size` **and** `spring.servlet.multipart.max-request-size` just **above** `cadence.import.max-file-size` (default 5 MB → both multipart caps e.g. 6 MB) so an over-size file reaches the handler and is refused with a clean `400 invalid_import`; map `MaxUploadSizeExceededException`/`MultipartException` → 400 in `CsvImportExceptionHandler`. Bound row count and per-field/total parsed-cell bytes in-service.

**Rationale**: The F03 logo lesson — if the container limit equals/below the in-service limit, the container 500s before the handler. F03 currently sets `max-file-size: 2MB` **and** `max-request-size: 2MB` for the logo; **both** must be raised (a 5 MB file part inside a multipart request trips `max-request-size` first regardless of `max-file-size`). Raising the global multipart caps is acceptable: `BrandingService.validateLogo` enforces its own unconditional 1 MB `LOGO_MAX_BYTES` gate independent of the container cap (verified — a 1–6 MB logo still refuses cleanly), so the shared raise does not weaken the logo control. A tasks check re-asserts the existing oversize-logo→400 test still passes under the raised caps.

**Alternatives considered**: a per-endpoint multipart resolver — more config for no benefit at MVP scale; rejected. Streaming-parse without a size cap — rejected (memory DoS, FR-004).

---

## D10 — No integration-pending stub; demonstrable end-to-end

**Decision**: F42 ships fully end-to-end (Angular upload → Spring async import → Mongo → imported candidate visible/contact-gated) with **no** stub, because there is **no external provider** (it is the no-ATS path).

**Rationale**: §II requires a real browser-to-DB flow; F42 has one natively. Unlike F40/F41 (which stub Greenhouse/Lever pending credentials), CSV has nothing to stub. The recruiter screen is internal (no §IX gate — the F50/F51 precedent); the §II leg is the real upload→status→resolve flow + Jasmine on the component.
