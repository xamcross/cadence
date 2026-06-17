# Feature Specification: Candidate Status Page

**Feature Branch**: `016-candidate-status-page`  
**Created**: 2026-06-17  
**Status**: Draft  
**Input**: User description: "create a spec for the next backlog feature. review with appropriate sub-agents" (resolves to **F30 — Candidate Status Page**, the next feature in the backlog delivery sequence)

## Overview

Every active candidate gets a private, no-login web link that honestly shows where they are in the process: their current stage, a plain-English description of what happens next, and an expected date. The page directly attacks the #1 candidate complaint — silence after applying or interviewing. Recruiters keep the stage, next-step text, and expected date current from their internal candidate view, and the system refuses to publish a vague "we'll be in touch" with no date. The page is also the candidate's self-service route to request erasure of their personal data.

This feature is within MVP scope (product spec §11; backlog F30). It depends only on already-delivered capabilities: the candidate record and GDPR/erasure machinery (F04), candidate-facing private-link + rate-limiting + accessibility patterns (F13/F14), branding (F03), and email delivery (F22).

> **Multi-role review applied (2026-06-17)**: Business Analyst, Security/GDPR Lead, and QA Lead reviewed the draft. Their findings — long-lived-token transport controls, token rotation, free-text safe-rendering + at-rest posture, erasure-submit oracle/abuse-limit, quantified rate-limit, page-state precedence, testable stale-date behaviour, scoping writes to Recruiter/Admin — are incorporated below.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Candidate sees their honest status (Priority: P1)

A candidate who has applied or interviewed opens their private status-page link (no account, no login) and immediately sees their current stage, a plain-English explanation of what happens next, a concrete expected date (e.g. "Feedback expected by Thursday 19 June"), and how to reach the recruiting team. The page is branded to the hiring company and works on a phone.

**Why this priority**: This is the core value of the feature and the anti-ghosting promise of the product. Without it there is no candidate-facing transparency. It is independently demonstrable and delivers the MVP value on its own. (Story 1 and Story 2 are both P1 and ship together: Story 1 has nothing truthful to show until Story 2 lets a recruiter publish a status.)

**Independent Test**: Open a valid status link for a candidate whose recruiter has set stage + next-step + expected date; verify all three render correctly, in the candidate's local time zone, plus a contact route, on a 375 px mobile viewport, with no login prompt and no personal data in the URL.

**Acceptance Scenarios**:

1. **Given** an active candidate with a recruiter-set stage, next-step text, and expected date, **When** the candidate opens their status link, **Then** the page shows the stage, the next-step text, the expected date, and a contact route, branded to the workspace, with no login required.
2. **Given** the candidate is on a 375 px mobile screen, **When** the page loads, **Then** all content is readable without horizontal scrolling and the page is usable.
3. **Given** an expected date is set, **When** the page renders for a candidate in a different time zone, **Then** the date/time is displayed clearly and unambiguously to that candidate.
4. **Given** a candidate's process is complete (offer extended or rejection decided), **When** they open the page, **Then** the page honestly reflects the completed outcome rather than implying the process is still ongoing.
5. **Given** a recruiter has typed next-step text that contains markup characters, **When** the candidate views the page, **Then** the text renders as inert literal characters (no markup is interpreted or executed).

---

### User Story 2 - Recruiter keeps the status current (Priority: P1)

A recruiter, from their internal candidate view, updates a candidate's current stage, the plain-English next-step description, and the expected date. The system requires all three and blocks a publish that would show a no-date "we'll be in touch" message.

**Why this priority**: The page is only valuable if it is honest and current; the recruiter-update path is what keeps it that way and what enforces the "no silent, dateless holding message" product rule. P1 because Story 1 has nothing truthful to show without it.

**Independent Test**: As a Recruiter, set stage + next-step + expected date for a candidate and confirm it persists and surfaces on the candidate page; then attempt to save a status with a missing expected date and confirm it is rejected with a clear validation message.

