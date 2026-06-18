# Phase 1 Contracts — CSV Import API (F42)

All endpoints are **internal** (`/api/internal/import/**`), behind the F02 `@Order(2)` authenticated chain, class-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`. Every endpoint is workspace-scoped via `SessionService.Principal.workspaceId()`. Errors render through `CsvImportExceptionHandler` (`@Order(HIGHEST_PRECEDENCE)`, `@RestControllerAdvice(assignableTypes = CsvImportController.class)`, re-throws `AccessDeniedException`/`AuthenticationException` from its catch-all — the F31 lesson) as a value-free envelope `{"error": "<code>", "message": "<safe text>"}`. No response or error ever contains candidate PII or raw cell values.

`RbacEndpointInventoryTest` covers these (they are NOT in the no-login allow-list — they require a session).

---

## POST `/api/internal/import/csv` — upload a CSV (async)

Accept a multipart CSV, persist it, and return a job id immediately (no parsing on the request thread).

- **Auth**: Admin or Recruiter. Rate-limited via `CandidateRateLimiter` (advisory; 429 on breach).
- **Request**: `multipart/form-data`, part `file` = the CSV (`MultipartFile`).
- **Success**: `202 Accepted`
  ```json
  { "jobId": "665f...e21", "status": "ACCEPTED" }
  ```
- **Behavior**: enforce size (in-service gate, the multipart cap sits above it — D9), store bytes as an encrypted `CsvImportFile`, insert `CsvImportJob{status:ACCEPTED, expiresAt}`, return. The handler performs **no** parse/validate/commit (SC-002/SC-013, asserted structurally).
- **Errors**:
  - `400 invalid_import` — missing `file` part, empty body, or over the size limit (`MaxUploadSizeExceededException`/`MultipartException` mapped to 400 — the F03 lesson). Nothing is imported.
  - `429 rate_limited` — per-IP advisory limit exceeded.
  - `403` — role not permitted (HM/Interviewer/Read-only); rendered by the security chain.

> Schema/row validation is **not** done here — a malformed CSV is accepted (202) and surfaces as a job `REJECTED`/per-row results via status. (Exception: a non-CSV/over-size upload is refused at the boundary as above.)

---

## GET `/api/internal/import/{jobId}/status` — poll job progress & results

- **Auth**: Admin or Recruiter (the importing workspace only).
- **Resolution**: `findByWorkspaceIdAndId(workspaceId, jobId)` → empty throws `RbacExceptions.ScopedNotFoundException` (reused, not a new exception type — so it inherits the proven mapping); an unknown id OR a job in another workspace → **indistinguishable** `404 not_found` (no existence/count oracle — SC-015). `CsvImportExceptionHandler` (`@Order(HIGHEST_PRECEDENCE)`) overrides the global `RbacExceptionHandler` to keep the envelope byte-identical (the F31 lesson).
- **Success**: `200 OK`
  ```json
  {
    "jobId": "665f...e21",
    "status": "AWAITING_DUPLICATE_DECISION",
    "originalFilename": "candidates.csv",
    "totalRows": 120,
    "importedCount": 95,
    "rejectedCount": 7,
    "duplicatePendingCount": 18,
    "mergedCount": 0,
    "skippedCount": 0,
    "rejectionReason": null,
    "rowResults": [
      { "rowNumber": 4,  "status": "REJECTED",          "failingField": "email", "reason": "INVALID_EMAIL" },
      { "rowNumber": 9,  "status": "REJECTED",          "failingField": "name",  "reason": "MISSING_REQUIRED" },
      { "rowNumber": 12, "status": "DUPLICATE_PENDING", "existingCandidateId": "664a...0f1" },
      { "rowNumber": 13, "status": "IMPORTED",          "candidateId": "665f...a02" }
    ],
    "createdAt": "2026-06-18T10:00:00Z",
    "completedAt": null
  }
  ```
- **Notes**: `rowResults` carries **no raw cell values** — only row number, status, field name, value-free reason enum, and internal ids. `reason` is an enum string (MISSING_REQUIRED / INVALID_EMAIL / MALFORMED_ROW / FIELD_TOO_LONG); `rejectionReason` is SCHEMA_INVALID / TOO_MANY_INVALID / OVER_LIMIT or null. Large `rowResults` are bounded by `max-row-count`.

---

## POST `/api/internal/import/{jobId}/resolve` — resolve flagged duplicates (merge/skip)

- **Auth**: Admin or Recruiter (same workspace). The resolution actor is audited even if different from the uploader (FR-012).
- **Precondition**: job MUST be `AWAITING_DUPLICATE_DECISION` → else `409 invalid_state`.
- **Request** (per-row decisions and/or a default for the rest):
  ```json
  {
    "decisions": [
      { "rowNumber": 12, "action": "MERGE" },
      { "rowNumber": 27, "action": "SKIP" }
    ],
    "defaultAction": "SKIP"
  }
  ```
  - `action` ∈ `{MERGE, SKIP}`. `defaultAction` (optional) applies to any still-pending duplicate not named in `decisions`; if omitted, only the named rows are resolved and the job stays awaiting until all are decided (or the TTL defaults the rest to skip).
- **Success**: `200 OK` — returns the updated status body (same shape as GET status). Idempotent: re-submitting an already-applied decision is a no-op (SC — not a double-apply).
- **Behavior**:
  - **MERGE** → atomic active-state-guarded `updateFirst({_id, workspaceId, erasureState:ACTIVE} → $set non-empty content fields)`; overwrites `name`/`phone`/`importStageLabel`/`importRequisitionLabel` from non-empty cells only; never email identity/`emailHash`/consent/erasure/retention/status token/provenance (`origin`)/ATS keys (FR-014). A merge racing an erasure no-ops (matchedCount 0) → recorded as SKIPPED-equivalent (erased), never resurrecting PII.
  - **SKIP** → existing record unchanged; row → SKIPPED.
  - When no pending duplicates remain → job → COMPLETED, blob disposed.
- **Errors**: `409 invalid_state` (not awaiting), `400 invalid_request` (unknown rowNumber / bad action / row not a pending duplicate), `404 not_found` (unknown/cross-workspace job), `403` (role).

---

## DTOs (Java records, illustrative)

```java
// response
record UploadAccepted(String jobId, String status) {}
record JobStatusResponse(String jobId, String status, String originalFilename,
    int totalRows, int importedCount, int rejectedCount, int duplicatePendingCount,
    int mergedCount, int skippedCount, String rejectionReason,
    List<RowResultDto> rowResults, Instant createdAt, Instant completedAt) {}
