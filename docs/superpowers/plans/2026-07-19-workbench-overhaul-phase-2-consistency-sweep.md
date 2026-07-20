# Workbench Overhaul — Phase 2: Consistency Sweep — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adopt the Phase 1 kit (`app-page-header`, `app-empty-state`, `app-skeleton`, `app-table-scroll`) across all 17 internal screens so every screen has the Dashboard's masthead, guided empty states, loading placeholders, and scroll-safe tables.

**Architecture:** Per-screen adoption of one shared pattern (below). The masthead is universal (all 17 screens). Table-scroll wraps only the 6 screens that already render `<table class="table">`. Empty-state replaces/introduces empty messaging on the 10 empty-able list screens. Skeleton binds to a loading signal — 2 screens already have one; the rest get a small `loading` signal added. Frontend-only; no business-logic, service, or error-shape changes.

**Tech Stack:** Angular 17.3 standalone components, TypeScript 5.4, Jasmine + Angular TestBed, axe-core via `frontend/src/testing/axe.ts`. Tests run on EdgeHeadless (auto-resolved by `frontend/karma.conf.js`).

## Global Constraints

- **Standalone components only.** Import kit components and add them to the screen's `imports: [...]`.
- **Kit import depth:** `features/<x>/…` → `../../shared/ui/<name>.component`; `features/admin/<x>/…` and `features/admin/gdpr/…` → `../../../shared/ui/<name>.component`.
- **Presentational adoption only.** Do NOT change business logic, services, fetch semantics, or error-signal shapes. The ONLY logic change permitted is adding a `loading` signal (per the pattern) where a screen has none. Do NOT convert `<ul>`/`<ol>`/`<div>`-row lists into `<table>`s — `app-table-scroll` applies ONLY where a `<table class="table">` already exists.
- **i18n — every user-facing string localized.** Reuse the screen's existing `<h1>` translation id for `i18n-heading` (do not mint a new one for the heading); add new ids `@@<screen>.eyebrow`, `@@<screen>.subtitle`, `@@<screen>.empty.heading`, `@@<screen>.empty.body`, `@@<screen>.empty.cta`, `@@<screen>.tableLabel` as needed. In attribute values write `&amp;` for ampersands (or spell "and").
- **Tokens/classes only; no new global CSS** (the kit already ships it).
- **Testing:** run from `frontend/` with `ng test --watch=false --include='**/<spec-file>'`. Every touched screen's spec asserts **axe-core 0 violations** and that `app-page-header` renders. Screens **with** an existing spec: extend it. Screens **without** a spec (members, workspace-setup-wizard, candidate-erasure-action, candidate-audit, erasure-queue, retention-review): create a minimal spec using the Minimal-Spec Pattern below.
- **Git:** stage only the files each task touched with an explicit `git add <paths>` — **never `git add -A`** (the tree carries unrelated uncommitted SEO edits: `CLAUDE.md`, `frontend/src/environments/environment.prod.ts` — leave them). Commit per screen; append the CLAUDE.md trailer convention (Co-Authored-By + Claude-Session). Do NOT push.

---

## The Adoption Pattern (canonical — every task instantiates this)

**Step A — Imports.** Add to the component's TS imports and its `@Component imports: [...]` array only the kit pieces that screen uses. Add `RouterLink` if an empty-state CTA or back-link routes. Add `signal` to the `@angular/core` import if the screen gains a `loading` signal.

**Step B — Masthead (ALL screens).** Replace the current heading (`<h1 i18n="@@X.title">Heading</h1>`, plus any surrounding bare `<header>`/title wrapper) with:

```html
<app-page-header
  eyebrow="Section" i18n-eyebrow="@@X.eyebrow"
  heading="Heading" i18n-heading="@@X.title"
  subtitle="One line." i18n-subtitle="@@X.subtitle">
  <!-- Move any pre-existing header-level action buttons here, adding the `actions` attribute: -->
  <button actions type="button" class="btn ...">…</button>
</app-page-header>
```

