# Product Specification: **Cadence**

### An Interview Orchestration & Candidate Communication Platform

**Version 1.0 · June 2026 · Product specification (pre-development)**

---

## 1. Problem Statement

This specification addresses the single largest operational time sink identified in the accompanying research report: **interview scheduling and candidate communication**.

The data behind the choice:

- Scheduling is the biggest operational burden in hiring, consuming **38% of recruiter time** (GoodTime, 2025).
- Of the 17.7 admin hours recruiters spend per vacancy, **2.5 hours go to scheduling interviews** and **3 hours to processing post-interview notes** (Totaljobs, 2025).
- Slow, opaque communication is the top driver of candidate drop-off: **28% of jobseekers abandon hiring processes**, citing slow communication (23%), excessive length (25%), and lack of clarity (21%).
- Communication breakdown fuels two-way ghosting: **61% of candidates report being ghosted** after interviews, while **76% of recruiters report being ghosted by candidates** — and 81% of hiring managers who ghost do so simply because they are "still deciding" and have no lightweight way to keep candidates informed.
- Recruiters carry ~20 open requisitions each; manual calendar coordination across candidates, hiring managers, and interview panels does not scale at that load.

The root cause is not laziness — it is that scheduling and status communication are **coordination problems spread across people who don't share a system**: the candidate, the recruiter, the hiring manager, and interview panelists all live in different calendars and inboxes. Every reschedule, no-show, or "any update?" email is manual work.

**Cadence** eliminates this coordination layer. It is a web application (with companion mobile app) that automates interview scheduling end-to-end and — critically — automates the *communication around the process* so that no candidate is ever left in silence and no recruiter ever plays calendar Tetris again.

---

## 2. Product Vision & Goals

**Vision:** Every interview schedules itself; every candidate always knows where they stand.

**Primary goals (target outcomes):**

| # | Goal | Target metric |
|---|------|---------------|
| G1 | Reduce recruiter time spent on scheduling | From 38% to under 10% of working time |
| G2 | Reduce scheduling time per vacancy | From 2.5 h to under 20 min of human involvement |
| G3 | Reduce candidate drop-off due to slow/unclear communication | −50% process abandonment attributable to communication |
| G4 | Eliminate "silent" candidates | 100% of active candidates receive a status touchpoint at least every N days (configurable, default 5) |
| G5 | Reduce interview no-shows | −40% via confirmations, reminders, and friction-free rescheduling |
| G6 | Reduce time-to-schedule | First interview slot confirmed within 24 h of screening decision in ≥80% of cases |

**Non-goals (v1):** Cadence is not an ATS, not a sourcing tool, not a résumé screening engine, and not a video-interview platform. It integrates with these; it does not replace them.

---

## 3. Target Users & Personas

**P1 — Rita, In-house Recruiter (primary user).** Carries 15–25 open requisitions. Spends her mornings sending availability emails and her afternoons untangling reschedules. Needs: bulk scheduling, automatic reminders, a single view of every candidate's "communication health."

**P2 — Marek, Hiring Manager (secondary user).** Interviews 4–6 candidates a week between his real job. Forgets to submit feedback, which silently blocks the pipeline. Needs: one-tap slot confirmation, feedback nudges, near-zero learning curve.

**P3 — Dana, Candidate (external user, no account required).** Applied three weeks ago, had one interview, has heard nothing. Needs: self-service slot picking, a live status page, and the honest "we're still deciding" message that 81% of hiring managers never send.

**P4 — Tomas, TA / HR Operations Lead (buyer & admin).** Owns recruiting KPIs and the tooling budget. Needs: configuration, SLA policies, analytics proving ROI in recruiter-hours saved.

**P5 — Agency Recruiter (secondary segment).** Same pains multiplied across multiple client calendars; needs multi-tenant client workspaces (post-MVP).

---

## 4. Core Concept & Key Flows

