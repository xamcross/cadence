# Feature Specification: Join / Express-Interest Request Form

**Feature Branch**: `029-join-interest-form`  
**Created**: 2026-06-23  
**Status**: Draft (revised after multi-role spec review)  
**Input**: User description: "since the users are waiting for the invitation before they can login there should be some form for them to fill in to express interest in joining the app."

## Overview

Cadence is invitation-only: a person cannot sign in until a workspace administrator has invited them. Today a prospective user who lands on the sign-in screen has no way to ask for access — they hit a dead end. This feature adds a public, no-login form where a prospective user can express interest in joining, and gives administrators a queue to review those requests and convert them into invitations.

This feature does **not** create accounts or grant access. It captures intent and routes it to the people who can issue invitations, preserving the existing invitation-only access model.

> **Scope note — "users" means prospective workspace members (the people who would log in to operate Cadence: recruiters, hiring managers, admins). It does NOT mean job candidates.** Candidates already have their own intake and data-subject flows; this feature is a separate, member-facing on-ramp. Interest-request data is therefore a **distinct personal-data category from candidate data**: the candidate consent/contact-gate and candidate erasure machinery do NOT apply to it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Prospective user expresses interest (Priority: P1)

A person who wants to use Cadence but has no invitation finds a "Request access" / "Express interest" entry point from the public sign-in screen (and the public home page), opens a short form, provides their name and email (and optionally their organization and a short message), and submits it. They immediately see a confirmation that their interest was received and that they will be contacted if access is granted.

**Why this priority**: This is the entire point of the feature and the minimum viable slice — without it, prospective users still hit a dead end. It delivers value on its own: every submission is a captured lead that an admin can act on out-of-band even before the review queue (US2) exists.

**Independent Test**: Open the public form as an unauthenticated visitor, submit valid details, and confirm (a) a success acknowledgement is shown and (b) the submission is recorded and retrievable by an administrator.

**Acceptance Scenarios**:

1. **Given** an unauthenticated visitor on the sign-in screen, **When** they select the "Request access" entry point, **Then** the interest form opens without requiring any credentials.
2. **Given** the interest form, **When** the visitor submits a valid name and email, **Then** the request is recorded with status "new" and a confirmation message is displayed.
3. **Given** the interest form, **When** the visitor submits with a missing required field, a malformed email, or a field exceeding its length limit, **Then** the form shows a clear, field-level validation message and does not record a submission.
4. **Given** a submission whose email already corresponds to an active member, a pending invitation, an existing open request, or none of these, **When** the visitor submits, **Then** the confirmation shown is byte-identical and the response timing is not distinguishable across all four cases (no account-existence oracle).
5. **Given** the interest form, **When** it is displayed, **Then** a privacy notice is visible stating the data collected (name, email, organization, message), the purpose (evaluating access requests), the lawful basis (legitimate interest), and the retention period.
6. **Given** a submitter who pastes content containing markup (e.g. `<script>`) or a spreadsheet-formula prefix (e.g. `=cmd`) into a free-text field, **When** the value is later shown in the admin view or exported, **Then** it is rendered/exported inertly (never executed or interpreted as a formula).

---

### User Story 2 - Administrator reviews requests and converts them to invitations (Priority: P2)

An administrator opens a list of interest requests for their workspace, sees each request's submitted details and current status, and can act on each one: mark it reviewed, invite the person (which hands off to the existing invitation flow with a chosen role), or dismiss it. Acting on a request updates its status so the queue reflects what has and hasn't been handled.

**Why this priority**: This turns captured interest into actual access and prevents the queue from becoming a pile of unactioned data. It depends on US1 producing submissions but is independently demonstrable once submissions exist.

**Independent Test**: As an administrator, view the request list, change a request's status, and trigger an invitation from a request; confirm the request reflects the new status and that inviting produces an invitation through the normal invitation path.

**Acceptance Scenarios**:

1. **Given** one or more submitted requests, **When** an administrator opens the requests view, **Then** each request shows the submitted name, email, organization, message, submission date, and status, with the email and organization clearly labelled as submitter-claimed and unverified.
2. **Given** a request with status "new", **When** the administrator marks it "reviewed", **Then** its status becomes "reviewed", it remains visible in the queue, and it is removed from the default "needs triage" filter.
3. **Given** a request with status "new" or "reviewed", **When** the administrator invites the person and selects a role, **Then** an invitation is issued through the existing invitation process and the request becomes "invited" and is linked to that invitation.
4. **Given** a request whose email already belongs to an active member, **When** the administrator attempts to invite from it, **Then** the administrator sees a clear "already a member" message, no second invitation or access path is created, and the request is moved to a terminal state (no error/500).
5. **Given** a request, **When** the administrator dismisses it, **Then** its status becomes "dismissed" and it no longer appears in the default queue view.
6. **Given** a request already actioned (invited or dismissed) by another administrator, **When** a second administrator tries to act on it concurrently or afterward, **Then** the action is a no-op returning a conflict signal — never a second invitation or a conflicting status (last-write-wins is not allowed).
7. **Given** a user without administrator privileges, **When** they attempt to view or act on requests, **Then** access is denied.
8. **Given** requests in another workspace, **When** an administrator views the list, **Then** they see only requests for their own workspace.

---

### User Story 3 - Administrators are alerted to new interest (Priority: P3)

When a new interest request arrives, the workspace's administrators are notified so they can respond promptly rather than having to poll the queue.

**Why this priority**: Improves responsiveness and reduces time-to-invite, but the feature is still useful without it (admins can check the queue manually). It is the lowest-priority slice.

**Independent Test**: Submit a new interest request and confirm the workspace administrators receive a value-free notification that links them to the review queue and contains no submitter personal data.

**Acceptance Scenarios**:

1. **Given** a workspace with at least one administrator, **When** a new interest request is submitted, **Then** the administrators are notified that a new request awaits review, with a link to the access-controlled queue and no submitter name/email/organization/message in the notification.
2. **Given** repeated submissions from the same person within the configured de-duplication window, **When** notifications would be generated, **Then** at most one alert is sent for that person in the window.
3. **Given** the notification is delivered to administrators (existing, verified members), **When** it is generated, **Then** it is never sent to the submitter-provided email address.

---

### Edge Cases

- **Already a member / already invited (public side)**: a submitter whose email already belongs to an active member or an outstanding invitation receives the same neutral, timing-invariant confirmation as anyone else (FR-005), and no redundant access path is created.
- **Repeat submissions while a request is open**: a second submission from an email that already has an *open* request (status new or reviewed) does not create a second request; the existing request is updated (timestamp/message) instead.
- **Resubmission after a terminal request**: if the submitter's prior request was *dismissed* or *invited*, a new submission is treated as a fresh "new" request (de-duplication keys only on open requests).
- **Spam / automated abuse**: submissions are throttled per source and an obvious-bot heuristic (e.g. honeypot/timing) is applied without a CAPTCHA for ordinary users; a per-workspace write ceiling bounds total intake per window so a distributed flood cannot grow the queue without bound.
- **Oversized or unsafe input**: name, organization, and message are length-bounded; submitted text is stored verbatim but treated as untrusted and never interpreted as markup or spreadsheet formulae when displayed or exported.
- **Invalid email**: malformed addresses are rejected at validation time. The email is never verified (the submitter is not sent a confirmation email — see Assumptions).
- **Submitter wants their data removed**: erasure is administrator-triggered from the queue (there is no public "delete my request" endpoint, which would be an enumeration oracle).
- **Acting on a stale request**: see US2 Scenario 6 (guarded, conflict-no-op).
- **No reachable administrator**: if a workspace has no administrator to notify, the request is still captured and becomes visible once an administrator exists.
- **No workspace yet / first-run**: the public form is reachable only after the deployment's default public workspace exists; before then the entry point is hidden or returns a neutral "not available yet" response (no error/oracle).
- **Invitation produced from a request later expires or is revoked**: the request remains terminal ("invited"); re-inviting that person is done through the normal members/invitation screen, not by re-opening the request (out of scope for this feature).

## Requirements *(mandatory)*

### Functional Requirements

**Submission (public)**