- Reuse the existing heading id verbatim for `i18n-heading`.
- Omit `eyebrow`/`i18n-eyebrow` where the row says "(no eyebrow)".
- For a drill-down screen, add `backLink="/route" backLabel="…" i18n-backLabel="@@X.back"` and delete the screen's hand-rolled back-link.

**Step C — Table-scroll (only rows marked table=YES).** Wrap the existing table:

```html
<app-table-scroll ariaLabel="Label" i18n-ariaLabel="@@X.tableLabel">
  <table class="table"> … unchanged … </table>
</app-table-scroll>
```

**Step D — Empty-state (only rows with empty copy).** Replace the current empty text/branch:

```html
<app-empty-state
  heading="Heading" i18n-heading="@@X.empty.heading"
  body="Body sentence." i18n-body="@@X.empty.body">
  <a class="btn btn--primary" routerLink="/route" i18n="@@X.empty.cta">CTA</a>
</app-empty-state>
```

Drop the `<a>` when the row says "no CTA".

**Step E — Skeleton + loading (only rows marked skeleton=YES).**

- If the screen already exposes a loading signal (`loading`, or `loaded` where noted): branch the content:
  ```html
  @if (loading()) { <app-skeleton variant="table" /> }
  @else if (<empty condition>) { <app-empty-state … /> }
  @else { <content> }
  ```
- If it has none: add `readonly loading = signal(true);` (initial `true` — the screen fetches on init), and set `this.loading.set(false)` in the fetch's `next`/`error`/`finalize`. Then add the branch above. Use the `variant` named in the row.

**Minimal-Spec Pattern (for the 6 screens with no spec).** Create `<screen>.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { <ScreenComponent> } from './<screen>.component';
import { attachToBody, axeViolations, detachFromBody } from '<rel>/testing/axe';

describe('<ScreenComponent> (phase 2 adoption)', () => {
  let fixture: ComponentFixture<<ScreenComponent>>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [<ScreenComponent>],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    fixture = TestBed.createComponent(<ScreenComponent>);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });

  afterEach(() => detachFromBody(el));

  it('renders the shared page-header masthead', () => {
    expect(el.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
```

`<rel>/testing/axe` depth: `features/admin/gdpr/` → `../../../../testing/axe`; `features/admin/<x>/` → `../../../../testing/axe`; `features/<x>/` → `../../../testing/axe`. (The pending HTTP call from `provideHttpClientTesting()` never resolves, so the component sits in its loading/empty state — exactly what we assert.)

**Extend-existing-spec (for the 11 screens with a spec).** Add two `it`s to the existing describe: masthead present (`app-page-header .page__head h1` non-null), and — if the screen is empty-able — force the empty list state and assert `app-empty-state` renders. Keep/verify the existing axe assertion (add one if absent).

---

## Per-screen parameters

Section names match the launchpad groups. "id" = reuse existing heading i18n id.