Cadence sits between the company's ATS and its calendar/email systems. Its unit of work is the **Interview Loop** — the full sequence of stages a candidate moves through for one requisition. Three pillars:

### Pillar A — Self-Scheduling Engine
The recruiter (or an ATS trigger) initiates a scheduling request; Cadence does the rest.

**Flow A1 — Single interview scheduling**
1. Recruiter selects candidate + interview stage template (e.g., "60-min technical, panel of 2 of 5 engineers").
2. Cadence reads real-time availability of all required and optional participants from their calendars, applies rules (working hours, time zones, buffer times, max interviews/day per interviewer, focus-time blocks, interviewer load balancing).
3. Candidate receives a branded self-scheduling link showing only genuinely free, rule-compliant slots; picks one on any device, no login.
4. Cadence books calendar events, attaches the video-call link and prep materials, and confirms to everyone.

**Flow A2 — Multi-stage / panel ("loop") scheduling.** For onsite-style loops (e.g., 4 back-to-back sessions), Cadence solves the combinatorial puzzle: contiguous or same-day slots, interviewer rotation and load-balancing, room booking where relevant, and break insertion. The candidate still sees a single simple choice: "Pick your interview day."

**Flow A3 — Reschedules & cancellations.** Either side can reschedule via one link. Cadence automatically re-runs the search, re-books, notifies all parties, and logs the change. A reschedule should cost a human under 60 seconds.

**Flow A4 — No-show defense.** Configurable confirmation cascade (e.g., confirm 24 h before via email; if unconfirmed, SMS/WhatsApp nudge 4 h before; if still unconfirmed, alert recruiter with one-tap "release slot & invite waitlisted candidate").

### Pillar B — Communication Autopilot (the anti-ghosting layer)
This is the differentiator. Cadence treats candidate communication as an SLA-managed pipeline, not an act of individual goodwill.

- **Candidate Status Page.** Every active candidate gets a private link showing their stage, what happens next, and expected timing ("Feedback expected by Thursday"). This single feature attacks the #1 candidate complaint — silence after application or interview.
- **Status SLAs & nudges.** Admin-defined rules such as "no candidate goes more than 5 days without an update." When a deadline approaches, Cadence drafts the appropriate message (update, honest hold notice, rejection) for one-click recruiter approval — or sends automatically if so configured.
- **"Still deciding" templates.** Pre-written, human-toned holding messages for exactly the situation where 81% of ghosting originates. Sending one takes a single click.
- **Feedback chasing.** After each interview, Cadence requests structured feedback from interviewers (quick scorecard form on web/mobile), escalating reminders until submitted, because missing feedback is the silent blocker behind most "still deciding" limbo. Optional: voice-note feedback transcribed and structured into the scorecard, directly attacking the 3 h/vacancy spent on post-interview notes.
- **Respectful closure.** Bulk, personalized rejection at any stage with stage-appropriate templates, so no application ever ends in a void.

### Pillar C — Visibility & Analytics
- **Pipeline Health Board:** every requisition with color-coded communication status (green = within SLA, amber = nearing breach, red = candidate in silence) and scheduling status (awaiting slots, booked, feedback pending).
- **Time-saved dashboard:** recruiter-hours saved vs. manual baseline (using the 2.5 h/vacancy and 38%-of-time benchmarks), no-show rate, time-to-schedule, candidate response times, drop-off points, feedback turnaround per interviewer.
- **Candidate experience pulse:** optional 2-question micro-survey after each stage; aggregate NPS-style score per team and per requisition.

---

## 5. Functional Requirements

