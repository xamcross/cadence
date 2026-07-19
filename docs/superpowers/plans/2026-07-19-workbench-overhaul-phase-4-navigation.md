# Workbench Overhaul — Phase 4: Navigation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the workbench a persistent role-aware navigation sidebar + breadcrumbs so operators stop round-tripping through the `/app` launchpad to switch screens.

**Architecture:** Extract the launchpad's nav model (`ALL_GROUPS`) into a shared `core/nav/nav.config.ts` (single source of truth for both the launchpad and the new sidebar). A `SideNavComponent` renders role-filtered grouped links with active-route highlighting; it's a sticky rail on desktop and an accordion on mobile (no drawer/overlay/focus-trap — robustly accessible). A `BreadcrumbsComponent` shows Home > current screen. Both mount in the global chrome in `AppComponent`, gated on `data.shell === true` (like the top-bar), so candidate/token/auth routes stay bare.

**Tech Stack:** Angular 17.3 standalone, `@angular/router` (`RouterLink`/`RouterLinkActive`), Jasmine + TestBed (EdgeHeadless), axe via `frontend/src/testing/axe.ts`.

## Global Constraints

- **Standalone components only.** New components live in `frontend/src/app/features/shell/` (with `top-bar.component.ts`); the config in `frontend/src/app/core/nav/nav.config.ts`.
- **No behavior change to the launchpad** — Task 1 is a pure refactor (shell keeps rendering the same cards from the moved config).
- **Chrome gating:** the sidebar + breadcrumbs render ONLY inside `AppComponent`'s `@if (showChrome())` (i.e. `data.shell === true`). They must never appear on candidate/token/auth routes.
- **Design tokens only** (`--space-*`, `--step-*`, `--line`, `--surface`, `--surface-sunken`, `--accent-wash`, `--accent-ink`, `--ink`, `--radius-sm`, `--shadow-lg`, `--z-overlay`, `--content-max`). Reuse `.eyebrow`/`.eyebrow--quiet`, `.btn`/`.btn--ghost`.
- **i18n:** the nav labels are already `$localize` in `ALL_GROUPS` (preserved on move). New strings (toggle label, breadcrumb "Home"/aria) use `$localize` `@@` ids.
- **a11y:** sidebar is a `<nav aria-label="Primary">` with `aria-current="page"` on the active link (via `routerLinkActive` `ariaCurrentWhenActive`); the mobile toggle has `aria-expanded` + `aria-controls`; breadcrumbs are a `<nav aria-label="Breadcrumb"><ol>`. Every new component spec asserts axe 0 violations.
- **Testing** from `frontend/`: `ng test --watch=false --include='**/<spec>'`.
- **Git:** stage only each task's files (never `-A`; leave `CLAUDE.md`/`environment.prod.ts`). Commit per task, trailer convention, no push.
- **Note:** structure + a11y + active-state are unit-tested; exact visual layout is not. A quick manual visual check of the rail/accordion is advisable after this phase (call it out in the final report).

## File Structure
- Create `frontend/src/app/core/nav/nav.config.ts` — `NavItem`/`NavGroup` types + `NAV_GROUPS`.
- Modify `frontend/src/app/features/shell/shell.component.ts` — import from nav.config; delete local copies.
- Create `frontend/src/app/features/shell/side-nav.component.ts` (+ spec) — `app-side-nav`.
- Create `frontend/src/app/features/shell/breadcrumbs.component.ts` (+ spec) — `app-breadcrumbs`.
- Modify `frontend/src/app/app.component.ts` + `.html` + `.scss` — chrome layout + mounts.
- Modify `frontend/src/app/app.routes.ts` — add `data.breadcrumb` to the 3 non-nav shell routes.

---

## Task 1: Extract nav.config.ts (pure refactor)

**Files:** Create `frontend/src/app/core/nav/nav.config.ts`; Modify `frontend/src/app/features/shell/shell.component.ts`; touch `shell.component.spec.ts` only if it references the moved symbols.

**Interfaces:** Produces `NavItem { path: string; label: string; desc: string; roles: readonly Role[] }`, `NavGroup { title: string; items: readonly NavItem[] }`, and `NAV_GROUPS: readonly NavGroup[]`.