**Acceptance Scenarios**:

1. **Given** a recruiter editing a candidate's status, **When** they submit stage + next-step + expected date, **Then** the update is saved, audited, and reflected on the candidate's status page on next load.
2. **Given** a recruiter submits a status update with no expected date, **When** they try to save, **Then** the system rejects it with a validation error and the candidate page is unchanged.
3. **Given** a recruiter submits a status update with empty or whitespace-only next-step text, **When** they try to save, **Then** the system rejects it (next-step description is required and non-empty).
4. **Given** a user without Recruiter/Admin permission, **When** they attempt to update a candidate's status, **Then** the request is refused server-side.
5. **Given** two recruiters submit different status updates for the same candidate near-simultaneously, **When** both complete, **Then** the candidate page shows exactly one consistent published status (last valid write wins, no partial/mixed state).

---

### User Story 3 - Candidate requests erasure from the status page (Priority: P2)

A candidate uses a clearly labelled control on their status page to request deletion of their personal data. The request is recorded and routed to an Admin for confirmation (it does not erase immediately). The candidate sees a confirmation that their request was received.

**Why this priority**: GDPR Article 17 requires a data-subject-initiated erasure path, and the product specifies the status page as where it surfaces. P2 because the operator-triggered erasure (F04) already exists; this adds the self-service entry point. The status page can ship and demo Story 1+2 without it, but the MVP is not complete without it.

**Independent Test**: From a valid status link, submit an erasure request; verify a routed erasure request (carrying no personal data) is recorded for Admin confirmation, the candidate sees an acknowledgement, a repeated submit does not create a second open request, and no personal data is exposed in the process.

**Acceptance Scenarios**:

1. **Given** a candidate viewing their status page, **When** they submit an erasure request, **Then** the request is recorded for Admin review and the candidate receives an on-page acknowledgement.
2. **Given** an erasure request has been submitted, **When** an Admin reviews requests, **Then** the candidate's request appears for explicit confirmation (the same confirm-before-wipe path as operator-triggered erasure), and no deletion has occurred yet.
3. **Given** a candidate's data has been erased, **When** their old status link is opened, **Then** no personal data is shown and the response is indistinguishable from an unknown/invalid link.
4. **Given** a candidate has already submitted an erasure request, **When** they submit again, **Then** no second open request is created and the candidate still receives an acknowledgement (idempotent, no Admin-queue flooding).

---

### Edge Cases

- **No status set yet**: An active candidate exists but the recruiter has not yet set stage/next-step/expected date. The page shows a neutral, honest "your application is being reviewed" state, never a broken or empty page (FR-006). *(The dateless-holding-message ban applies to recruiter-published content; the default pre-status state must still avoid implying ghosting.)*
- **Expected date elapsed**: The recruiter-set expected date is now in the past without an update. The page must not silently present the stale date as still-current; it shows an honest "past the expected date" framing while preserving the last known stage (FR-017).
- **Conflicting page states**: A candidate is simultaneously "complete" and "past expected date", or "under review" with a past date. A single precedence order resolves which state renders (FR-008).
- **Expired/invalid/erased link (view & erasure-submit)**: A link that is malformed, never existed, or belongs to an erased candidate must not reveal whether a candidate exists; behaviour is indistinguishable across these cases on both the view and the erasure-submit paths (FR-023, FR-031).
- **Leaked-but-not-erased token**: A still-valid status link is forwarded, screenshotted, or logged by an intermediary. A recruiter/admin can rotate the link to revoke the old one without erasing the candidate (FR-029).
- **Rate-limit abuse**: Repeated rapid requests to a status link or erasure-submit (guessing/scraping/spamming) are throttled (FR-022, FR-030).
- **Completed outcome**: Offer or rejection must read honestly; a rejected candidate must not see "interview being scheduled."
- **Long/RTL content**: A long unbroken stage name or right-to-left next-step text must not break the mobile layout or cause horizontal scroll (SC-003).

