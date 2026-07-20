# Workbench Overhaul — Phase 6: Responsive Tables — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** At narrow widths, render workbench tables as stacked label/value cards (instead of only horizontal-scroll), and polish the mobile nav "Menu" toggle so it reads clearly as a control.

**Architecture:** A single global `.table--stack` responsive rule in `styles.scss` — below 40rem it turns a `<table class="table">` into stacked per-row cards, each cell showing its column name from a `data-label` attribute via `::before`. Each of the 6 real-table screens adds the `table--stack` class and `data-label` on its `<td>`s. Above 40rem tables are unchanged (still inside the Phase-1 `app-table-scroll`). Plus a small `SideNavComponent` toggle restyle (hamburger glyph + outline). Frontend-only; the responsive behavior is CSS media-query driven.

**Tech Stack:** Angular 17.3 standalone, SCSS, Jasmine + TestBed (EdgeHeadless), axe via `frontend/src/testing/axe.ts`.

## Global Constraints
- Design tokens only; the responsive rule is added ONCE to `frontend/src/styles.scss` (no per-component CSS duplication).
- `data-label` values must match the column's `<th>` text (read each table). Keep tables wrapped in `app-table-scroll` (Phase 1) — `table--stack` is added to the inner `<table class="table">`.
- The card layout activates only `@media (max-width: 40rem)`; at test/desktop width tables render normally, so existing specs + axe stay valid. New assertions check `data-label` attributes + the `table--stack` class (width-independent).
- i18n: no new user-facing strings except the nav toggle keeps its existing `@@nav.toggle` label; the hamburger glyph is `aria-hidden`.
- **Testing** from `frontend/`: `ng test --watch=false --include='**/<spec>'`. Keep axe assertions.
- **Git:** stage only each task's files (never `-A`; leave `CLAUDE.md`/`environment.prod.ts`); commit per task; trailer convention; no push.

## File Structure
- Modify `frontend/src/styles.scss` — add the `.table--stack` responsive block.
- Modify `frontend/src/app/features/shell/side-nav.component.ts` (+ spec if needed) — toggle polish.
- Modify the 6 table screens (add `table--stack` + `data-label`): `pipeline/pipeline-list.component.html`, `admin/requisitions/requisitions.component.ts`, `admin/members/members.component.ts`, `admin/gdpr/candidate-audit.component.ts`, `admin/interest-requests/interest-requests.component.ts`, `admin/csv-import/csv-import.component.ts`.

---

## Task 1: Foundation — `.table--stack` CSS + nav toggle polish

**Files:** Modify `frontend/src/styles.scss`; Modify `frontend/src/app/features/shell/side-nav.component.ts` (+ `.spec.ts` if it asserts toggle text).

- [ ] **Step 1: Add the responsive rule to `styles.scss`** — after the `.table-scroll` block (added in Phase 1, near line ~527):

```scss
/* Responsive card fallback (Phase 6): below 40rem a .table--stack renders each row as a label/value
   card. Each <td> carries data-label="<column>"; the column name shows via ::before. Above 40rem the
   table is unchanged (still inside app-table-scroll). */
@media (max-width: 40rem) {
  .table--stack thead { border: 0; clip: rect(0 0 0 0); height: 1px; overflow: hidden; position: absolute; width: 1px; }
  .table--stack, .table--stack tbody, .table--stack tr, .table--stack td { display: block; width: 100%; }
  .table--stack tr {
    border: 1px solid var(--line); border-radius: var(--radius-sm);
    margin-bottom: var(--space-3); padding: var(--space-2) var(--space-3); background: var(--surface-raised);
  }
  .table--stack td {
    display: flex; justify-content: space-between; gap: var(--space-4); text-align: end;
    padding: var(--space-2) 0; border: 0; border-bottom: 1px solid var(--line);
  }
  .table--stack tr td:last-child { border-bottom: 0; }
  .table--stack td::before {
    content: attr(data-label); font-weight: 600; color: var(--ink-muted); text-align: start; padding-inline-end: var(--space-3);
  }
}
```