- [ ] **Step 1: Create the config file.** Move the `NavItem` and `NavGroup` interfaces and the entire `ALL_GROUPS` array **verbatim** out of `shell.component.ts` into a new `frontend/src/app/core/nav/nav.config.ts`, renamed to `NAV_GROUPS` and exported. Keep every `$localize` label/desc/id and every `path`/`roles` unchanged. Add:
```ts
import { Role } from '../auth/auth.models';

export interface NavItem { readonly path: string; readonly label: string; readonly desc: string; readonly roles: readonly Role[]; }
export interface NavGroup { readonly title: string; readonly items: readonly NavItem[]; }

export const NAV_GROUPS: readonly NavGroup[] = [ /* …the exact ALL_GROUPS content… */ ];
```
(`core/nav/` → `core/auth/auth.models` is `../auth/auth.models`.)

- [ ] **Step 2: Refactor the shell.** In `shell.component.ts`: delete the local `NavItem`/`NavGroup` interfaces and the `ALL_GROUPS` const; `import { NAV_GROUPS, NavGroup } from '../../core/nav/nav.config';` and change the `groups` computed to map over `NAV_GROUPS` instead of `ALL_GROUPS`. Nothing else changes.

- [ ] **Step 3: Run the shell spec.** `ng test --watch=false --include='**/shell.component.spec.ts'` → still green (behavior unchanged). Fix the spec only if it imported the now-moved symbols.

- [ ] **Step 4: Commit**
```bash
git add frontend/src/app/core/nav/nav.config.ts frontend/src/app/features/shell/shell.component.ts
# add shell.component.spec.ts only if modified
git commit -m "refactor(ui): extract nav config to core/nav/nav.config.ts (workbench overhaul phase 4)"
```

---

## Task 2: SideNavComponent

**Files:** Create `frontend/src/app/features/shell/side-nav.component.ts` (+ `.spec.ts`).

**Interfaces:** Consumes `NAV_GROUPS`, `AuthService.member$`. Produces `SideNavComponent`, selector `app-side-nav`. Role-filtered grouped links, active highlighting, mobile accordion via an `open` signal.

- [ ] **Step 1: Write the failing test** — create `side-nav.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { SideNavComponent } from './side-nav.component';
import { AuthService } from '../../core/auth/auth.service';
import { MemberSummary } from '../../core/auth/auth.models';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

const admin: MemberSummary = { memberId: 'm1', workspaceId: 'w1', role: 'ADMIN', displayName: 'Ada', email: 'a@x.co', workspaceConfigured: true };

describe('SideNavComponent', () => {
  let fixture: ComponentFixture<SideNavComponent>;
  let el: HTMLElement;
  let member$: BehaviorSubject<MemberSummary | null>;

  beforeEach(() => {
    member$ = new BehaviorSubject<MemberSummary | null>(admin);
    TestBed.configureTestingModule({
      imports: [SideNavComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: { member$ } }]
    });
    fixture = TestBed.createComponent(SideNavComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });
  afterEach(() => detachFromBody(el));

  it('renders a Primary nav landmark with role-filtered links', () => {
    const nav = el.querySelector('nav[aria-label="Primary"]');
    expect(nav).not.toBeNull();
    const links = el.querySelectorAll('.side-nav__link');
    expect(links.length).toBeGreaterThan(5);
    // Admin sees the Members admin link:
    expect(Array.from(links).some((a) => a.getAttribute('href') === '/admin/members')).toBe(true);
  });

  it('hides admin-only links for an interviewer', () => {
    member$.next({ ...admin, role: 'INTERVIEWER' });
    fixture.detectChanges();
    const hrefs = Array.from(el.querySelectorAll('.side-nav__link')).map((a) => a.getAttribute('href'));
    expect(hrefs).not.toContain('/admin/members');
    expect(hrefs).toContain('/calendar/connections'); // interviewer keeps the personal link
  });

  it('toggles the mobile accordion open state', () => {
    const toggle = el.querySelector('.side-nav__toggle') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click(); fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(el.querySelector('.side-nav__panel--open')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const v = await axeViolations(el);
    expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
  });
});
```

- [ ] **Step 2: Run to verify FAIL** — `ng test --watch=false --include='**/side-nav.component.spec.ts'` → FAIL.

- [ ] **Step 3: Implement** — create `side-nav.component.ts`:

```ts
import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { NAV_GROUPS, NavGroup } from '../../core/nav/nav.config';

/** Persistent role-aware navigation (workbench overhaul phase 4). Sticky rail on desktop; an
 *  accordion under a toggle on mobile (no drawer/overlay/focus-trap). Rendered by AppComponent on
 *  data.shell routes only. Server + roleGuard remain the boundary; this only hides links a role can't use. */
@Component({
  selector: 'app-side-nav',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  template: `
    @if (member()) {
      <button type="button" class="side-nav__toggle btn btn--ghost" (click)="toggle()"
              [attr.aria-expanded]="open()" aria-controls="primary-nav"
              i18n="@@nav.toggle">Menu</button>
      <nav id="primary-nav" class="side-nav__panel" [class.side-nav__panel--open]="open()"
           aria-label="Primary" i18n-aria-label="@@nav.primary">
        @for (group of groups(); track group.title) {
          <div class="side-nav__group">
            <p class="side-nav__group-title eyebrow eyebrow--quiet">{{ group.title }}</p>
            <ul class="side-nav__list">
              @for (item of group.items; track item.path) {
                <li>
                  <a class="side-nav__link" [routerLink]="item.path"
                     routerLinkActive="is-active" ariaCurrentWhenActive="page"
                     (click)="close()">{{ item.label }}</a>
                </li>
              }
            </ul>
          </div>
        }
      </nav>
    }
  `,
  styles: [`
    :host { display: block; }
    .side-nav__toggle { width: 100%; justify-content: center; margin-bottom: var(--space-3); }
    .side-nav__panel { display: none; }
    .side-nav__panel--open { display: block; }
    .side-nav__group { margin-bottom: var(--space-5); }
    .side-nav__group-title { margin-bottom: var(--space-2); }
    .side-nav__list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
    .side-nav__link {
      display: block; padding: var(--space-2) var(--space-3); border-radius: var(--radius-sm);
      color: var(--ink); text-decoration: none; font-size: var(--step--1);
    }
    .side-nav__link:hover { background: var(--surface-sunken); }
    .side-nav__link.is-active { background: var(--accent-wash); color: var(--accent-ink); font-weight: 600; }
    @media (min-width: 48rem) {
      :host { position: sticky; top: 4rem; align-self: flex-start; flex: none; width: 15rem; }
      .side-nav__toggle { display: none; }
      .side-nav__panel { display: block; }   /* always open on desktop */
    }
  `]
})
export class SideNavComponent {
  private readonly auth = inject(AuthService);
  readonly member = toSignal(this.auth.member$, { initialValue: null });
  readonly open = signal(false);

  readonly groups = computed<NavGroup[]>(() => {
    const role = this.member()?.role;
    if (!role) return [];
    return NAV_GROUPS
      .map((g) => ({ title: g.title, items: g.items.filter((i) => i.roles.includes(role)) }))
      .filter((g) => g.items.length > 0);
  });

  toggle(): void { this.open.update((v) => !v); }
  close(): void { this.open.set(false); }
}
```

- [ ] **Step 4: Run to verify PASS** — 4/4 pass.
- [ ] **Step 5: Commit**
```bash
git add frontend/src/app/features/shell/side-nav.component.ts frontend/src/app/features/shell/side-nav.component.spec.ts
git commit -m "feat(ui): add role-aware SideNavComponent (workbench overhaul phase 4)"
```

---

## Task 3: BreadcrumbsComponent

**Files:** Create `frontend/src/app/features/shell/breadcrumbs.component.ts` (+ `.spec.ts`).

**Interfaces:** Consumes `Router`, `NAV_GROUPS`. Produces `BreadcrumbsComponent`, selector `app-breadcrumbs`. Shows `Home > <current screen>`; nothing extra on `/app`.