## Requirements *(mandatory)*

### Functional Requirements

**Candidate-facing status page**

- **FR-001**: The system MUST serve a candidate status page over a private link without requiring the candidate to log in or create an account.
- **FR-002**: The status page MUST display the candidate's current stage, a plain-English next-step description, and an expected date for the next step.
- **FR-003**: The status page MUST present the company branding (logo, brand colour) configured for the workspace (F03).
- **FR-004**: The status page MUST display dates/times unambiguously to the candidate, accounting for the candidate's local time zone.
- **FR-005**: When the candidate's process has concluded (offer or rejection), the status page MUST reflect that outcome honestly rather than implying the process is ongoing.
- **FR-006**: When no recruiter-published status exists yet for an active candidate, the page MUST show a neutral, honest "under review" state — never a broken, empty, or dateless "we'll be in touch" message.
- **FR-007**: The status page MUST provide a contact route to the recruiting team, sourced from workspace configuration (e.g. the workspace recruiting/support address); it MUST NOT echo back any candidate-specific personal data as the contact destination.
- **FR-008**: The page MUST resolve exactly one display state by a defined precedence: **terminal outcome (complete) > past-expected-date framing > published in-progress status > default under-review**. The chosen state is deterministic and the others are suppressed.
- **FR-009**: Recruiter-authored free text (stage label, next-step description) rendered on the candidate page MUST be output-escaped/sanitised so that any markup or script characters render as inert literal text — no stored content can execute or inject markup in the candidate's browser.

**Recruiter status maintenance**

- **FR-010**: Authorized users (**Recruiter or Admin**) MUST be able to set and update a candidate's stage, next-step description, and expected date. *(Hiring Manager has view access to their own candidates per F02; status authoring is Recruiter/Admin in the MVP, matching backlog US-F30-2.)*
- **FR-011**: The system MUST reject a status update that omits the expected date (all of stage + next-step + expected date are required for a published status).
- **FR-012**: The system MUST reject a status update whose next-step description is empty, absent, or whitespace-only, so a dateless/contentless holding message cannot be published.
- **FR-013**: A saved status update MUST be reflected on the candidate's status page on the next page load (read-your-write after a successful save).
- **FR-014**: Status maintenance MUST be permission-enforced on the server: a user without Recruiter/Admin rights to a candidate's workspace cannot update that candidate's status.
- **FR-015**: Every status change MUST be recorded in the candidate audit trail (actor, fields changed, timestamp), verifiable by an audit-record assertion.
- **FR-016**: Concurrent status updates to the same candidate MUST resolve to a single consistent published status (atomic last-valid-write-wins); no partial or mixed state can be observed.
- **FR-017**: When a published expected date has elapsed (the expected date is before the current instant, compared in the workspace time zone) without a recruiter update, the page MUST NOT present the stale date as a still-valid promise; it MUST instead show an honest "past the expected date" framing while preserving the last known stage. *(Prevents the page itself becoming a ghosting artifact.)*

**Candidate-initiated erasure**

- **FR-018**: The status page MUST offer the candidate a clearly labelled control to request erasure of their personal data.
- **FR-019**: An erasure request submitted from the status page MUST be recorded and routed to an Admin for explicit confirmation; it MUST NOT erase data immediately or without Admin action (reuses the F04 confirm-before-wipe path).
- **FR-020**: After submitting an erasure request, the candidate MUST receive an on-page acknowledgement that the request was received.
- **FR-021**: The recorded erasure-request artefact MUST carry no candidate personal data (internal identifiers only); the candidate's name/email/phone MUST NOT be copied into it.
- **FR-022**: Repeated erasure submissions for the same candidate MUST be idempotent / abuse-limited — a second submission while a request is already open MUST NOT create a duplicate open request, and the erasure-submit action MUST be rate-limited.
- **FR-023**: The erasure-submit response MUST be indistinguishable across valid, unknown, malformed, and already-erased links — it MUST NOT act as an existence oracle.
- **FR-024**: Once a candidate's data has been erased, their status link MUST no longer reveal any personal data or personal status, and the associated status token MUST be invalidated **atomically as part of the erasure wipe** (no window where an erased candidate still has a live, resolvable token).

