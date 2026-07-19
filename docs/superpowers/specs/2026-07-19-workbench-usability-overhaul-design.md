# Cadence Workbench Usability Overhaul — Design

**Date:** 2026-07-19 · **Status:** Approved for planning · **Owner:** UI/UX

## Context

An audit (2026-07-19) confirmed the Cadence MVP backlog (F00–F51) is **functionally
complete** — every feature has a spec, a backend controller, and a frontend surface.
The remaining gaps are not features but **UI/UX polish and consistency**:

- The design system (`frontend/src/styles.scss`, 661 lines) is strong and cohesive —
  full token set, button/card/table/badge/alert primitives, *and* `.skeleton`/`.spinner`/
  `.empty` primitives that **no component currently uses**.
- The **candidate-facing pages** (schedule, status, booking, feedback) are showcase-quality
  state machines with focus management and live-region announcements.
- The **internal recruiter/admin workbench** (~17 screens) is a patchwork: only the Dashboard
  fully realizes the design system. Most screens have bare `<h1>`s, no loading states, weak
  empty states, tables that overflow on mobile, no destructive-action confirmation, and several
  require operators to **hand-type raw UUIDs** (scheduling, email-send, requisitions).

## Goal

Bring the entire app to the Dashboard's polish level by extracting the Dashboard's patterns
into a **reusable component kit** and adopting it everywhere, then layering on the feedback,
navigation, and input-affordance improvements that make the workbench pleasant to live in.

## Scope

**In scope (no carve-outs):** all ~17 internal screens **and** the candidate-facing pages;
frontend and backend changes both permitted; every screen held to the full accessibility bar
(axe-core 0 violations; Lighthouse where user-facing).

**Explicitly removed boundaries** (decisions of record from brainstorming):
1. Candidate-facing pages are **in scope** (not exempt).
2. Backend changes of any kind are permitted where an improvement needs them.
3. Responsive card fallback applies to **every** table, not just high-traffic ones.
4. **No** testing/accessibility exemption for internal screens — full bar everywhere.
5. Nothing deferred to a "later theme" — all six phases below are in scope.

**Non-goals:** rebuilding backend features that already work; changing business logic; touching
the MVP feature set. This is a usability layer, not new product surface.

## Approach

**Shared component kit** (Approach A). A small, presentational, standalone-component kit lives in
`frontend/src/app/shared/ui/`, consumes the existing design tokens, and is adopted across every
screen. Single source of truth → consistency can't drift again, and new screens inherit it.

## Phased delivery

Each phase is independently shippable. Recommended order: 1 → 2 → 3 → 4 → 5 → 6
(Phase 5 may be pulled forward after Phase 1 to hit the worst daily friction first).

| Phase | Workstream | New building blocks | Adopted on | Backend |
|---|---|---|---|---|
| **1** | **Foundation kit** *(prereq)* | `PageHeaderComponent`, `EmptyStateComponent`, `SkeletonComponent`, `TableScrollComponent` (+ `.table-scroll` CSS) | — | none |
| **2** | **Consistency sweep** | *(adopts Phase 1)* | all ~17 internal screens: masthead, loading→empty→content states, scroll-safe tables | none |
| **3** | **Feedback & safety** | `ToastService` + `<app-toast-host>` (aria-live), `ConfirmDialogService` + focus-trapped `<app-confirm-dialog>` (CDK a11y) | action-outcome toasts everywhere; confirm-gate every destructive action | none |
| **4** | **Navigation** | `nav.config.ts` (extracted from launchpad `ALL_GROUPS`), persistent role-aware sidebar (→ mobile drawer), `BreadcrumbComponent` | every shell route; top-bar keeps brand/identity/sign-out | none |
| **5** | **Kill UUID entry** | accessible combobox pickers: `CandidatePicker`, `TemplatePicker`, `MemberPicker`, `RequisitionPicker` | scheduling, email-send, requisitions | candidate typeahead search endpoint (RBAC-scoped, paginated, PII-safe) |
| **6** | **Responsive tables** | card-fallback layout at narrow widths | every table | none |

### The Phase 1 kit (component APIs)

- **`PageHeaderComponent`** (`app-page-header`) — inputs `eyebrow?`, `heading` (required),
  `subtitle?`, `backLink?`, `backLabel?`; projects `[actions]` for right-aligned buttons.
  Wraps the `.page__head` + `.page__title-group` + `.eyebrow` masthead the Dashboard uses.
- **`EmptyStateComponent`** (`app-empty-state`) — inputs `heading` (required), `body?`;
  projects a CTA via default `<ng-content>`. Wraps the `.empty` primitive.
- **`SkeletonComponent`** (`app-skeleton`) — inputs `variant` (`table`|`cards`|`form`|`lines`),
  `rows` (default 5); `role="status"` + SR-only "Loading…"; wraps the `.skeleton` primitive.
- **`TableScrollComponent`** (`app-table-scroll`) — projects a `<table>` into a
  `.table-scroll` overflow-x container so tables never break narrow layouts.

Copy is **consumer-provided** (inputs) so each screen localizes via `$localize`; the only
in-kit string is "Loading…" (`@@ui.loading`). No new color/spacing literals — tokens only.

## Data flow

Phases 1–2, 3, 4, 6 are **frontend-only**, plugging into signals the screens already expose
(e.g. pipeline's existing `loading` signal that currently drives nothing visible). Canonical
template after adoption:

```
@if (loading())    { <app-skeleton variant="table" /> }
@else if (error()) { <div class="alert alert--danger" role="alert">…</div> }   // existing pattern
@else if (empty()) { <app-empty-state heading="…"><a class="btn btn--primary">…</a></app-empty-state> }
@else              { <app-table-scroll><table class="table">…</table></app-table-scroll> }
```

Phase 5 is the only backend touch, and only additively — a read/search endpoint with RBAC
scoping (Hiring Manager sees only assigned candidates) and no PII in logs (existing precedent).

## Accessibility & testing bar (all phases)

- Jasmine unit tests per new component/service (inputs render, content projects, ARIA correct).
- Per-screen spec updates: masthead present, skeleton shows while loading, empty-state on empty.
- **axe-core: 0 WCAG 2.2 AA violations** on every screen (using `app/testing/axe`), internal included.
- Phase 3 `ConfirmDialog` + Phase 5 pickers get explicit keyboard / focus-trap / ARIA-combobox tests.
- Phase 5 endpoint gets RBAC contract tests + a PII-log-scan (existing backend precedent).

## Risks

- **Phase 5** carries the most risk: RBAC scoping and PII-safety on the new candidate search
  endpoint. Treat its spec/plan with the F13/F04 security rigor.
- **Phase 4** changes global chrome (`AppComponent`) — verify no regression on candidate/token
  routes (the sidebar must render only on `data.shell === true` routes, like the top-bar).

## Documentation & sequencing

One design doc (this file) covers all six phases; each phase gets its **own implementation plan**
under `docs/superpowers/plans/` so each is a tractable, independently-shippable unit. Phase 1
(Foundation kit) is planned first because it unblocks Phases 2 and 6.