- [ ] **Step 1: Write the failing test** — create `breadcrumbs.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Component } from '@angular/core';
import { BreadcrumbsComponent } from './breadcrumbs.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

@Component({ standalone: true, template: 'x' }) class Dummy {}

describe('BreadcrumbsComponent', () => {
  let fixture: ComponentFixture<BreadcrumbsComponent>;
  let el: HTMLElement;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [BreadcrumbsComponent],
      providers: [provideRouter([
        { path: 'pipeline', component: Dummy },
        { path: 'app', component: Dummy },
        { path: 'pipeline/candidate/:id/timeline', component: Dummy, data: { breadcrumb: 'Candidate timeline' } }
      ])]
    });
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(BreadcrumbsComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
  });
  afterEach(() => detachFromBody(el));

  it('shows Home > <nav label> for a nav route', async () => {
    await router.navigateByUrl('/pipeline');
    fixture.detectChanges();
    const items = el.querySelectorAll('.breadcrumbs__item');
    expect(items.length).toBe(2);
    expect(items[0].textContent).toContain('Home');
    expect(items[1].textContent).toContain('Pipeline');
    expect(items[1].querySelector('[aria-current="page"]')).not.toBeNull();
  });

  it('prefers route data.breadcrumb for a drill-down route', async () => {
    await router.navigateByUrl('/pipeline/candidate/c1/timeline');
    fixture.detectChanges();
    const items = el.querySelectorAll('.breadcrumbs__item');
    expect(items[items.length - 1].textContent).toContain('Candidate timeline');
  });

  it('renders only Home on the launchpad', async () => {
    await router.navigateByUrl('/app');
    fixture.detectChanges();
    expect(el.querySelector('nav.breadcrumbs')).toBeNull(); // hidden when only Home
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    await router.navigateByUrl('/pipeline');
    fixture.detectChanges();
    const v = await axeViolations(el);
    expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
  });
});
```

- [ ] **Step 2: Run to verify FAIL** — `ng test --watch=false --include='**/breadcrumbs.component.spec.ts'` → FAIL.

- [ ] **Step 3: Implement** — create `breadcrumbs.component.ts`:

```ts
import { Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter } from 'rxjs';
import { NAV_GROUPS } from '../../core/nav/nav.config';

interface Crumb { readonly label: string; readonly link: string | null; }

/** Home > current-screen breadcrumb (workbench overhaul phase 4). Label resolves from the deepest route's
 *  data.breadcrumb first, then the nav config (longest path match). Hidden on the /app launchpad. */
@Component({
  selector: 'app-breadcrumbs',
  standalone: true,
  imports: [RouterLink],
  template: `
    @if (crumbs().length > 1) {
      <nav class="breadcrumbs" aria-label="Breadcrumb" i18n-aria-label="@@breadcrumb.aria">
        <ol class="breadcrumbs__list">
          @for (c of crumbs(); track c.label; let last = $last) {
            <li class="breadcrumbs__item">
              @if (c.link && !last) {
                <a [routerLink]="c.link">{{ c.label }}</a>
                <span class="breadcrumbs__sep" aria-hidden="true">/</span>
              } @else {
                <span aria-current="page">{{ c.label }}</span>
              }
            </li>
          }
        </ol>
      </nav>
    }
  `,
  styles: [`
    .breadcrumbs { padding-block: var(--space-3); }
    .breadcrumbs__list { list-style: none; display: flex; flex-wrap: wrap; gap: var(--space-2); margin: 0; padding: 0; font-size: var(--step--1); color: var(--ink-faint); }
    .breadcrumbs__item { display: inline-flex; gap: var(--space-2); align-items: center; }
    .breadcrumbs a { color: var(--ink-faint); text-decoration: none; }
    .breadcrumbs a:hover { text-decoration: underline; }
  `]
})
export class BreadcrumbsComponent {
  private readonly router = inject(Router);
  readonly crumbs = signal<Crumb[]>([]);
  private readonly home: Crumb = { label: $localize`:@@breadcrumb.home:Home`, link: '/app' };

  constructor() {
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => this.rebuild());
    this.rebuild();
  }

  private rebuild(): void {
    const url = this.router.url.split(/[?#]/)[0];
    if (url === '/app' || url === '/' || url === '') { this.crumbs.set([this.home]); return; }
    const label = this.labelFor(url);
    this.crumbs.set(label ? [this.home, { label, link: null }] : [this.home]);
  }

  private labelFor(url: string): string | null {
    let r = this.router.routerState.snapshot.root;
    while (r.firstChild) { r = r.firstChild; }
    const fromData = r.data['breadcrumb'] as string | undefined;
    if (fromData) return fromData;
    let best: { label: string; len: number } | null = null;
    for (const g of NAV_GROUPS) {
      for (const it of g.items) {
        if ((url === it.path || url.startsWith(it.path + '/')) && (!best || it.path.length > best.len)) {
          best = { label: it.label, len: it.path.length };
        }
      }
    }
    return best ? best.label : null;
  }
}
```