**Link / token security**

- **FR-025**: The status link MUST contain only an opaque token — no candidate identifier, name, email, or other personal data in the URL.
- **FR-026**: The status token MUST be a cryptographically random value of at least 128 bits, stored only as a keyed hash at rest (never the raw value), consistent with the existing candidate-token precedent.
- **FR-027**: The status token MUST be bound to a single candidate record; a candidate MUST NOT be able to guess, derive, or enumerate another candidate's token. Token comparison MUST be constant-time.
- **FR-028**: The status token MUST remain valid for the candidate's active lifecycle (it is NOT short-lived like a scheduling link) and MUST be invalidated when the candidate is erased (see FR-024).
- **FR-029**: An authorized user (Recruiter/Admin) MUST be able to rotate/re-issue a candidate's status link, which invalidates the previous token — providing revocation for a leaked-but-not-erased link without erasing the candidate.
- **FR-030**: Requests to the status link and the erasure-submit action MUST be rate-limited per source to at most 10 requests per minute; requests beyond the threshold are rejected with a throttling response (HTTP 429).
- **FR-031**: For a malformed, unknown, or erased-candidate link, the **view** response MUST be indistinguishable across cases in status code and body (and not leak via timing) — it MUST NOT reveal whether a given candidate exists.

**Privacy / logging**

- **FR-032**: The candidate status page MUST be served with privacy transport controls so the long-lived token cannot leak: no-store/no-cache, `Referrer-Policy: no-referrer` (token never sent in a `Referer` header), and a content security policy — consistent with the existing candidate-page (F14) controls.
- **FR-033**: The system MUST NOT write candidate personal data (name, email, phone, the recruiter-authored stage label, or next-step content that may contain personal data) to application logs at any level; only internal identifiers may be logged.
- **FR-034**: The status token value MUST NOT appear in logs; token issuance/rotation MAY be audit-logged by internal candidate identifier only.

### Key Entities *(include if feature involves data)*

- **Candidate status**: The recruiter-maintained, candidate-visible state for a candidate — current stage label, plain-English next-step description, expected date, and a derived completion/outcome state. Belongs to exactly one candidate within one workspace. The free-text fields are treated as candidate-PII-adjacent (see Assumptions: at-rest posture).
- **Status access token**: An opaque, lifecycle-bound, rotatable credential that grants read access to exactly one candidate's status page and the ability to submit an erasure request for that candidate. Stored hashed; bound to the candidate; invalidated on erasure or rotation.
- **Candidate (existing, F04)**: The data-subject record. F30 associates candidate-visible status with it and reuses its erasure state and audit trail.
- **Erasure request (existing, F04)**: The routed, Admin-confirmable request record (identifier-only, no PII); F30 adds the candidate-initiated (status-page) entry point.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a valid status link at a 375 px viewport, the current stage, next-step text, and expected date are all rendered on first paint without scrolling (verified by automated test); a moderated usability check confirms a candidate can locate all three within 10 seconds.
- **SC-002**: The status page loads in under 2 seconds on a simulated 4G mobile connection and scores at least 85 on a mobile performance audit.
- **SC-003**: The status page has zero WCAG 2.2 AA accessibility violations in an automated audit, renders correctly at 375 px, 768 px, and 1280 px widths, and shows no horizontal scroll with long unbroken or right-to-left next-step content.
- **SC-004**: 100% of attempts to publish a status without an expected date or without non-empty next-step content are rejected (no dateless "we'll be in touch" can reach a candidate).
- **SC-005**: A recruiter status update is visible to the candidate on their next page load 100% of the time (no stale read after a successful save).
- **SC-006**: No candidate personal data (including the stage label and next-step free text) and no token value appears in any application log across status-view, status-update, rotation, and erasure-request flows (verified by automated log scan with sentinels in each free-text field).
- **SC-007**: A malformed, unknown, and erased-candidate link return view responses indistinguishable from one another in status code and body (no existence oracle), verified by test.
- **SC-008**: 100% of candidate-submitted erasure requests are recorded and routed for Admin confirmation; none trigger immediate deletion, and a repeat submission creates no second open request.
- **SC-009**: Requests to a status link or erasure-submit beyond 10 per minute per source are rejected with a 429 within the same request window.
- **SC-010**: The erasure-submit response is indistinguishable across valid, unknown, malformed, and erased links (no existence oracle), verified by test.
- **SC-011**: After a recruiter rotates a candidate's status link, the previous token no longer resolves (returns the indistinguishable not-found response) and the new token resolves correctly.
- **SC-012**: The status page response carries no-store/no-cache, `Referrer-Policy: no-referrer`, and a content-security-policy header (verified by test), and the token is never present in a referrer.
- **SC-013**: When a published expected date is in the past, the page renders the "past the expected date" framing (not the stale date as current) while preserving the last stage, verified by a test with a controlled clock.
- **SC-014**: Every status change produces an audit record (actor, changed fields, timestamp), verified by an audit-record assertion.
- **SC-015**: A recruiter-authored next-step value containing markup/script characters renders as inert literal text on the candidate page (no execution/injection), verified by test.
- **SC-016**: The page-state precedence (terminal > past-date > published > under-review) resolves deterministically for every combination, verified by test.