| # | Screen (file) | eyebrow | subtitle | table-scroll (ariaLabel) | empty-state (heading / body / CTA→route) | skeleton (variant / loading source) | spec |
|---|---|---|---|---|---|---|---|
| 1 | admin/members/members.component.ts | Administration | Invite teammates and manage roles. | YES (Workspace members) | ADD: No members yet / Invite teammates to get started. / no CTA | table / **add** `loading` signal | NEW |
| 2 | admin/workspace/workspace-setup-wizard.component.ts | (no eyebrow) | A few details to get your workspace ready. | no | no | no | NEW |
| 3 | admin/workspace/workspace-settings.component.ts | Administration | Branding, time zone, retention, and SLAs. | no | no | form / gate on `config() === null` | EXISTING |
| 4 | admin/gdpr/candidate-erasure-action.component.ts | Data &amp; privacy | Lawful basis, withdrawal, and erasure. | no | no | no | NEW |
| 5 | admin/gdpr/candidate-audit.component.ts | Data &amp; privacy | Per-candidate access and change history. | YES (Audit entries) | swap: No audit entries / This candidate has no recorded activity yet. / no CTA | table / **add** `loading` signal | NEW |
| 6 | admin/gdpr/erasure-queue.component.ts | Data &amp; privacy | Pending right-to-be-forgotten queue. | no | swap: Queue is empty / There are no pending erasure requests right now. / no CTA | lines / **add** `loading` signal | NEW |
| 7 | admin/gdpr/retention-review.component.ts | Data &amp; privacy | Review and confirm scheduled deletions. | no | swap: Nothing flagged / No candidate records are currently over the retention period. / no CTA | lines / **add** `loading` signal | NEW |
| 8 | interview-templates/interview-templates.component.ts | Templates | Panels, durations, and slot rules. | no | swap: No templates yet / Create your first interview template using the form below. / no CTA | lines / **add** `loading` signal | EXISTING |
| 9 | email-templates/email-templates.component.ts | Templates | Candidate message content and tone. | no | swap: No templates yet / Templates initialize automatically with your workspace. / no CTA | lines / **add** `loading` signal | EXISTING |
| 10 | calendar/calendar-connections.component.ts | Personal | Connect Google or Microsoft for availability. | no | no (fixed list) | no | EXISTING |
| 11 | admin/interest-requests/interest-requests.component.ts | Administration | Review, invite, or dismiss requests to join your workspace. | YES (Access requests) | swap: No requests to review / There are no access requests matching this filter. / CTA "Show all requests" → button that sets the filter to all | table / existing `loading` signal | EXISTING |
| 12 | admin/ats/ats-integration.component.ts | Administration | Connect Greenhouse or Lever and sync status. | no | no (fixed list) | no | EXISTING |
| 13 | admin/csv-import/csv-import.component.ts | Your work | Bulk-add candidates from a CSV file. | YES (Import results) — wrap the conditional `rowResults` table | no (upload-first) | no | EXISTING |
| 14 | scheduling/scheduling.component.ts | Your work | Invite a candidate to self-schedule an interview. | no | no | no | EXISTING |
| 15 | pipeline/pipeline-list.component.ts (+ .html) | Your work | Your working candidate list across every stage. | YES (Candidates) | swap `emptyMsg`: No matching candidates / Try widening your filters, or import candidates to get started. / CTA "Import candidates" → /admin/csv-import | table / existing `loading` signal | EXISTING |
| 16 | pipeline/candidate-timeline.component.ts | Your work (+ `backLink="/pipeline"` `backLabel="Back to pipeline"`, replacing the manual `<a>`) | Chronological activity for this candidate. | no | swap: No activity yet / Nothing has happened for this candidate yet. / no CTA | lines / gate on `!loaded()` | EXISTING |
| 17 | admin/requisitions/requisitions.component.ts | Administration | Open roles and hiring-manager assignment. | YES (Requisitions) | ADD: No requisitions yet / Create your first requisition using the form below. / no CTA | table / **add** `loading` signal | EXISTING |

---

## Tasks

Each task = one screen. Per task: (1) update/create the spec (Extend-existing or Minimal-Spec) with the new assertions and run it to see it FAIL; (2) apply Pattern steps A–E per the screen's row; (3) run the spec to PASS; (4) commit. Run each spec scoped: `ng test --watch=false --include='**/<screen>.component.spec.ts'`. Commit message: `feat(ui): adopt kit on <screen> (workbench overhaul phase 2)`.

### Batch 1 — Pipeline area
- [ ] **Task 1 — pipeline/pipeline-list** (row 15). Markup lives in the separate `pipeline-list.component.html`; import kit in the `.ts`. Apply A,B,C,D,E. Extend existing spec.
- [ ] **Task 2 — pipeline/candidate-timeline** (row 16). Apply A,B(+backLink),D,E. Delete the manual `<a routerLink="/pipeline">`. Extend existing spec (assert masthead + that no stray duplicate back-link remains).
- [ ] **Task 3 — admin/requisitions** (row 17). Apply A,B,C,D(ADD),E(add loading). Extend existing spec.