- [ ] **Step 2: Polish the nav toggle** — in `side-nav.component.ts`, change the toggle button to an outline style with a hamburger glyph (keep `aria-expanded`/`aria-controls` and the `@@nav.toggle` label):
```html
<button type="button" class="side-nav__toggle btn btn--outline" (click)="toggle()"
        [attr.aria-expanded]="open()" aria-controls="primary-nav">
  <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
    <line x1="2.5" y1="4" x2="13.5" y2="4"/><line x1="2.5" y1="8" x2="13.5" y2="8"/><line x1="2.5" y1="12" x2="13.5" y2="12"/>
  </svg>
  <span i18n="@@nav.toggle">Menu</span>
</button>
```
Update the toggle style so the glyph + label sit inline with a small gap: change `.side-nav__toggle { width: 100%; justify-content: center; margin-bottom: var(--space-3); }` to also include `gap: var(--space-2);` (the `.btn` base already flexes).

- [ ] **Step 3: Run the side-nav spec** — `ng test --watch=false --include='**/side-nav.component.spec.ts'`. The existing toggle test asserts `.side-nav__toggle` + `aria-expanded` — still passes (the button + attrs remain). If it asserted exact `textContent === 'Menu'`, adjust to `.toContain('Menu')` (the glyph adds no text). Keep axe green.

- [ ] **Step 4: Commit**
```bash
git add frontend/src/styles.scss frontend/src/app/features/shell/side-nav.component.ts
# add side-nav.component.spec.ts only if modified
git commit -m "feat(ui): responsive .table--stack rule + nav toggle polish (workbench overhaul phase 6)"
```

---

## Task 2: Apply `table--stack` + data-labels to the 6 tables

**Files (modify, commit each screen separately):** `pipeline/pipeline-list.component.html`, `admin/requisitions/requisitions.component.ts`, `admin/members/members.component.ts`, `admin/gdpr/candidate-audit.component.ts`, `admin/interest-requests/interest-requests.component.ts`, `admin/csv-import/csv-import.component.ts`.

For each table: (a) add `table--stack` to the `<table class="table …">` class list; (b) add `data-label="<column name>"` to every `<td>` in the `@for`/row body, where the value matches that column's `<th>` text (read the header row); (c) extend the screen's existing spec with one assertion that the table has class `table--stack` and that a rendered `<td>` carries a non-empty `data-label`. Keep axe assertions. Commit message per screen: `feat(ui): responsive card-fallback on <screen> table (workbench overhaul phase 6)`.

- [ ] **Step 1: pipeline-list** (`pipeline-list.component.html`) — add `table--stack` + `data-label` on each body `<td>` (Candidate / Stage / SLA / Scheduling / … — mirror the `<th>` text). Extend `pipeline-list.component.spec.ts`. Commit.
- [ ] **Step 2: requisitions** (`requisitions.component.ts`) — columns Title / Status / Label / Actions. Extend spec. Commit.
- [ ] **Step 3: members** (`members.component.ts`) — mirror its `<th>` columns. Extend spec. Commit.
- [ ] **Step 4: candidate-audit** (`candidate-audit.component.ts`) — mirror its `<th>` columns. Extend spec. Commit.
- [ ] **Step 5: interest-requests** (`interest-requests.component.ts`) — mirror its `<th>` columns (`table class="rows table"` → add `table--stack`). Extend spec. Commit.
- [ ] **Step 6: csv-import** (`csv-import.component.ts`) — the conditional `rowResults` table (`class="rows table"` → add `table--stack`); mirror its `<th>` columns. Extend spec. Commit.

- [ ] **Step 7: Full-suite gate** — `ng test --watch=false` → all green. `git status --short` shows only the touched files committed + the 2 unrelated SEO files.

---

## Self-Review (completed at authoring time)
- **Coverage vs design:** Phase 6 = responsive card-fallback for every table + the flagged nav-toggle polish. Task 1 adds the single global rule + the toggle restyle; Task 2 applies it to all 6 real-table screens (the same 6 that got `app-table-scroll` in Phase 2). No `<ul>`/`<ol>` list screens are touched (they were never tables).
- **Placeholder scan:** the CSS + toggle markup are complete; the per-`<td>` `data-label` values are an explicit "mirror the `<th>` text" instruction (a mechanical read, not a vague directive) because the exact column strings live in each screen.
- **Consistency:** one `.table--stack` rule, tokenized; `data-label` pattern uniform; tables stay inside the Phase-1 `app-table-scroll`. axe stays green because the card layout only activates below 40rem (tests run wider). Toggle keeps `@@nav.toggle` + `aria-expanded`/`aria-controls`.
- **Scope guard:** frontend-only, CSS-driven; no backend, no new dependency, no logic change.