- **FR-001**: The system MUST provide a public, no-login form through which a prospective user can express interest in joining, reachable from the sign-in screen and the public home page; the submit endpoint MUST be a public, unauthenticated route, and the form page MUST carry no token or personal data in its URL.
- **FR-002**: The form MUST collect the prospective user's name and email (required), and MUST allow optional entry of an organization/company and a short free-text message. Each field MUST be length-bounded (name ≤ 200, organization ≤ 200, message ≤ 2000 characters); the message field is purpose-limited to "why you want access".
- **FR-003**: The system MUST validate presence of required fields, email format, and per-field length before accepting a submission, returning clear field-level feedback that never echoes back unrelated stored data.
- **FR-004**: On a successful submission, the system MUST record the request with its submitted details, a submission timestamp, and an initial status of "new", and MUST display an on-screen confirmation to the submitter.
- **FR-005**: The public submission response MUST be byte-identical in body, HTTP status, and headers whether the submitted email corresponds to (a) an active member, (b) a pending invitation, (c) an existing open request, or (d) none of these. The submit path MUST NOT perform any member- or invitation-existence check, so cases (a) and (b) are indistinguishable from (d) **by construction** (there is no branch that could leak them); the only internal branch is new-insert vs. coalesce-update of an existing open request, both of which return the identical response, with side effects (notification) deferred off the response path. The response is therefore **structurally constant-time**. (Timing is a structural guarantee, not a wall-clock-asserted one — see SC-005.) No account- or request-existence oracle may be exposed to the submitter.
- **FR-006**: The form MUST present a privacy notice stating what personal data is collected (all four fields), the purpose (evaluating access requests), the lawful basis (legitimate interest), and the retention period.

**Data protection**

- **FR-007**: Submitter name, email, organization, and message MUST be encrypted at rest, consistent with the application's existing per-field personal-data encryption for member and candidate fields.
- **FR-008**: De-duplication and any member/invitation/duplicate-existence check MUST be performed via a keyed one-way email hash (never by querying or comparing the plaintext or ciphertext email), so the lookup neither weakens encryption-at-rest nor introduces a comparison oracle.
- **FR-009**: Submitter personal data MUST NOT appear in application logs, dead-letter records, or administrator notifications. Exception/error messages MUST be reduced to a personal-data-free cause indicator before being logged, and an automated check MUST assert absence of seeded personal-data sentinels across these sinks.
- **FR-010**: All submitted free-text MUST be treated as untrusted: it MUST be stored verbatim but rendered inertly in the admin view (never interpreted as executable markup) and neutralized at the export boundary so it can never be interpreted as a spreadsheet formula.

**Review & conversion (admin)**

- **FR-011**: Administrators MUST be able to view a list of interest requests for their own workspace, including each request's name, email, organization, message, submission date, and current status; the view MUST be an access-controlled internal route restricted to administrators.
- **FR-012**: The system MUST scope every administrator's view and actions to interest requests belonging to their own workspace; requests from other workspaces MUST be neither visible nor actionable.
- **FR-013**: Administrators MUST be able to transition a request to "reviewed" or "dismissed", and MUST be able to invite the person directly from a request with a selected role. The allowed status transitions are: new → reviewed, new/reviewed → invited, new/reviewed → dismissed. "Invited" and "dismissed" are terminal and not re-actionable. "Reviewed" keeps the request in the queue but removes it from the default "needs triage" filter.
- **FR-014**: Inviting from a request MUST use the existing invitation capability, with the issuing workspace and acting administrator derived from the administrator's session (not from submitter input) and the role chosen by the administrator. On success the request MUST become "invited" and be linked to the resulting invitation.
- **FR-015**: If an administrator invites from a request whose email is already an active member (which the existing invitation capability rejects), the system MUST surface a clear administrator-facing "already a member" outcome, create no second invitation or access path, and move the request to a terminal state — without an error/500 and without leaking anything to any public surface.
- **FR-016**: Status transitions and invite-from-request MUST use an atomic, status-guarded compare-and-set so that concurrent or repeated actions on the same request are a no-op returning a conflict signal, never a duplicate invitation or a conflicting status.

**Abuse resistance**

- **FR-017**: The system MUST throttle submissions per source. The per-source key MUST be derived from the **real client IP** (the forwarded-client-IP header validated against the trusted reverse proxy), hashed one-way (no raw IP retained, even in memory); where the real client IP cannot be established, the per-source layer is **best-effort only** (it must not be the sole guard, because behind the proxy all callers can otherwise collapse to one edge IP). A hidden honeypot field plus a minimum form-fill time MUST silently reject obvious automated submissions (returning the same neutral response, no row written) without a CAPTCHA for ordinary users.
- **FR-018**: The system MUST enforce a **durable** per-workspace ceiling on accepted submissions per time window, evaluated as a database count of recent submissions before insert (not an in-memory counter), so that a distributed flood (many sources, varied emails) cannot grow the request store without bound; submissions beyond the ceiling are rejected with a throttling response. This per-workspace ceiling, together with the per-field bounds (FR-002), is the durable abuse backstop. Ordinary single submissions MUST NOT require a CAPTCHA.