## Assumptions

- **Token model**: Each active candidate has one stable, lifecycle-bound, rotatable status token (distinct from the short-lived 72 h scheduling/reschedule tokens). The status link is delivered to the candidate inside existing candidate-facing emails (e.g. invitation/confirmation) rather than as a separate new email type. The token is stored hashed, provisioned when needed, and invalidated on erasure or rotation. The long-lived bearer-token risk is mitigated by transport controls (FR-032), rotation (FR-029), rate-limiting (FR-030), and the no-oracle responses (FR-031/FR-023); residual risk of a forwarded valid link is accepted and bounded by rotation.
- **Stage content & at-rest posture**: "Stage" and "next-step" are recruiter-authored fields (a short stage label plus a plain-English description), not a rigid system-defined pipeline taxonomy — the structured pipeline/stage model is a later feature (F51). Because these candidate-specific free-text fields may contain personal data, they are treated as PII-adjacent and **encrypted at rest following the per-candidate free-text precedent** (the F13 `locationText` model), kept out of logs (FR-033), and output-escaped on render (FR-009). The candidate page renders what the recruiter publishes, subject to the "must include an expected date and non-empty next step" rule.
- **Completion/outcome**: The completed (offer/rejection) outcome is represented by the recruiter selecting/marking a terminal status; the page renders an honest terminal message. Detailed offer/rejection workflows are out of scope.
- **Scope boundaries**: This feature does NOT include the full pipeline view (F51), SLA breach detection/nudges (F31), or auto-updating the status from booked interviews. Surfacing live booked-interview details automatically is out of scope for v1; the recruiter-maintained expected date is the source of truth. Hiring Manager status *authoring* is out of scope for the MVP (Recruiter/Admin only). The page provides a contact route but does not implement two-way messaging.
- **Reuse**: The feature reuses the existing candidate record, GDPR erasure/confirmation machinery and audit log (F04), workspace branding (F03), the candidate-facing private-link, token-hashing, rate-limiting, accessibility, transport-header (`_headers`/CSP) and localization patterns established for the scheduling page (F13/F14), and the email delivery channel (F22). No new infrastructure service is introduced.
- **Single language**: Candidate-facing copy is English for the MVP, authored with localization markers (consistent with F14), with full multi-language deferred.
- **Erasure routing**: The candidate-initiated erasure request reuses F04's confirm-before-wipe flow; an Admin must confirm before any data is deleted, consistent with the existing operator path.
