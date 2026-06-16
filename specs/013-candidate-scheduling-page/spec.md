# Feature Specification: Candidate Scheduling Page (UX) (F14)

**Feature Branch**: `013-candidate-scheduling-page`  
**Created**: 2026-06-16  
**Status**: Draft  
**Input**: User description: "checkout main, pull the updates. find the next unimplemented task from the backlog and create specification for it. review with appropriate sub-agents"

> Backlog reference: F14 — Candidate Scheduling Page (UX) (Tier 1, P1). Spec refs: product spec §5.1 (FR-3), §6 Accessibility, constitution §IX. This feature hardens the candidate-facing slot picker that F13 (Flow A1) shipped as functional-only: F13 explicitly deferred the mobile-first performance budget (< 2 s on 4G), WCAG 2.2 AA conformance, the 44 px touch-target rule, multi-breakpoint layout, full localization sign-off, and the token-experience polish to **F14** (per F13 `spec.md` Assumptions and the F13 component's own header comment). F14 does **not** re-implement the scheduling backend (token model, slot computation, atomic reservation, calendar booking all belong to F13); it owns the candidate's experience of that flow and turns the previously *advisory* accessibility/performance/localization checks into *blocking* quality gates.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Candidate picks a slot on a phone in seconds (Priority: P1)

A candidate opens the self-scheduling link on a mid-range phone over a typical mobile connection. The page loads fast, shows only the available interview times in the candidate's own time zone with clear, readable date/time labels, presents them as comfortably tappable cards, and lets the candidate confirm a time in a couple of taps — with no login, no app install, and no horizontal scrolling.

**Why this priority**: The candidate's first (and often only) impression of the hiring company is this page, and the majority of candidates open scheduling links on mobile. A slow, cramped, or confusing page directly causes drop-off and damages the employer brand — which is the core product promise (a respectful, frictionless candidate experience). It is the headline value of F14 and independently demonstrable on a real device or mobile emulator.

**Independent Test**: Open a valid scheduling link in a mobile viewport (375 px) on a throttled connection, confirm the page becomes interactive quickly, the slot cards are readable and tappable without zoom or horizontal scroll, times display in the local zone with the offered zone labelled, and a slot can be confirmed end-to-end.

**Acceptance Scenarios**:

1. **Given** a valid, unexpired scheduling link opened on a 375 px-wide mobile viewport over a throttled (4G-class) connection, **When** the candidate loads the page, **Then** the page reaches interactive state within the performance budget and renders without horizontal scrolling.
2. **Given** the page is displayed at 375 px, 768 px, and 1280 px widths, **When** the candidate views the slot list at each width, **Then** the layout adapts to each breakpoint, slot cards remain readable, and every interactive control is at least 44 × 44 px.
3. **Given** a candidate in a different time zone than the offering workspace, **When** they view the offered times, **Then** each time is shown in the candidate's local time zone with a clear, unambiguous date/time label and a visible indication of which zone is being displayed.
4. **Given** a candidate picks an available slot, **When** they confirm, **Then** they see an immediate, clearly worded confirmation of the booked time (in their local zone) without any further action required.

---

### User Story 2 - A candidate using assistive technology can schedule unaided (Priority: P1)

A candidate who relies on a screen reader, keyboard-only navigation, or high-contrast/large-text settings can read every element, reach and operate every control in a logical order, understand state changes (loading, error, success), and complete the booking without sighted assistance.

**Why this priority**: Accessibility is a legal and ethical requirement for a candidate-facing flow (a candidate cannot be excluded from a hiring process by an inaccessible tool), and the backlog makes WCAG 2.2 AA with zero axe-core violations a hard acceptance gate for this page. It is equal in priority to the mobile experience and independently testable with automated and manual assistive-technology checks.

**Independent Test**: Run an automated accessibility audit (axe-core) against every page state (loading, slot list, success, expired, invalid, error/conflict) and confirm zero WCAG 2.2 AA violations; manually verify keyboard-only operation and screen-reader announcement of each state and the slot controls.

**Acceptance Scenarios**:

1. **Given** the slot-picker page in any of its states, **When** an automated WCAG 2.2 AA audit is run, **Then** it reports zero violations.
2. **Given** a keyboard-only user, **When** they navigate the page, **Then** focus order is logical, every interactive element is reachable and operable by keyboard, and the focused element is always visibly indicated.
3. **Given** a screen-reader user, **When** the page changes state (loads slots, shows an error, confirms a booking, reports an expired link), **Then** the change is announced and each slot control has an accessible name that conveys the full date and time.
4. **Given** any text or interactive element, **When** rendered with default styling, **Then** colour contrast meets the WCAG 2.2 AA contrast ratio and the page remains usable at 200% text zoom without loss of content or function.

---

### User Story 3 - Token-state experiences are clear and never alarming (Priority: P2)

A candidate who opens an expired, already-used, superseded, or malformed link sees a calm, helpful, plainly-worded message that tells them what to do next (contact their recruiter), never a raw error page, stack trace, or HTTP status. An expired link reads differently from an already-booked link, but a used/invalid/unknown link reads identically (no hint about whether a link ever existed). Repeated rapid attempts are gently throttled with a clear "please wait" message.

**Why this priority**: These are off-happy-path states that every candidate-link feature must handle gracefully, but they are secondary to the core pick-a-slot journey. The underlying token rules (TTL, 410 vs 400, single-use, supersede-on-resend, per-IP rate limit) are already enforced by the F13 backend; F14 owns the candidate-facing presentation of each outcome so it is humane and consistent.

**Independent Test**: Drive the page with each token state — valid, expired, used/booked, superseded, malformed/unknown, and rate-limited — and confirm each renders the correct, accessible, plain-language message with an actionable next step and no technical error surface.

**Acceptance Scenarios**:

1. **Given** an expired scheduling link, **When** the candidate opens it, **Then** they see a distinct, friendly "this link has expired — contact your recruiter" message (not a 404/500 page and not the same message as an invalid link).
2. **Given** a link whose slot was already booked, **When** the candidate reopens it, **Then** they see their existing confirmed time, not a re-booking opportunity and not an error.
3. **Given** a used, superseded, or unknown/malformed token, **When** the candidate opens the link, **Then** they see a single, indistinguishable "this link is not valid — contact your recruiter" message that does not reveal whether the token ever existed.
4. **Given** the candidate has made too many rapid requests, **When** they are rate-limited, **Then** they see a clear "too many attempts — please wait and try again" message rather than a technical error, and the page recovers normally afterwards.
5. **Given** the chosen slot was taken or became unavailable between page load and confirm, **When** the candidate confirms, **Then** they see a clear "that time was just taken — please pick another" message and the remaining valid times are presented for a fresh choice.

---

### Edge Cases

- **Slow / flaky network**: the page shows a clear loading state and, on a network failure (not a token-state response), a retryable error message rather than a blank screen or a spinner that never resolves.
- **Empty slot set**: a valid link whose offered slots are all consumed/expired shows a calm "no times are currently available — your recruiter will follow up" message rather than an empty list with no explanation.
- **Conflict consumes the last slot**: if a "that time was just taken" conflict (FR-017) leaves no remaining valid slots, the page falls through to the empty-slot message (FR-011) rather than presenting an empty picker.
- **DST boundary display**: a slot near a daylight-saving transition renders at the correct wall-clock time in the candidate's local zone with unambiguous labelling (no off-by-one-hour ambiguity).
- **Time-zone ambiguity**: the page always makes clear which time zone a time is shown in, so a candidate never books the wrong hour because they assumed a different zone.
- **Very long workspace/brand names or many slots**: layout remains usable and scroll-contained at the smallest supported width; a long slot list remains navigable on mobile and by keyboard.
- **Right-to-left / long-translation strings**: because all strings are externalized for localization, a longer translated string or an RTL locale must not break the layout or truncate critical content.
- **Deep-link with stale token**: opening a bookmarked/forwarded link after the token has changed state shows the correct current state on load, not a cached stale view.
- **Reduced-motion preference**: any animation or transition respects the user's reduced-motion setting.

## Requirements *(mandatory)*

### Functional Requirements

**Performance & responsive layout**
- **FR-001**: The candidate scheduling page MUST meet the mobile performance budget: a Lighthouse Performance score ≥ 85 under the mobile simulation preset, and a Largest Contentful Paint within 2 seconds measured under that same mobile preset's 4G throttle profile — so the "loads in under 2 s on 4G" target is reproducible by the CI harness rather than an unmeasured aspiration.
- **FR-002**: The page MUST render correctly and without horizontal scrolling at 375 px, 768 px, and 1280 px viewport widths, adapting its layout to each breakpoint.
- **FR-003**: Every interactive control (slot cards, buttons, links) MUST present a touch target of at least 44 × 44 px on touch devices.
- **FR-004**: The page MUST remain usable and content-complete at 200% text zoom and MUST respect the user's reduced-motion preference for any transition or animation.

**Accessibility (WCAG 2.2 AA)**
- **FR-005**: Every state of the page (loading, slot list, booking success, expired, invalid, rate-limited, conflict/error, empty) MUST conform to WCAG 2.2 AA and MUST produce zero violations under an automated accessibility audit (axe-core).
- **FR-006**: All interactive elements MUST be reachable and operable by keyboard alone, in a logical focus order, with a visible focus indicator at all times.
- **FR-007**: Each slot control MUST expose an accessible name that conveys the full date and time of the slot; dynamic state changes (slots loaded, error shown, booking confirmed, link expired) MUST be programmatically announced to assistive technology.
- **FR-008**: Text and meaningful non-text elements MUST meet WCAG 2.2 AA colour-contrast ratios; information MUST NOT be conveyed by colour alone.

**Time-zone and slot presentation**
- **FR-009**: Offered interview times MUST be displayed in the candidate's local time zone, with a clear, DST-correct date/time label and a visible indication of the time zone being shown.
- **FR-010**: The page MUST display interview times only and MUST NOT reveal the identities (names, emails) of internal participants or any internal identifiers.
- **FR-011**: When no slots are available on a valid link, the page MUST show a clear, calm explanatory message with a next step (recruiter will follow up) rather than an empty or broken list.

**Localization**
- **FR-012**: All user-facing strings on the page MUST be externalized for localization (no hard-coded display text), and the layout MUST tolerate longer translated strings and right-to-left text without breaking or truncating critical content. (The MVP ships a single language — English — per F03's deferral of multi-language; F14 establishes the localization-ready surface so future languages need no code change.)

**Token-state presentation** (presentation only — the underlying token rules are enforced by the F13 backend)
- **FR-013**: An expired link MUST present a distinct, friendly "expired — contact your recruiter" message that is visually and textually different from the invalid-link message and is never a raw error page.
- **FR-014**: A used, superseded, unknown, or malformed link MUST present a single indistinguishable "not valid — contact your recruiter" message that does not reveal whether the token ever existed.
- **FR-015**: Reopening a link whose slot is already booked MUST display the candidate's existing confirmed interview time (in their local zone) rather than a re-booking opportunity or an error. This booked view is reachable only via the legitimate single-use token and shows only that token's own booking — it is not an enumeration or existence oracle (the indistinguishability requirement of FR-014 governs used/invalid/unknown/superseded tokens; a genuinely booked link is allowed to read differently, consistent with the F13 token contract).
- **FR-016**: When the candidate is rate-limited, the page MUST present a clear "too many attempts — please wait" message (not a technical error) and recover normally once the limit window passes.
- **FR-017**: When a chosen slot is taken or no longer available at confirmation, the page MUST show a clear "that time was just taken — please pick another" message and re-present the remaining valid slots for a new selection.

**WCAG 2.2 AA criteria not detectable by automated tooling** (explicit so the axe-core gate does not give false confidence — axe cannot verify most WCAG 2.2 additions)
- **FR-020**: Interactive controls MUST meet WCAG 2.2 **Target Size (2.5.8, minimum 24 px)**; FR-003's 44 px touch target satisfies and exceeds this on touch devices.
- **FR-021**: The currently focused element MUST NOT be hidden by other content such as a sticky header/footer or a fixed confirm bar (WCAG 2.2 **Focus Not Obscured, 2.4.11**) — critical on a small scrolling slot list — and the focus indicator MUST meet a clearly perceivable appearance (WCAG 2.2 **Focus Appearance, 2.4.13**).
- **FR-022**: The page MUST NOT introduce any cognitive test, puzzle, or CAPTCHA as a condition of access (WCAG 2.2 **Accessible Authentication, 3.3.8**); the link token remains the sole authentication and the rate-limit path (FR-016) MUST NOT add such a challenge.
- **FR-023**: The "contact your recruiter" guidance and any repeated help/recovery affordance MUST appear in a consistent location and wording across states (WCAG 2.2 **Consistent Help, 3.2.6**).
- **FR-024**: On every state transition (slots loaded, booking confirmed, conflict/error shown, link expired/invalid), focus MUST be programmatically moved to the new primary heading or message so keyboard and screen-reader users are not stranded; status changes MUST use an appropriate live-region politeness (errors/conflicts announced assertively, informational changes politely), and the loading state MUST NOT rely on a continuously animating indicator when reduced motion is requested.

**Privacy & access (inherited, must not regress)**
- **FR-018**: The candidate page MUST remain reachable with no login, account creation, or app install, authenticating solely via the link token.
- **FR-019**: The link URL MUST contain no personal data or internal identifiers beyond the opaque token, and no candidate personal data may be exposed beyond what the candidate needs to confirm their own booking, nor written to any client- or server-side log (including never interpolating the token into rendered text, ARIA labels, or announcements).
- **FR-025**: The page MUST NOT leak the link token to any third party — there MUST be no third-party scripts, analytics, fonts, or assets that receive the page URL, and the page MUST suppress the referrer (no token in any outbound `Referer`) so the bearer token in the URL is never disclosed off-origin.
- **FR-026**: Token view/confirm responses MUST NOT be cached or persisted client-side (no browser/back-forward cache restoring a bookable picker for a consumed token, no token or slot state written to local/session storage); reopening or navigating back to a link MUST resolve its current state freshly, never a stale cached view. This preserves the F13 no-store posture and MUST NOT regress it.

### Key Entities *(include if feature involves data)*

F14 introduces no new persisted data. It consumes the F13 scheduling read/confirm contract:

- **Offered Slot (view)**: a single proposed interview time the candidate can pick — start instant and candidate-safe label data only (no participant identities). Rendered in the candidate's local time zone.
- **Scheduling-link view state**: the current outcome of resolving the token — one of: open (slots available), booked (already confirmed, shows existing time), empty (no slots), expired, invalid, or rate-limited — each mapped to a distinct candidate-facing presentation.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The candidate scheduling page scores ≥ 85 on Lighthouse Performance under the mobile simulation preset in CI, with Largest Contentful Paint ≤ 2 s under that preset's 4G throttle profile; both are blocking gates (a regression below either fails the build).
- **SC-002**: An automated WCAG 2.2 AA audit (axe-core) reports zero violations across each enumerated page state — loading, slot list, booking success, expired, invalid, rate-limited, conflict/error, and empty — and this is a blocking gate.
- **SC-002a**: The WCAG 2.2 AA criteria that automated tooling cannot detect (Target Size 2.5.8, Focus Not Obscured 2.4.11, Focus Appearance 2.4.13, Accessible Authentication 3.3.8, Consistent Help 3.2.6, plus correct focus management on state transitions) are each verified by an explicit test or documented manual audit step; none is left to the axe-core gate alone.
- **SC-003**: A candidate can open a valid link and complete a booking in under 2 minutes on a mobile device with no login (a manual/UX target verified on a representative device, not a CI-blocking gate).
- **SC-004**: The page renders without horizontal scrolling and with all touch targets ≥ 44 px at 375 px, 768 px, and 1280 px widths, verified at each breakpoint.
- **SC-005**: A keyboard-only user can complete the full slot-pick-and-confirm journey without a pointing device, and every interactive element shows a visible focus indicator.
- **SC-006**: A slot near a DST transition renders at the correct local wall-clock time with unambiguous time-zone labelling in 100% of DST-boundary cases.
- **SC-007**: 100% of user-facing strings are externalized for localization (verified by a check that finds no hard-coded display text), and the layout passes a long-string/RTL pseudo-localization check without truncation or overflow.
- **SC-008**: Each token state (valid, expired, used/booked, superseded, unknown, rate-limited, conflict) renders its correct — distinct where required, indistinguishable where required — plain-language message with an actionable next step, and never a raw error, stack trace, or HTTP status, in 100% of cases.
- **SC-009**: Zero occurrences of candidate personal data or token values in any client- or server-side log produced by loading and using the page (verified by log scan).
- **SC-010**: The page leaks the link token to no third party and caches no token state client-side: no third-party asset receives the page URL, the referrer is suppressed, and a back/forward-cache or repeat navigation never re-presents a bookable picker for a consumed/expired token — verified by inspecting outbound requests and the consumed-then-reopened flow.

## Assumptions

- **F13 backend is complete and reused**: the scheduling token model, slot computation, atomic reservation, calendar booking, contactability checks, status codes (410 expired / 400 invalid / 429 rate-limited / 409 conflict), and the candidate view/confirm endpoints are owned by F13 and are not re-implemented here. F14 is a frontend/UX feature that consumes the existing contract; any backend change is limited to what the candidate page legitimately needs to render its experience (e.g., a candidate-safe field), with no change to the token-security or reservation invariants.
- **Single language (English) for the MVP**: per F03's deferral of multi-language, F14 ships English only but establishes a fully localization-ready surface (all strings externalized) so future languages need no code change. Per-workspace default-language selection and additional candidate-facing locales remain deferred.
- **Mobile-first is the design baseline**: the page is designed for a 375 px mobile viewport first and enhanced upward to tablet/desktop; the performance budget is measured under the mobile simulation preset.
- **No new infrastructure or dependencies**: F14 uses the existing Angular standalone-component + Angular Material/CDK frontend stack, Angular i18n / `$localize`, and the existing CI Lighthouse and accessibility tooling; no new runtime dependency, broker, or service is introduced (constitution C2/C4).
- **Token TTL and rate-limit values are inherited** from F13 / the backlog F14 token requirements (default 72-hour TTL, 10 requests/minute/IP, 410/400/429 semantics); F14 does not redefine them, it presents their outcomes. Traceability note: the backlog lists the "Token & expiry requirements" block under the F14 entry; F13 implemented the *enforcement* early, and F14 here formally owns the *candidate-facing presentation* of those outcomes — so a stakeholder cross-checking the backlog finds the F14 requirements satisfied, not missing.
- **Recruiter-side surfaces stay in F13/F51**: the recruiter pipeline scheduling-status display, the post-confirmation calendar event creation, and confirmation-email dispatch are owned by F13; the multi-candidate pipeline board is F51. F14 is strictly the candidate-facing page.
- **Any backend touch is additive and gated**: should the candidate page need a candidate-safe display field not already in the F13 read contract, that is a small additive read-contract extension requiring the F13 owner's sign-off — it must not alter the token-security or reservation invariants.
- **WCAG 2.2 AA and Lighthouse ≥ 85 become blocking CI gates for this candidate-facing route**, upgrading the advisory checks F13 shipped; internal recruiter screens remain out of scope for these candidate-facing gates (per the existing DoD carve-out for internal screens).
- **No reschedule/cancel UX here**: Flow A3 (F20) candidate reschedule/cancel pages are out of scope; F14 covers the initial single-stage slot-pick experience and must not preclude F20.
- **Verification harness**: performance and accessibility are verified against the existing test/CI harness (Lighthouse CI mobile preset and axe-core) for reproducibility, not against live production calendars.
</content>