**Workspace association**

- **FR-019**: Anonymous submissions MUST be associated with a single, server-configured default public workspace identifier (a documented configuration property, defaulting to the deployment's bootstrap workspace), surfaced only to that workspace's administrators. The workspace identifier MUST come from server configuration and MUST NEVER be taken from submitter input. Routing public requests to multiple workspaces is out of scope.

**Notification**

- **FR-020**: The system MUST notify the workspace's administrators when a new request is submitted, via the operational member-notification channel (the candidate consent/contact gate MUST NOT be applied — recipients are administrators, not data subjects). The notification MUST be value-free (signals a new request awaits review and links to the access-controlled queue; contains no submitter personal data) and MUST be coalesced so repeated submissions from the same person within the de-duplication window produce at most one alert.

**Lifecycle & retention**

- **FR-021**: Interest-request personal data MUST be automatically purged after a defined retention period, measured from the submission timestamp. The period follows the workspace's configured personal-data retention setting; if none is configured, a documented default (180 days) applies. Purge MUST run as a scheduled, checkpointed background task (no external broker) and MUST be backed by an index supporting the age-based query.
- **FR-022**: An administrator MUST be able to erase a submitter's interest data on request before the retention period elapses. Erasure MUST remove or wipe all four personal-data fields and drop the keyed email hash so the record is no longer discoverable by email. There MUST be no public (unauthenticated) erasure endpoint.
- **FR-023**: This feature MUST NOT, by itself, create an account or grant any access; access is only ever granted through an administrator-issued invitation.

**Accessibility**

- **FR-024**: The public form MUST meet the application's accessibility standard for public pages (WCAG 2.2 AA) and be usable on a mobile viewport. (The internal admin review screen follows the application's internal-screen bar and is not held to the public-page accessibility gate.)

### Key Entities *(include if feature involves data)*

- **Interest Request** (a.k.a. Access Request): a prospective user's expression of interest. Attributes: submitter name (encrypted), submitter email (encrypted) plus a keyed email hash for lookup/de-duplication, organization (optional, encrypted), message (optional, encrypted), submission timestamp, status (new / reviewed / invited / dismissed), owning workspace (server-resolved), link to the resulting invitation (when invited), and the administrator who last acted plus when. Subject to retention (FR-021) and administrator-triggered erasure (FR-022). Once converted, the interest PII continues to follow this retention/erasure lifecycle independently of the invitation.
- **Invitation** (existing): the administrator-issued credential that actually grants future access. An Interest Request may be converted into at most one Invitation; the Invitation entity is the separate lawful record of an access grant and is unchanged by this feature except for the optional back-link from the request.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The request form is a single page reachable in one action from both the sign-in screen and the public home page (objective). In moderated usability testing, a first-time visitor completes a valid submission within 2 minutes (usability target).
- **SC-002**: At least 99% of valid submissions are recorded and produce an on-screen confirmation.
- **SC-003**: 100% of submissions are visible to an administrator of the associated workspace in the review queue with no manual data handling.
- **SC-004**: An administrator can convert a request into an invitation in no more than two deliberate actions from the request list.
- **SC-005**: The public submission response is byte-identical in body, HTTP status, and headers across the four cases {active member, pending invitation, existing open request, unknown email} (verified by an automated 4-case contract test). Timing is structurally invariant — the four cases run the identical code path and side effects are deferred off the response path — and is documented as a structural guarantee rather than a wall-clock assertion (the no-flaky-timing rule).
- **SC-006**: Submission throttling demonstrably (a) blocks a single-source flood beyond the per-source limit and (b) blocks a distributed flood beyond the per-workspace ceiling, while allowing a normal single submission to succeed.
- **SC-007**: Duplicate submissions from the same email never create more than one *open* request (status new or reviewed); a submission after a terminal request creates exactly one new request.
- **SC-008**: Interest-request personal data is automatically purged after the retention window (verified deterministically against a controllable clock), and an administrator-triggered erasure wipes all four personal-data fields and removes email-discoverability ahead of that window.
- **SC-009**: The public form passes an automated WCAG 2.2 AA accessibility audit with zero violations, meets the 44px touch-target minimum, and renders usably on a mobile viewport.
- **SC-010**: No submitter personal data appears in application logs, dead-letter records, or administrator notifications (verified by a sentinel scan across all three sinks).
- **SC-011**: A burst of submissions from the same email within the de-duplication window generates at most one administrator notification.
- **SC-012**: A markup payload (e.g. `<script>`) and a formula payload (e.g. `=cmd|...`) submitted in free-text are inert in the admin display and neutralized in any export (verified by automated test).
- **SC-013**: Attempting to view or act on the review queue without administrator privileges, or against another workspace's requests, is denied in 100% of attempts.

