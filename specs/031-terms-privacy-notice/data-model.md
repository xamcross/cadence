# Phase 1 Data Model: Terms & Conditions and Privacy Notice

This feature has **no database entities** — legal documents are first-party static content, not stored or per-workspace data. The "data model" is therefore the **content-source schema** (build-time inputs) and the **derived in-memory descriptor** the generator assembles from it. Mirrors the F61 `content/articles/<slug>/{meta.json, body.html}` pattern.

## Entity: LegalDocument (build-time content artifact)

Source location: `frontend/src/content/legal/<slug>/` where `<slug> ∈ { terms, privacy }`.

| Field | Source | Type | Rules / Validation |
|---|---|---|---|
| `slug` | `meta.json` + dir name | string | Exactly `terms` or `privacy`; MUST equal the directory name (the F61 `slug_dir_mismatch` guard). Determines the served path `/<slug>/`. |
| `type` | `meta.json` | enum | `TERMS` or `PRIVACY`. Drives the page title and the cross-link label. |
| `title` | `meta.json` | string | Non-empty. Becomes `<title>`, `<h1>`, and OG/Twitter title. |
| `description` | `meta.json` | string | Non-empty, short. Becomes `<meta name=description>` + OG description. (No ≤60-word cap — legal docs are not articles.) |
| `version` | `meta.json` | string | Monotonic published-revision identifier (e.g. `1.0`, or an ISO date). MUST be consistent with `lastUpdated` (FR-005). |
| `lastUpdated` | `meta.json` | ISO date `YYYY-MM-DD` | Valid date; rendered human-readable (FR-005). |
| `draft` | `meta.json` | boolean | `true` ⇒ render the prominent "draft pending legal review" banner (FR-018). Operator flips to `false` when counsel-final copy is supplied. |
| `bodyHtml` | `body.html` | HTML fragment | Long-form prose. Starts at `<h2>` (no `<h1>` — page owns the single h1). Passes the existing `lintBody` allow-list: no `<script>/<iframe>/<style>/<h1>`, no inline event handlers, no `javascript:`/`data:` URLs, no token/email sentinels, links only on the public allow-list. |

### Derived (in-memory, computed by the assembler — not authored)

| Field | Derivation |
|---|---|
| `canonical` | `originBase + '/' + slug + '/'` (trailing-slash served form, D4). |
| `robots` | `__CADENCE_ROBOTS__` placeholder (prod→`index,follow`, non-prod→`noindex,nofollow`, D6). |
| `ogImage` | `originBase + '/assets/og-cadence.png'` (reused). |
| `breadcrumb` | Home › Terms\|Privacy (BreadcrumbList JSON-LD). |
| `crossLinks` | Link to the other legal document + the home page (FR-006). |

### Privacy Notice required content sections (FR-003 / SC-009)

The `privacy/body.html` MUST contain a section (heading + prose) for each mandatory transparency element; the SC-009 check verifies presence:

1. Controller identity & contact (+ DPO / EU representative where appointed)
2. Categories of personal data collected (candidate and member)
3. Purposes & lawful basis per purpose (incl. legitimate-interest description; right to withdraw consent where consent is the basis)
4. Recipients / third parties (calendar + ATS integrations the operator connects)
5. International transfers + safeguard relied upon
6. Retention periods (or criteria)
7. Data-subject rights (access, rectification, erasure, restriction, portability, objection) + how to exercise / contact route
8. Right to lodge a complaint with a supervisory authority
9. Existence/absence of automated decision-making / profiling
10. For indirectly-obtained candidate data: source + categories obtained
11. Whether provision is a statutory/contractual requirement + consequences (where applicable)
12. Cookies/tracking disclosure (first-party session cookie only; handled as a section, not a banner — FR-019)

### Terms & Conditions required content (FR-004)

`terms/body.html` MUST cover: who may use the service & acceptable use; operator↔user relationship; disclaimers/limitations as applicable; a reference/link to the Privacy Notice.

## Non-entity: Privacy-Notice Link Reference (UI/content affordance)

Not stored. A labelled link to `/privacy` presented at:
- the shared **public footer** (home + non-token public pages) — alongside a `/terms` link;
- **each candidate-facing personal-data surface** (single inline link on token pages) — the planning-fixed inventory below;
- the **request-access** notice block (added to the existing 4-point summary);
- the **candidate-facing built-in email templates** (static `https://<origin>/privacy`, no token/PII).

### Planning-fixed candidate-surface inventory (FR-008 / SC-002)

| Surface | Component | Link form |
|---|---|---|
| Interest / request-access form | `request-access.component.ts` | Inline link inside the existing privacy notice (FR-009) |
| Candidate scheduling | `schedule.component.ts` | Single inline Privacy link |
| Candidate status | `candidate-status.component.ts` | Single inline Privacy link |
| Interviewer scorecard/feedback | `scorecard-page.component.ts` | Single inline Privacy link |
| Booking-manage | `booking-manage.component.ts` | Single inline Privacy link |
| Cancel confirm | `cancel-confirm.component.ts` | Single inline Privacy link |
| Confirm attendance | `confirm-attendance.component.ts` | Single inline Privacy link |

Any future surface that collects/displays candidate personal data inherits this requirement (FR-008 closure rule).

## State / lifecycle

A LegalDocument has one lifecycle attribute: `draft` (true → false when counsel-final). There is **no** version history, archival, or per-workspace variant (Out of Scope). A new published revision overwrites the single current version and bumps `version` + `lastUpdated`.
