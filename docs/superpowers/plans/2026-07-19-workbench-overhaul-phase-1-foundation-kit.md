# Workbench Overhaul — Phase 1: Foundation Kit — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the four reusable, presentational UI components (`PageHeader`, `EmptyState`, `Skeleton`, `TableScroll`) that every Cadence internal screen will adopt, so consistency has a single source of truth.

**Architecture:** Standalone Angular 17 components in a new `frontend/src/app/shared/ui/` directory. They are purely presentational — no services, no HTTP. They wrap design-system primitives that already exist in `frontend/src/styles.scss` (`.page__head`, `.empty`, `.skeleton`, `.visually-hidden`) and consume design tokens only. Copy is supplied by the consumer via `@Input` (each screen localizes its own strings with `$localize`); the sole in-kit string is "Loading…". This is the prerequisite for Phase 2 (consistency sweep) and Phase 6 (responsive tables).

**Tech Stack:** Angular 17.3 standalone components, TypeScript 5.4, Jasmine + Angular TestBed, `axe-core` (via the existing `frontend/src/testing/axe.ts` helper).

## Global Constraints

- Angular **standalone components only** — no NgModules (constitution / CLAUDE.md).
- New files live in `frontend/src/app/shared/ui/`.
- **Presentational only**: no injected services, no HTTP, no router navigation logic. The one routed affordance (`PageHeader.backLink`) uses `RouterLink`.
- **Styling via design tokens only** (`var(--space-*)`, `var(--step-*)`, `var(--accent)`, `var(--line)`, `var(--radius-sm)`, `var(--measure)`, `var(--ink)`, `var(--ink-faint)`). No hard-coded colors or px spacing except border widths and component-local sizing already expressed against tokens.
- **Reuse existing primitives**: `.page__head`, `.page__title-group`, `.eyebrow`, `.eyebrow--quiet`, `.empty`, `.skeleton`, `.visually-hidden`, `.btn`, `.btn--link`, `.muted`. The **only** new global CSS is `.table-scroll` (Task 4).
- **i18n**: consumer-provided copy is passed as inputs (no marker needed inside the kit). The only in-kit string, "Loading…", carries `i18n="@@ui.loading"`.
- **Accessibility bar**: every component spec asserts **axe-core 0 violations** using `attachToBody`/`axeViolations`/`detachFromBody` from `../../../testing/axe`.
- **Tests** run from the `frontend/` directory: `ng test --watch=false --include='**/<spec-file>'`.
- **Commits**: stage only the exact files listed in each task with `git add <paths>` — do **NOT** use `git add -A` (the working tree carries unrelated uncommitted SEO changes that must not be staged). Commit messages follow the repo trailer convention in CLAUDE.md (Co-Authored-By + Claude-Session).

---

## File Structure

- `frontend/src/app/shared/ui/page-header.component.ts` — masthead component (`app-page-header`).
- `frontend/src/app/shared/ui/page-header.component.spec.ts` — its Jasmine spec.
- `frontend/src/app/shared/ui/empty-state.component.ts` — empty-state component (`app-empty-state`).
- `frontend/src/app/shared/ui/empty-state.component.spec.ts` — its spec.
- `frontend/src/app/shared/ui/skeleton.component.ts` — loading-placeholder component (`app-skeleton`).
- `frontend/src/app/shared/ui/skeleton.component.spec.ts` — its spec.
- `frontend/src/app/shared/ui/table-scroll.component.ts` — table scroll wrapper (`app-table-scroll`).
- `frontend/src/app/shared/ui/table-scroll.component.spec.ts` — its spec.
- `frontend/src/styles.scss` — **modify**: add the `.table-scroll` utility (Task 4).

Each component is imported directly by its consumers in Phase 2 (no barrel file — matches the codebase convention of importing standalone components by path).

---

## Task 1: PageHeaderComponent

**Files:**
- Create: `frontend/src/app/shared/ui/page-header.component.ts`
- Test: `frontend/src/app/shared/ui/page-header.component.spec.ts`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `PageHeaderComponent`, selector `app-page-header`. Inputs: `eyebrow?: string`, `heading: string` (required), `subtitle?: string`, `backLink?: string`, `backLabel?: string`. Content slot: `[actions]` (right-aligned). Phase 2 screens use `<app-page-header eyebrow="…" heading="…"><button actions>…</button></app-page-header>`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/shared/ui/page-header.component.spec.ts`:

```ts
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { PageHeaderComponent } from './page-header.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