- [ ] **Step 4: Run to verify PASS** — 4/4 pass.
- [ ] **Step 5: Commit**
```bash
git add frontend/src/app/features/shell/breadcrumbs.component.ts frontend/src/app/features/shell/breadcrumbs.component.spec.ts
git commit -m "feat(ui): add BreadcrumbsComponent (workbench overhaul phase 4)"
```

---

## Task 4: Integrate into the chrome + route breadcrumb data

**Files:** Modify `app.component.ts`, `app.component.html`, `app.component.scss`, `app.routes.ts`; touch `app.component.spec.ts` only if it breaks.

- [ ] **Step 1: Add breadcrumb labels to the 3 non-nav shell routes.** In `app.routes.ts`, add `breadcrumb` to the `data` of these routes (the ones not covered by the nav config): `workspace/setup` → `breadcrumb: 'Workspace setup'`; `admin/interest-requests` → `breadcrumb: 'Access requests'`; `pipeline/candidate/:candidateId/timeline` → `breadcrumb: 'Candidate timeline'`. (Leave all other routes as-is; their labels come from the nav config.) Keep the existing `seo`/`shell` data keys.

- [ ] **Step 2: Import the components** in `app.component.ts` — add to TS imports and the `imports: [...]` array:
```ts
import { SideNavComponent } from './features/shell/side-nav.component';
import { BreadcrumbsComponent } from './features/shell/breadcrumbs.component';
```

- [ ] **Step 3: Restructure the chrome** in `app.component.html`:
```html
@if (showChrome()) {
  <a class="skip-link" href="#main-content" i18n="@@app.skip">Skip to content</a>
  <app-top-bar />
}
<div class="app-shell" [class.app-shell--chrome]="showChrome()">
  @if (showChrome()) { <app-side-nav /> }
  <div id="main-content" class="app-main" tabindex="-1">
    @if (showChrome()) { <app-breadcrumbs /> }
    <router-outlet></router-outlet>
  </div>
</div>
<app-toast-host />
<app-confirm-dialog />
```
(Single `router-outlet`. On non-chrome routes `.app-shell` has no `--chrome` modifier and `.app-main` is full-width, so candidate/token/auth pages render exactly as before.)

- [ ] **Step 4: Add the layout CSS** to `app.component.scss` (keep the existing `#main-content:focus` rule):
```scss
.app-shell--chrome {
  display: flex; flex-direction: column;
  gap: var(--space-4);
  max-width: var(--content-max); margin-inline: auto;
  padding-inline: var(--space-4);
}
.app-shell--chrome .app-main { flex: 1; min-width: 0; }
@media (min-width: 48rem) {
  .app-shell--chrome { flex-direction: row; align-items: flex-start; }
}
```

- [ ] **Step 5: Run the app spec + full suite.** `ng test --watch=false --include='**/app.component.spec.ts'` (fix minimally if the new tags break a strict assertion — don't remove existing assertions), then `ng test --watch=false` → all green.

- [ ] **Step 6: Commit**
```bash
git add frontend/src/app/app.component.ts frontend/src/app/app.component.html frontend/src/app/app.component.scss frontend/src/app/app.routes.ts
# add app.component.spec.ts only if modified
git commit -m "feat(ui): mount sidebar + breadcrumbs in the workbench chrome (workbench overhaul phase 4)"
```

---

## Self-Review (completed at authoring time)
- **Coverage vs design:** Phase 4 = persistent role-aware sidebar + breadcrumbs, mounted in global chrome, config extracted to a single source. Task 1 extracts; Tasks 2–3 build; Task 4 integrates + adds the 3 breadcrumb-data routes. The launchpad keeps working off the same `NAV_GROUPS`.
- **Placeholder scan:** none — full component + spec code; the only per-file unknowns (whether shell/app specs need a tweak) are explicit conditional steps.
- **Consistency:** `NAV_GROUPS` type/shape matches the shell's existing `ALL_GROUPS`; `SideNavComponent`/`BreadcrumbsComponent` selectors; `AuthService.member$` + `MemberSummary.role` verified; chrome gating mirrors the existing `showChrome()`/top-bar pattern; import depths (`features/shell/` → `../../core/...`, spec axe → `../../../testing/axe`) verified. Tokens all exist in `styles.scss`.
- **Scope guard:** candidate/token/auth routes stay bare (gated by `showChrome()`); no route guard/security change; nav is presentational (server + roleGuard remain the boundary). Visual polish of the rail/accordion is unit-covered for structure/a11y only — a manual look is recommended post-merge.