### 5.1 Scheduling
- FR-1: Real-time two-way sync with Google Calendar and Microsoft 365/Outlook calendars of all internal participants.
- FR-2: Rule engine per interview template: duration, required/optional participants, panel composition rules ("any 2 of pool X"), buffers, daily/weekly interview caps per person, blackout periods, time-zone fairness for the candidate.
- FR-3: Candidate-facing scheduling pages: brandable, localized (multi-language), mobile-first, accessible (WCAG 2.2 AA), no account or app install required.
- FR-4: Multi-stage loop solver with same-day/contiguous constraints and automatic break/room insertion.
- FR-5: One-link reschedule/cancel for both sides with automatic propagation.
- FR-6: Waitlist & slot-release mechanics for no-show defense.
- FR-7: Automatic generation/attachment of meeting links (Google Meet, Teams, Zoom) and physical-location details.
- FR-8: Interviewer load balancing with fairness reporting (interviews per person per period).

### 5.2 Communication
- FR-9: Template library (invitations, confirmations, reminders, holds, rejections, offers-stage updates) with merge fields, tone presets, and per-stage variants; fully editable by admins.
- FR-10: SLA policy engine: configurable maximum silence windows per stage; breach detection; auto-draft or auto-send behavior per policy.
- FR-11: Candidate Status Page with live stage, next steps, expected dates, and contact route.
- FR-12: Multi-channel delivery: email (native), SMS and WhatsApp (via integrated messaging providers), all logged to a unified candidate timeline.
- FR-13: Structured interviewer feedback forms with reminder escalation and manager-visible compliance stats; optional voice-to-scorecard capture.
- FR-14: Bulk actions: schedule, update, or close out many candidates at once with personalization preserved.

### 5.3 Integration & Data
- FR-15: Native bi-directional integrations with major ATSs (Greenhouse, Lever, Workable, SmartRecruiters, Teamtailor; extensible connector framework). Candidate/stage data flows in; scheduling and communication events flow back as ATS activity.
- FR-16: Open REST API + webhooks so Cadence can be embedded into custom pipelines.
- FR-17: CSV import / lightweight standalone mode for SMBs with no ATS — Cadence's minimal pipeline view makes it usable on its own.
- FR-18: Full audit log of every message, booking, and change per candidate.

### 5.4 Administration
- FR-19: Role-based access (Admin, Recruiter, Hiring Manager, Interviewer, Read-only).
- FR-20: Workspace settings: branding, languages, working-hours policies, SLA defaults, template governance (locked vs. editable templates).
- FR-21: Analytics module per §4 Pillar C, exportable (CSV/PDF) for leadership reporting.

### 5.5 Mobile Companion App (iOS/Android)
- FR-22: For hiring managers/interviewers: today's interviews, one-tap confirm/decline of proposed slots, push-notified feedback forms completable in under 2 minutes, push reminders.
- FR-23: For recruiters: red-status alerts (SLA breaches, no-show risks) with one-tap remediation actions.
- Candidates intentionally need no app — everything candidate-facing is responsive web.

---

## 6. Non-Functional Requirements (product-level)

- **Privacy & compliance:** GDPR-compliant by design (EU-relevant: lawful basis tracking, configurable data-retention periods per candidate, right-to-erasure workflows, EU data residency option). Candidate consent management for SMS/WhatsApp channels.
- **Security:** SSO (SAML/OIDC), role-based permissions, encryption of personal data at rest and in transit, granular calendar scopes (free/busy only by default — Cadence never needs event contents of unrelated meetings).
- **Reliability:** Scheduling and reminders are time-critical; missed reminders are product failures. Target ≥99.9% availability for booking and notification paths.
- **Performance:** Candidate scheduling page loads in <2 s on mobile networks; slot computation for a 5-person panel in <5 s.
- **Accessibility & localization:** WCAG 2.2 AA on all candidate-facing surfaces; UI and templates localizable; correct handling of time zones, DST, and regional date formats as a first-class feature, not an afterthought.
- **Tone safety:** AI-assisted message drafting is always template-grounded and human-approvable by default; no fully generative free-form messages to candidates without opt-in.

---

## 7. Differentiation & Positioning