@Component({
  standalone: true,
  imports: [PageHeaderComponent],
  template: `
    <app-page-header eyebrow="Your work" heading="Pipeline" subtitle="Your list"
                     backLink="/app" backLabel="Back">
      <button actions type="button" class="btn btn--primary">New</button>
    </app-page-header>
  `
})
class HostComponent {}

describe('PageHeaderComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideRouter([])] });
    fixture = TestBed.createComponent(HostComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });

  afterEach(() => detachFromBody(el));

  it('renders the heading in an <h1> inside the .page__head masthead', () => {
    expect(el.querySelector('.page__head h1')?.textContent?.trim()).toBe('Pipeline');
  });

  it('renders the eyebrow and subtitle when provided', () => {
    expect(el.querySelector('.eyebrow')?.textContent?.trim()).toBe('Your work');
    expect(el.querySelector('.page__subtitle')?.textContent?.trim()).toBe('Your list');
  });

  it('projects [actions] content into the header', () => {
    expect(el.querySelector('.page__actions button')?.textContent?.trim()).toBe('New');
  });

  it('renders a routed back-link when backLink is set', () => {
    const back = el.querySelector('.page__back') as HTMLAnchorElement | null;
    expect(back?.textContent?.trim()).toBe('Back');
    expect(back?.getAttribute('href')).toBe('/app');
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

From `frontend/`: `ng test --watch=false --include='**/page-header.component.spec.ts'`
Expected: FAIL — cannot find module `./page-header.component` (component not created yet).

- [ ] **Step 3: Write the minimal implementation**

Create `frontend/src/app/shared/ui/page-header.component.ts`:

```ts
import { Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Shared workbench masthead (Phase 1 kit). Wraps the `.page__head` + `.page__title-group` +
 * `.eyebrow` structure the Dashboard uses so every internal screen gets an identical header.
 * Presentational only: copy is passed in (consumers localize via $localize); an optional routed
 * back-link serves drill-down pages (e.g. the candidate timeline). Right-aligned buttons project
 * through the `[actions]` slot.
 */
@Component({
  selector: 'app-page-header',
  standalone: true,
  imports: [RouterLink],
  template: `
    <header class="page__head">
      <div class="page__title-group">
        @if (backLink) {
          <a class="page__back btn btn--link" [routerLink]="backLink">{{ backLabel }}</a>
        }
        @if (eyebrow) {
          <p class="eyebrow eyebrow--quiet">{{ eyebrow }}</p>
        }
        <h1>{{ heading }}</h1>
        @if (subtitle) {
          <p class="page__subtitle muted">{{ subtitle }}</p>
        }
      </div>
      <div class="page__actions">
        <ng-content select="[actions]"></ng-content>
      </div>
    </header>
  `,
  styles: [`
    .page__actions { display: flex; flex-wrap: wrap; gap: var(--space-2); align-items: center; }
    .page__back { align-self: flex-start; margin-bottom: var(--space-2); padding-inline: 0; }
    .page__subtitle { margin: var(--space-1) 0 0; font-size: var(--step--1); }
  `]
})
export class PageHeaderComponent {
  @Input() eyebrow?: string;
  @Input({ required: true }) heading!: string;
  @Input() subtitle?: string;
  @Input() backLink?: string;
  @Input() backLabel?: string;
}
```

- [ ] **Step 4: Run the test to verify it passes**

From `frontend/`: `ng test --watch=false --include='**/page-header.component.spec.ts'`
Expected: PASS — all 5 specs green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/ui/page-header.component.ts frontend/src/app/shared/ui/page-header.component.spec.ts
git commit -m "feat(ui): add shared PageHeader masthead component (workbench overhaul phase 1)"
```

---

## Task 2: EmptyStateComponent

**Files:**
- Create: `frontend/src/app/shared/ui/empty-state.component.ts`
- Test: `frontend/src/app/shared/ui/empty-state.component.spec.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: `EmptyStateComponent`, selector `app-empty-state`. Inputs: `heading: string` (required), `body?: string`. Default `<ng-content>` slot holds the CTA. Phase 2 usage: `<app-empty-state heading="…" body="…"><a class="btn btn--primary" …>CTA</a></app-empty-state>`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/shared/ui/empty-state.component.spec.ts`:

```ts
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EmptyStateComponent } from './empty-state.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

@Component({
  standalone: true,
  imports: [EmptyStateComponent],
  template: `
    <app-empty-state heading="No candidates yet" body="Import a CSV to get started.">
      <a class="btn btn--primary" href="/admin/csv-import">Import candidates</a>
    </app-empty-state>
  `
})
class HostComponent {}

describe('EmptyStateComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });

  afterEach(() => detachFromBody(el));

  it('renders the heading and body inside the .empty primitive', () => {
    expect(el.querySelector('.empty .empty__title')?.textContent?.trim()).toBe('No candidates yet');
    expect(el.querySelector('.empty__body')?.textContent?.trim()).toBe('Import a CSV to get started.');
  });

  it('projects a CTA into the actions slot', () => {
    expect(el.querySelector('.empty__actions a')?.textContent?.trim()).toBe('Import candidates');
  });

  it('omits the body paragraph when no body is provided', () => {
    const f2 = TestBed.createComponent(EmptyStateComponent);
    f2.componentRef.setInput('heading', 'Empty');
    f2.detectChanges();
    expect((f2.nativeElement as HTMLElement).querySelector('.empty__body')).toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

From `frontend/`: `ng test --watch=false --include='**/empty-state.component.spec.ts'`
Expected: FAIL — cannot find module `./empty-state.component`.

- [ ] **Step 3: Write the minimal implementation**

Create `frontend/src/app/shared/ui/empty-state.component.ts`:

```ts
import { Component, Input } from '@angular/core';

/**
 * Shared empty-state (Phase 1 kit). Wraps the `.empty` primitive with a heading, optional body,
 * and a projected CTA so every "nothing here yet" surface guides the operator's first action
 * instead of showing bare text. Presentational only — copy and CTA come from the consumer.
 */
@Component({
  selector: 'app-empty-state',
  standalone: true,
  template: `
    <div class="empty">
      <h2 class="empty__title">{{ heading }}</h2>
      @if (body) {
        <p class="empty__body">{{ body }}</p>
      }
      <div class="empty__actions">
        <ng-content></ng-content>
      </div>
    </div>
  `,
  styles: [`
    .empty__title { font-size: var(--step-1); margin: 0 0 var(--space-2); color: var(--ink); }
    .empty__body { margin: 0 auto var(--space-4); max-width: var(--measure); }
    .empty__actions { display: flex; gap: var(--space-2); justify-content: center; flex-wrap: wrap; }
  `]
})
export class EmptyStateComponent {
  @Input({ required: true }) heading!: string;
  @Input() body?: string;
}
```

- [ ] **Step 4: Run the test to verify it passes**

From `frontend/`: `ng test --watch=false --include='**/empty-state.component.spec.ts'`
Expected: PASS — all 4 specs green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/ui/empty-state.component.ts frontend/src/app/shared/ui/empty-state.component.spec.ts
git commit -m "feat(ui): add shared EmptyState component (workbench overhaul phase 1)"
```

---

## Task 3: SkeletonComponent

**Files:**
- Create: `frontend/src/app/shared/ui/skeleton.component.ts`
- Test: `frontend/src/app/shared/ui/skeleton.component.spec.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: `SkeletonComponent`, selector `app-skeleton`. Inputs: `variant: 'table' | 'cards' | 'form' | 'lines'` (default `'lines'`), `rows: number` (default 5). Renders a `role="status"` region with an SR-only "Loading…" label and `rows` shimmer blocks shaped by `variant`. Phase 2 usage: `@if (loading()) { <app-skeleton variant="table" /> }`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/shared/ui/skeleton.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SkeletonComponent } from './skeleton.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

describe('SkeletonComponent', () => {
  let fixture: ComponentFixture<SkeletonComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [SkeletonComponent] });
    fixture = TestBed.createComponent(SkeletonComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
  });

  afterEach(() => detachFromBody(el));

  it('renders `rows` skeleton blocks for the chosen variant', () => {
    fixture.componentRef.setInput('variant', 'table');
    fixture.componentRef.setInput('rows', 3);
    fixture.detectChanges();
    expect(el.querySelectorAll('.skeleton__block').length).toBe(3);
    expect(el.querySelector('.skeleton-group--table')).not.toBeNull();
  });

  it('exposes a polite, busy status region with an SR-only Loading label', () => {
    fixture.detectChanges();
    const group = el.querySelector('.skeleton-group');
    expect(group?.getAttribute('role')).toBe('status');
    expect(group?.getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('.visually-hidden')?.textContent?.trim()).toBe('Loading…');
  });

  it('hides the shimmer blocks from assistive tech', () => {
    fixture.detectChanges();
    el.querySelectorAll('.skeleton__block').forEach((b) => {
      expect(b.getAttribute('aria-hidden')).toBe('true');
    });
  });

  it('defaults to 5 rows and the lines variant', () => {
    fixture.detectChanges();
    expect(el.querySelectorAll('.skeleton__block').length).toBe(5);
    expect(el.querySelector('.skeleton-group--lines')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    fixture.detectChanges();
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

From `frontend/`: `ng test --watch=false --include='**/skeleton.component.spec.ts'`
Expected: FAIL — cannot find module `./skeleton.component`.

- [ ] **Step 3: Write the minimal implementation**

Create `frontend/src/app/shared/ui/skeleton.component.ts`:

```ts
import { Component, Input } from '@angular/core';

type SkeletonVariant = 'table' | 'cards' | 'form' | 'lines';

/**
 * Shared loading placeholder (Phase 1 kit). Finally puts the dormant `.skeleton` primitive to work:
 * a polite `role="status"` region with an SR-only "Loading…" label and shimmer blocks shaped to the
 * content being awaited. Consumers render it while their existing `loading` signal is true.
 */
@Component({
  selector: 'app-skeleton',
  standalone: true,
  template: `
    <div class="skeleton-group" [class]="'skeleton-group--' + variant"
         role="status" aria-live="polite" aria-busy="true">
      <span class="visually-hidden" i18n="@@ui.loading">Loading…</span>
      @for (row of rowArray; track $index) {
        <div class="skeleton skeleton__block" aria-hidden="true"></div>
      }
    </div>
  `,
  styles: [`
    .skeleton-group { display: grid; gap: var(--space-3); }
    .skeleton-group--cards { grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr)); }
    .skeleton__block { height: 1.5rem; }
    .skeleton-group--table .skeleton__block { height: 2.75rem; }
    .skeleton-group--cards .skeleton__block { height: 7rem; border-radius: var(--radius-sm); }
    .skeleton-group--form .skeleton__block { height: 3rem; }
  `]
})
export class SkeletonComponent {
  @Input() variant: SkeletonVariant = 'lines';
  @Input() rows = 5;

  get rowArray(): readonly number[] {
    return Array.from({ length: Math.max(1, this.rows) }, (_, i) => i);
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

From `frontend/`: `ng test --watch=false --include='**/skeleton.component.spec.ts'`
Expected: PASS — all 5 specs green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/ui/skeleton.component.ts frontend/src/app/shared/ui/skeleton.component.spec.ts
git commit -m "feat(ui): add shared Skeleton loading component (workbench overhaul phase 1)"
```

---

## Task 4: TableScrollComponent + `.table-scroll` utility

**Files:**
- Create: `frontend/src/app/shared/ui/table-scroll.component.ts`
- Test: `frontend/src/app/shared/ui/table-scroll.component.spec.ts`
- Modify: `frontend/src/styles.scss` (add the `.table-scroll` utility near the `.table` rules, ~line 558)

**Interfaces:**
- Consumes: nothing.
- Produces: `TableScrollComponent`, selector `app-table-scroll`. Input: `ariaLabel?: string`. Wraps a projected `<table>` in a keyboard-focusable `.table-scroll` overflow-x container. Phase 2/6 usage: `<app-table-scroll ariaLabel="Candidates"><table class="table">…</table></app-table-scroll>`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/shared/ui/table-scroll.component.spec.ts`:

```ts
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TableScrollComponent } from './table-scroll.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

@Component({
  standalone: true,
  imports: [TableScrollComponent],
  template: `
    <app-table-scroll ariaLabel="Candidates">
      <table class="table">
        <thead><tr><th scope="col">Name</th></tr></thead>
        <tbody><tr><td>Dana</td></tr></tbody>
      </table>
    </app-table-scroll>
  `
})
class HostComponent {}

describe('TableScrollComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });

  afterEach(() => detachFromBody(el));

  it('wraps projected content in a keyboard-focusable .table-scroll region', () => {
    const wrap = el.querySelector('.table-scroll') as HTMLElement | null;
    expect(wrap).not.toBeNull();
    expect(wrap?.getAttribute('tabindex')).toBe('0');
    expect(wrap?.getAttribute('aria-label')).toBe('Candidates');
    expect(wrap?.querySelector('table.table')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

From `frontend/`: `ng test --watch=false --include='**/table-scroll.component.spec.ts'`
Expected: FAIL — cannot find module `./table-scroll.component`.

- [ ] **Step 3a: Write the minimal component**

Create `frontend/src/app/shared/ui/table-scroll.component.ts`:

```ts
import { Component, Input } from '@angular/core';

/**
 * Shared table scroll wrapper (Phase 1 kit). Wraps a projected `<table class="table">` in a
 * keyboard-focusable overflow-x container so a wide data table never breaks a narrow layout and a
 * pointerless user can still scroll it. Pass `ariaLabel` to name the scroll region (e.g. the table
 * caption). Presentational only.
 */
@Component({
  selector: 'app-table-scroll',
  standalone: true,
  template: `
    <div class="table-scroll" tabindex="0" [attr.aria-label]="ariaLabel || null">
      <ng-content></ng-content>
    </div>
  `
})
export class TableScrollComponent {
  @Input() ariaLabel?: string;
}
```

- [ ] **Step 3b: Add the `.table-scroll` utility to the global stylesheet**

In `frontend/src/styles.scss`, immediately after the `.table` rules block (the `.table th`/`.table td`/`.table thead th` declarations, ~line 558), add:

```scss
/* Horizontal-scroll wrapper for .table on narrow viewports (Phase 1 kit / TableScrollComponent).
   Keyboard-focusable so a pointerless user can scroll a wide table; the child keeps its content width. */
.table-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
.table-scroll:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.table-scroll > .table { min-width: max-content; }
```

- [ ] **Step 4: Run the test to verify it passes**

From `frontend/`: `ng test --watch=false --include='**/table-scroll.component.spec.ts'`
Expected: PASS — both specs green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/ui/table-scroll.component.ts frontend/src/app/shared/ui/table-scroll.component.spec.ts frontend/src/styles.scss
git commit -m "feat(ui): add shared TableScroll wrapper + .table-scroll utility (workbench overhaul phase 1)"
```

---

## Task 5: Full-suite regression gate

**Files:** none (verification only).

- [ ] **Step 1: Run the whole frontend test suite**

From `frontend/`: `ng test --watch=false`
Expected: PASS — the four new specs plus every pre-existing spec are green (the new `@@ui.loading` i18n id introduces no template regressions; no existing file was modified except the additive `.table-scroll` CSS).

- [ ] **Step 2: Confirm a clean, scoped working tree**

Run: `git status`
Expected: the four new component/spec files and `styles.scss` are committed; the only remaining unstaged entries are the pre-existing, unrelated SEO changes (`CLAUDE.md`, `frontend/src/environments/environment.prod.ts`) that this plan never touched.

---

## Self-Review (completed at authoring time)

- **Spec coverage:** Phase 1 of the design doc = the four-component kit + `.table-scroll`. Tasks 1–4 create exactly those; Task 5 gates the suite. Phases 2–6 are out of scope for this plan (each gets its own).
- **Placeholder scan:** none — every step contains full component code, full test code, and exact commands.
- **Type/name consistency:** selectors (`app-page-header`/`app-empty-state`/`app-skeleton`/`app-table-scroll`), input names (`eyebrow`/`heading`/`subtitle`/`backLink`/`backLabel`; `heading`/`body`; `variant`/`rows`; `ariaLabel`), and content slots (`[actions]`, default) are consistent across the tasks, the Interfaces blocks, and the design doc. Spec import path `../../../testing/axe` verified against `frontend/src/testing/axe.ts`. CSS classes reused (`.page__head`, `.empty`, `.skeleton`, `.visually-hidden`, `.eyebrow--quiet`, `.btn--link`, `.muted`) and tokens (`--space-*`, `--step-*`, `--accent`, `--ink`, `--radius-sm`, `--measure`) all exist in `styles.scss`; `.table-scroll` is the sole new class and is created in Task 4.
