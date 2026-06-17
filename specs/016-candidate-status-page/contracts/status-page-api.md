# API Contracts: Candidate Status Page (F30)

All candidate endpoints ride the existing `@Order(2)` permitAll/STATELESS chain (token IS the auth, CSRF disabled, allow-listed in `RbacEndpointInventoryTest`), are per-IP rate-limited (429), and set `Cache-Control: no-store`. Internal endpoints ride the `@Order(4)` authenticated chain with method security and need `.with(csrf())` in MockMvc.

---

## A — Candidate: view status

`GET /api/candidate/status/{token}`

- **200** — resolved active candidate. Body (minimal, escaped, no candidate id, no PII beyond the recruiter-authored status text the candidate is meant to see):
  ```json
  {
    "displayState": "PUBLISHED",            // TERMINAL | PAST_DATE | PUBLISHED | UNDER_REVIEW
    "stage": "Onsite interview",            // present for PUBLISHED/PAST_DATE/TERMINAL
    "nextStep": "We are collecting interviewer feedback.",
    "expectedDate": "2026-06-19",           // present for PUBLISHED (and PAST_DATE as the elapsed date)
    "outcome": "IN_PROGRESS",               // IN_PROGRESS | COMPLETE_OFFER | COMPLETE_REJECTED
    "workspaceZone": "Europe/London"        // so the page can present the date unambiguously
  }
  ```
  - `UNDER_REVIEW` → `stage`/`nextStep`/`expectedDate` omitted; the page renders the neutral default.
  - `PAST_DATE` → carries `stage` + `expectedDate` (the elapsed date) for the "past the expected date" framing.
  - Branding (logo/colour) and the contact route are fetched separately via the public branding endpoint (F03) — the candidate page composes them.
- **404** — unknown / malformed / **erased** candidate → **byte-identical** body (no existence oracle, FR-031/SC-007): `{"error":"not_found"}`.
- **429** — > 10 requests/min per source (FR-030/SC-009): `{"error":"rate_limited"}`.
- Header: `Cache-Control: no-store` on every response.

---

## B — Candidate: request erasure

`POST /api/candidate/status/{token}/erasure-request`  *(affirmative POST; `GET` → 405 so a prefetch cannot trigger it)*

- **202** — **always** for a syntactically valid call, regardless of {newly recorded, already-open, unknown token, erased} → **indistinguishable** ack (no oracle, FR-023/SC-010): `{"status":"received"}`. A request is actually recorded only when the token resolves to an active candidate; idempotent (no 2nd PENDING).
- **429** — rate-limited.
- **405** — on `GET` (and other non-POST verbs).
- Routed to the Admin `confirm`/`reject` queue (existing F04 `ErasureRequestController`); **never erases immediately** (FR-019).

---

## C — Recruiter: publish status

`PUT /api/internal/candidates/{candidateId}/status`  ·  `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`

Request:
```json
{
  "outcome": "IN_PROGRESS",                 // required
  "stage": "Onsite interview",              // required non-blank for IN_PROGRESS
  "nextStep": "We are collecting feedback.",// required non-blank (all outcomes)
  "expectedDate": "2026-06-19"              // required for IN_PROGRESS; ignored/optional for terminal
}
```
- **200** — published; body echoes the resolved `displayState` and (for the recruiter) confirms persistence. Audited `STATUS_PUBLISHED`.
- **400 `invalid_status`** — dateless/contentless in-progress, or blank terminal message (FR-011/FR-012/SC-004). Value-free message.
- **404** — candidate not in caller's workspace **or** erased (scoped, oracle-free; FR-014). Indistinguishable from "missing".
- **403** — Hiring Manager / Interviewer / Read-only (status authoring is ADMIN|RECRUITER only — FR-010). 5-role matrix asserted.

---

## D — Recruiter: read status + current link

`GET /api/internal/candidates/{candidateId}/status`  ·  `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`

- **200** — the persisted status (decrypted for the authorized recruiter) **plus** the current candidate status link (`statusLink`, re-derived from the encrypted token) so the recruiter can copy/share it. Returns the link if present; if absent it triggers an **audited** lazy-provision (`STATUS_LINK_ISSUED`) — token issuance is never silent (FR-034). (Normal path: the token is provisioned at first publish, so this GET does not mint.)
- **404** — scoped (not in workspace / erased).
- **403** — HM/Interviewer/Read-only.

---

## E — Recruiter: rotate the status link

`POST /api/internal/candidates/{candidateId}/status/rotate-link`  ·  `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`

- **200** — `{ "statusLink": "https://app.../status?token=NEW" }`. The previous token no longer resolves (its `GET` → the indistinguishable 404, SC-011). Audited `STATUS_LINK_ROTATED`.
- **404** — scoped (not in workspace / erased).
- **403** — HM/Interviewer/Read-only.

---

## F — Internal SPI (no HTTP) — link derivation, reused by F31 later

`CandidateStatusService.statusLinkFor(workspaceId, candidateId) : String`
- Lazily provisions a token if absent (atomic `$set`), decrypts `statusToken`, returns `{spaBaseUrl}{spaStatusBasePath}?token={raw}`.
- Supplies the value for `MergeToken.STATUS_LINK` in the F21 merge context. F30 wires it into the `CONFIRMATION` built-in (+ `MergeTokenCatalogue` permission); F31's `HOLD_UPDATE`/`SLA_HOLDING` reuse it unchanged.

---

## Cross-cutting contract assertions (for tasks.md)

- **Dedicated exception handler (load-bearing for no-oracle)**: the existing `SchedulingExceptionHandler` is `@RestControllerAdvice(assignableTypes={...scheduling controllers})` and does NOT cover the F30 controllers. F30 MUST add a `@RestControllerAdvice` (new `CandidateStatusExceptionHandler` or widened `assignableTypes`) mapping `StatusNotFoundException`→byte-identical 404 `{"error":"not_found"}`, erasure-submit→identical 202 `{"status":"received"}`, `InvalidStatusPublish`→400 `invalid_status`, rate-limit→429. Without it the default `/error` body becomes the oracle.
- **No-oracle**: A (view) and B (erasure-submit) return identical status+body across {unknown, malformed, erased}. Timing dominated by the indexed hash read (FR-027 satisfied structurally — token is hashed + index-resolved, never byte-compared).
- **Read-your-write update reflect (SC-005/FR-013, the backlog E2E leg)**: publish v1 → GET A → assert v1; publish v2 (changed stage/date) → GET A → assert v2 (no stale read). Prove the *update*-reflects path, not just first-write visibility.
- **Concurrent publish (FR-016/Story2 AC-5)**: an explicit 2-writer integration test (the F21 `concurrentFirstEdit`/F13 claim-race precedent) asserts a single consistent published status, no partial/mixed state — not just a single-thread `$set` unit.
- **No-store** on every candidate response; the served SPA inherits `_headers` (`no-referrer` + CSP) — FR-032/SC-012.
- **RBAC inventory**: the new internal endpoints carry `@PreAuthorize` so `RbacEndpointInventoryTest` stays green; the candidate `/api/candidate/status/**` prefix is allow-listed.
- **PII/token**: no candidate PII / no token value in any response error path, log, audit, or persisted index field (SENTINELF30* scan).
- **Localization**: every candidate-facing string `$localize`-marked; the JSON contract carries no display copy (the page owns copy), only data + `displayState` + `workspaceZone`.