### Batch 2 — Templates, scheduling, calendar
- [ ] **Task 4 — interview-templates** (row 8). Apply A,B,D,E(add loading). Extend existing spec.
- [ ] **Task 5 — email-templates** (row 9). Apply A,B,D,E(add loading). Extend existing spec.
- [ ] **Task 6 — scheduling** (row 14). Apply A,B only. Extend existing spec.
- [ ] **Task 7 — calendar/calendar-connections** (row 10). Apply A,B only. Extend existing spec.

### Batch 3 — GDPR
- [ ] **Task 8 — admin/gdpr/candidate-erasure-action** (row 4). Apply A,B only. Create Minimal-Spec.
- [ ] **Task 9 — admin/gdpr/candidate-audit** (row 5). Apply A,B,C,D,E(add loading). Create Minimal-Spec (+ empty-state assertion).
- [ ] **Task 10 — admin/gdpr/erasure-queue** (row 6). Apply A,B,D,E(add loading). Create Minimal-Spec.
- [ ] **Task 11 — admin/gdpr/retention-review** (row 7). Apply A,B,D,E(add loading). Create Minimal-Spec.

### Batch 4 — Members & workspace
- [ ] **Task 12 — admin/members** (row 1). Apply A,B,C,D(ADD),E(add loading). Create Minimal-Spec (+ empty-state assertion).
- [ ] **Task 13 — admin/workspace/workspace-settings** (row 3). Apply A,B,E(config===null gate). Extend existing spec.
- [ ] **Task 14 — admin/workspace/workspace-setup-wizard** (row 2). Apply A,B(no eyebrow) only. Create Minimal-Spec.

### Batch 5 — Admin integrations & queue
- [ ] **Task 15 — admin/interest-requests** (row 11). Apply A,B,C,D,E(existing loading). Extend existing spec.
- [ ] **Task 16 — admin/ats/ats-integration** (row 12). Apply A,B only. Extend existing spec.
- [ ] **Task 17 — admin/csv-import** (row 13). Apply A,B,C(conditional table). Extend existing spec.

---

## Verification (after all tasks)

- [ ] From `frontend/`: `ng test --watch=false` — full suite green (Phase 1's 362 + the new/extended Phase 2 assertions).
- [ ] `git status --short` — only the 4 new spec files, the touched screen files committed; the 2 unrelated SEO files still unstaged and unchanged.
- [ ] Spot-manual (optional): each screen shows the masthead; the table screens don't overflow at 375px; empty lists show the guided empty-state.

## Self-Review (completed at authoring time)

- **Spec coverage:** Phase 2 of the design doc = adopt the kit on all internal screens. The 17 tasks cover exactly the 17 internal screens catalogued; the Dashboard (already conformant) and the `/app` launchpad (nav, Phase 4) are intentionally excluded. Masthead=all 17; table-scroll=rows 1,5,11,13,15,17 (the 6 real-table screens); empty-state=rows 1,5,6,7,8,9,11,15,16,17; skeleton=rows 1,3,5,6,7,8,9,11,15,16,17 (+ gates on 3,16).
- **Placeholder scan:** the Adoption Pattern and Minimal/Extend spec patterns carry the exact code once (DRY); each task names its file, the pattern steps that apply, and its exact parameter row. `<rel>`/`<X>`/`<screen>` are genuine per-file substitutions (import depth, i18n id, class name) the implementer resolves by reading the file — not vague directives.
- **Consistency:** kit selectors/inputs match Phase 1 (`app-page-header` eyebrow/heading/subtitle/backLink/backLabel/[actions]; `app-empty-state` heading/body + CTA slot; `app-skeleton` variant/rows; `app-table-scroll` ariaLabel). i18n rule (reuse existing heading id) applied uniformly. Import-depth and axe-helper-depth rules verified against the directory tree.
- **Scope guard:** no list→table rewrites, no error-shape changes, no nav wiring (interest-requests/setup-wizard nav membership is Phase 4), no service/logic changes beyond the additive `loading` signal.