Scheduling links exist (Calendly et al.) and enterprise interview-scheduling tools exist (GoodTime, ModernLoop). Cadence's wedge is the **fusion of scheduling with SLA-governed candidate communication**:

1. Competitors automate the *booking*; Cadence automates the *relationship* — status pages, silence SLAs, honest-hold messaging, and feedback chasing close the loop that actually causes drop-off and ghosting.
2. **Communication health as a first-class metric.** No competitor surfaces "candidates currently in silence" as a red-flag operational dashboard. Cadence makes ghosting visible, measurable, and fixable.
3. **Feedback-to-decision acceleration.** By chasing and structuring interviewer feedback (including voice capture), Cadence attacks the hidden 3 h/vacancy of note processing and the "still deciding" limbo simultaneously.
4. **SMB-friendly standalone mode.** SMBs average 83.5 days posting-to-offer (vs. 51.7 enterprise) and are the least tooled; Cadence works without an ATS, opening a large underserved segment.

---

## 8. Monetization (outline)

Per-recruiter-seat subscription (interviewers/hiring managers free — adoption must be frictionless for them), tiered as: **Starter** (standalone mode, scheduling + reminders), **Professional** (ATS integrations, SLA engine, status pages, analytics), **Enterprise** (SSO, multi-workspace, API, data residency, advanced governance). Messaging channel costs (SMS/WhatsApp) passed through with margin. The ROI story writes itself from the report data: one recruiter saving even half the scheduling burden recovers multiples of the seat price (≈£17,000/yr admin waste per recruiter as the anchor).

---

## 9. Success Metrics (post-launch)

- Activation: ≥70% of invited hiring managers complete their first one-tap slot confirmation within 7 days.
- Engagement: ≥85% of interviews in a workspace booked via self-scheduling (vs. manually) by month 3.
- Outcome: median time-to-schedule <24 h; no-show rate −40%; feedback submitted within 48 h for ≥80% of interviews; zero candidates beyond SLA silence threshold in ≥95% of weeks.
- Experience: candidate pulse score ≥4.5/5; measurable reduction in stage-level drop-off.
- Business: net revenue retention ≥110%, driven by seat expansion as time savings are demonstrated on the dashboard.

---

## 10. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Hiring managers ignore yet another tool | Their entire experience is one-tap actions in email/push; no dashboard required of them |
| Over-automation feels robotic to candidates | Human-toned templates, recruiter-approval defaults, status pages framed as transparency rather than automation |
| Calendar data sensitivity concerns | Free/busy-only default scope, transparent permissions screen, EU residency option |
| ATS vendors bundle similar features | Move fast on the communication-SLA wedge; standalone SMB mode reduces ATS dependence |
| SLA auto-messages sent in error (e.g., post-offer) | Stage-aware guardrails; auto-send off by default for sensitive stages (offer, rejection) |
| Messaging deliverability (spam filters) | Verified sending domains per customer, channel fallbacks, delivery monitoring surfaced in UI |

---

## 11. Scope Phasing

**MVP (v1):** Flows A1, A3, A4 (single-stage scheduling, reschedule, no-show defense); Google + Microsoft calendar sync; email channel; template library; Candidate Status Page; basic SLA nudges (draft-for-approval only); feedback forms with reminders; Greenhouse + Lever integrations; standalone CSV mode; core dashboard (time-to-schedule, no-shows, silence list).

**v1.5:** Multi-stage loop solver (A2); SMS/WhatsApp; voice-to-scorecard; auto-send SLA policies; load-balancing analytics; mobile companion app.

**v2:** Agency multi-client workspaces; additional ATS connectors; candidate pulse surveys; public API; advanced forecasting (predicted time-to-fill from scheduling velocity).

---

*Prepared as a product specification accompanying the report "The Most Prominent, Time-Wasting and Costly Problems Facing HR and Recruitment Teams" (June 2026). Engineering architecture, stack selection, and deployment topology are intentionally out of scope per the brief.*