record RowResultDto(int rowNumber, String status, String failingField, String reason,
    String existingCandidateId, String candidateId) {}
// request
record ResolveRequest(List<Decision> decisions, String defaultAction) {}
record Decision(int rowNumber, String action) {}  // action: MERGE | SKIP
```

All enum-valued fields are serialized as their `.name()` Strings (never the enum object — the F01.1 logstash Jackson footgun applies to logs, and DTO serialization stays String-stable).

---

## Internal service contract (not REST)

```java
// CsvImportService
UploadAccepted accept(String workspaceId, String actorMemberId, String filename,
                      byte[] bytes, String contentType);   // store blob + insert job, return 202 payload
JobStatusResponse status(String workspaceId, String jobId);        // scoped read, no-oracle 404
JobStatusResponse resolve(String workspaceId, String actorMemberId,
                          String jobId, ResolveRequest req);        // 409 unless AWAITING_DUPLICATE_DECISION

// CsvImportProcessor (called by the scheduler, per claimed job)
void process(CsvImportJob job);   // parse → validate → per-row CAS create / flag duplicate; idempotent per (job,row)

// CsvRowValidator (pure)
Optional<CsvRowFailureReason> validate(CsvRow row);   // value-free reason; null/empty = valid

// CsvInjectionEscaper (pure, export boundary)
String escapeForSpreadsheet(String cellValue);   // prefix-neutralize leading =,+,-,@,| / tab / CR; verbatim otherwise
```

CSV access (Commons CSV) is confined to `CsvImportProcessor`; the rest of the service layer depends on the normalized `CsvRow` POJO, so the parser is swappable behind that seam (structural test: no `com.cadence.service` class outside `CsvImportProcessor` references `org.apache.commons.csv` — the F22 `MailTransportSwapTest` constant-pool precedent).