## Assumptions

- **Invitation-only model preserved**: the form captures intent only; account creation and access continue to flow exclusively through an administrator-issued invitation. The feature feeds that pipeline rather than replacing it (FR-023).
- **Reviewers are administrators**: because issuing invitations and assigning roles is already an administrator-only capability, reviewing and acting on requests is scoped to administrators. Broadening review to other roles is out of scope for this version.
- **Single default public workspace**: in the current single-workspace deployment, submissions associate with a server-configured default public workspace (FR-019); the optional "organization" field lets a submitter indicate where they are from. Multi-workspace public routing is out of scope.
- **No email is sent to the submitter (deliberate security control)**: the submitter acknowledgement is on-screen only. This is an intentional control against mail amplification / "joe-jobbing" via an unverified, attacker-controlled address, and MUST NOT be relaxed (e.g. by adding a confirmation email) without a security re-review. Because the email is unverified, the admin review UI labels the submitter email and organization as claimed/unverified (US2 Scenario 1) so an administrator is not socially engineered into inviting an attacker's address under a trusted-organization pretext.
- **Retention reuses the workspace policy value**: interest-request retention uses the workspace's configured personal-data retention period where set, with a documented 180-day default otherwise — but interest data remains a *separate data category* from candidate data with its own purge task and index (FR-021); the candidate retention scan does not cover it.
- **Reuse of established security primitives**: the public submit endpoint rides the existing public, unauthenticated, CSRF-exempt request chain; the admin endpoints ride the access-controlled internal chain (so the deny-by-default endpoint inventory forces a declared admin role); de-duplication/existence checks use the existing keyed email-hash pattern; the no-oracle response uses the existing scoped, byte-identical not-found/acknowledgement handler pattern; the export uses the existing CSV-injection escaper; the rate limiter uses the existing hashed-IP advisory limiter; and the purge uses the existing scheduled-checkpoint pattern. No new runtime dependency is introduced.
- **Notification channel**: administrator notification reuses the operational member-notification mechanism (workspace-scoped, value-free), not the candidate consent-gated dispatch path.
- **SEO posture**: the public request-access page is **not indexed** (`noindex`, deny-by-default). It is a POST form with negligible organic-search value; the marketing on-ramp value comes from the public home linking to it, not from the form being a search landing page. Keeping it `noindex` preserves the existing "exactly one indexable route" deny-by-default guard unchanged. It carries no token or personal data in any URL or generated artifact regardless.

## Dependencies

- **Existing invitation capability** (administrator-issued, email + role, workspace/actor from session) — reused by FR-013/FR-014/FR-015.
- **Existing per-field personal-data encryption, keyed email-hash lookup, scoped no-oracle handler, hashed-IP rate limiter, CSV-injection escaper, scheduled-checkpoint background-task pattern, and operational member-notification channel** — reused by the data-protection, abuse-resistance, retention, and notification requirements. No external service or new dependency is required.

## Out of Scope

- Creating accounts or granting access directly from the form (only an administrator invitation grants access).
- Sending any email to the submitter-provided address (including a confirmation/acknowledgement email).
- Routing public requests to more than one workspace, or letting the submitter choose a workspace.
- A public, self-service erasure or status-lookup endpoint for the submitter.
- Re-opening an "invited" request when its invitation later expires or is revoked (handled via the normal members/invitation screen).
- Broadening request visibility beyond administrators.

## Governance Note

This feature is a member-onboarding on-ramp and is **not enumerated in the Constitution §11 MVP scope** (which is candidate-scheduling-centric). The implementation plan's Constitution Check (C1) MUST explicitly address whether this is in-scope as a supporting capability (as the SEO features F60/F61 were, also outside §11) or requires a scope amendment, before proceeding.
