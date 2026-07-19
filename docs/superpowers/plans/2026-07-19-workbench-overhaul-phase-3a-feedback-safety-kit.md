# Workbench Overhaul — Phase 3a: Feedback & Safety Kit — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the feedback & safety primitives — a `ToastService` + `<app-toast-host>` for transient action feedback, and a `ConfirmDialogService` + `<app-confirm-dialog>` accessible modal for gating destructive actions — and mount both once in `AppComponent`.

**Architecture:** Two signal-driven services (`providedIn: 'root'`) and two standalone host components in `frontend/src/app/shared/ui/`. The confirm dialog is **hand-rolled with `@angular/cdk/a11y`** (the codebase already uses only CDK a11y — `FocusTrap`/`LiveAnnouncer` — and imports no CDK overlay CSS): a single signal-driven modal in the root, focus-trapped via `cdkTrapFocus`, no CDK Overlay/Dialog. Both hosts mount globally in `AppComponent` (not gated on `showChrome`) so any route can use them. This phase only *builds* the primitives; Phase 3b wires them into destructive actions and success paths.

**Tech Stack:** Angular 17.3 standalone, `@angular/cdk/a11y` 17.3.10 (already installed), Jasmine + TestBed (EdgeHeadless), axe-core via `frontend/src/testing/axe.ts`.

## Global Constraints

- **Standalone components only**; services `@Injectable({ providedIn: 'root' })`.
- New files in `frontend/src/app/shared/ui/`. Spec axe-helper import from there: `../../../testing/axe`.
- **Design tokens only** (`--space-*`, `--step-*`, `--surface-raised`, `--line`, `--ink`, `--ink-faint`, `--radius-sm`, `--radius-lg`, `--shadow-md`, `--shadow-lg`, `--ok`, `--danger`, `--accent`, `--z-overlay`). No new global CSS; reuse `.btn`, `.btn--ghost`, `.btn--primary`, `.btn--danger`, `.btn--sm`.
- **i18n**: the only in-kit strings are the dialog's default button labels ("Confirm"/"Cancel") and the toast dismiss label — use `$localize`/`i18n` with `@@` ids. All other copy is caller-supplied.
- **Accessibility**: toast items use `role="status"` (or `role="alert"` for errors); the dialog uses `role="dialog"` + `aria-modal="true"` + `aria-labelledby`/`aria-describedby`, `cdkTrapFocus` with auto-capture, ESC-to-cancel, backdrop-click-to-cancel. Every component spec asserts **axe-core 0 violations**.
- **Testing** from `frontend/`: `ng test --watch=false --include='**/<spec>'`.
- **Git**: stage only each task's files (`git add <paths>`, never `-A`; leave the unrelated `CLAUDE.md` / `environment.prod.ts` unstaged). Commit per task with the CLAUDE.md trailer convention. No push.

## File Structure

- `frontend/src/app/shared/ui/toast.service.ts` (+ `.spec.ts`) — the toast state/service.
- `frontend/src/app/shared/ui/toast-host.component.ts` (+ `.spec.ts`) — `app-toast-host`.
- `frontend/src/app/shared/ui/confirm-dialog.service.ts` (+ `.spec.ts`) — the confirm request/promise broker.
- `frontend/src/app/shared/ui/confirm-dialog.component.ts` (+ `.spec.ts`) — `app-confirm-dialog`.
- **Modify** `frontend/src/app/app.component.ts` + `app.component.html` — mount both hosts globally.

---

## Task 1: ToastService

**Files:** Create `frontend/src/app/shared/ui/toast.service.ts`; Test `…/toast.service.spec.ts`.

**Interfaces:** Produces `ToastService` with `readonly toasts = signal<readonly Toast[]>`, `success(msg): number`, `error(msg): number`, `info(msg): number`, `show(kind, msg): number`, `dismiss(id): void`. `type ToastKind = 'success'|'error'|'info'`; `interface Toast { id: number; kind: ToastKind; message: string }`.

- [ ] **Step 1: Write the failing test** — create `toast.service.spec.ts`:

```ts
import { fakeAsync, tick, TestBed } from '@angular/core/testing';
import { ToastService } from './toast.service';

describe('ToastService', () => {
  let svc: ToastService;
  beforeEach(() => { TestBed.configureTestingModule({}); svc = TestBed.inject(ToastService); });

  it('adds a toast with the given kind and message', () => {
    svc.success('Saved');
    expect(svc.toasts().length).toBe(1);
    expect(svc.toasts()[0].kind).toBe('success');
    expect(svc.toasts()[0].message).toBe('Saved');
  });

  it('supports error and info kinds', () => {
    svc.error('Boom'); svc.info('FYI');
    expect(svc.toasts().map((t) => t.kind)).toEqual(['error', 'info']);
  });

  it('dismiss removes the toast by id', () => {
    const id = svc.success('Saved');
    svc.dismiss(id);
    expect(svc.toasts().length).toBe(0);
  });

  it('auto-dismisses after the delay', fakeAsync(() => {
    svc.success('Saved');
    expect(svc.toasts().length).toBe(1);
    tick(4000);
    expect(svc.toasts().length).toBe(0);
  }));
});
```

- [ ] **Step 2: Run to verify FAIL** — from `frontend/`: `ng test --watch=false --include='**/toast.service.spec.ts'` → FAIL (module not found).

- [ ] **Step 3: Implement** — create `toast.service.ts`:

```ts
import { Injectable, signal } from '@angular/core';

export type ToastKind = 'success' | 'error' | 'info';
export interface Toast { readonly id: number; readonly kind: ToastKind; readonly message: string; }

/** Transient action feedback. Signal-driven; rendered by ToastHostComponent (mounted once in AppComponent). */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private seq = 0;
  readonly toasts = signal<readonly Toast[]>([]);

  private delayFor(kind: ToastKind): number { return kind === 'error' ? 8000 : 4000; }

  show(kind: ToastKind, message: string): number {
    const id = ++this.seq;
    this.toasts.update((list) => [...list, { id, kind, message }]);
    setTimeout(() => this.dismiss(id), this.delayFor(kind));
    return id;
  }
  success(message: string): number { return this.show('success', message); }
  error(message: string): number { return this.show('error', message); }
  info(message: string): number { return this.show('info', message); }
  dismiss(id: number): void { this.toasts.update((list) => list.filter((t) => t.id !== id)); }
}
```

- [ ] **Step 4: Run to verify PASS** — same command → 4/4 pass.
- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/ui/toast.service.ts frontend/src/app/shared/ui/toast.service.spec.ts
git commit -m "feat(ui): add ToastService (workbench overhaul phase 3a)"
```

---

## Task 2: ToastHostComponent

**Files:** Create `frontend/src/app/shared/ui/toast-host.component.ts`; Test `…/toast-host.component.spec.ts`.

**Interfaces:** Consumes `ToastService`. Produces `ToastHostComponent`, selector `app-toast-host` (no inputs). Renders `toasts()` as a fixed bottom-right stack; each toast has `role="status"` (`role="alert"` for `kind==='error'`) and a dismiss button.

- [ ] **Step 1: Write the failing test** — create `toast-host.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ToastHostComponent } from './toast-host.component';
import { ToastService } from './toast.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

describe('ToastHostComponent', () => {
  let fixture: ComponentFixture<ToastHostComponent>;
  let el: HTMLElement;
  let svc: ToastService;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ToastHostComponent] });
    svc = TestBed.inject(ToastService);
    fixture = TestBed.createComponent(ToastHostComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });
  afterEach(() => detachFromBody(el));

  it('renders a toast pushed to the service', () => {
    svc.success('Saved'); fixture.detectChanges();
    const toast = el.querySelector('.toast');
    expect(toast?.textContent).toContain('Saved');
    expect(toast?.getAttribute('role')).toBe('status');
  });

  it('uses role=alert for error toasts', () => {
    svc.error('Boom'); fixture.detectChanges();
    expect(el.querySelector('.toast--error')?.getAttribute('role')).toBe('alert');
  });

  it('dismiss button removes the toast', () => {
    svc.success('Saved'); fixture.detectChanges();
    (el.querySelector('.toast__close') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.toast')).toBeNull();
  });

  it('has zero axe violations with a toast shown', async () => {
    svc.info('FYI'); fixture.detectChanges();
    const v = await axeViolations(el);
    expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
  });
});
```

- [ ] **Step 2: Run to verify FAIL** — `ng test --watch=false --include='**/toast-host.component.spec.ts'` → FAIL.

- [ ] **Step 3: Implement** — create `toast-host.component.ts`:

```ts
import { Component, inject } from '@angular/core';
import { ToastService } from './toast.service';

/** Renders ToastService toasts as a fixed bottom-right stack. Mounted once in AppComponent. Each toast
 *  is its own live region (role=status, or role=alert for errors) so screen readers announce it. */
@Component({
  selector: 'app-toast-host',
  standalone: true,
  template: `
    <div class="toast-host">
      @for (t of toasts(); track t.id) {
        <div class="toast toast--{{ t.kind }}" [attr.role]="t.kind === 'error' ? 'alert' : 'status'">
          <span class="toast__msg">{{ t.message }}</span>
          <button type="button" class="toast__close btn btn--ghost btn--sm"
                  (click)="dismiss(t.id)" aria-label="Dismiss notification" i18n-aria-label="@@toast.dismiss">&times;</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .toast-host {
      position: fixed; inset-block-end: var(--space-4); inset-inline-end: var(--space-4);
      z-index: var(--z-overlay); display: flex; flex-direction: column; gap: var(--space-2);
      max-width: min(24rem, calc(100vw - var(--space-6))); pointer-events: none;
    }
    .toast {
      pointer-events: auto; display: flex; align-items: flex-start; gap: var(--space-3);
      padding: var(--space-3) var(--space-4); background: var(--surface-raised);
      border: 1px solid var(--line); border-inline-start: 3px solid var(--accent);
      border-radius: var(--radius-sm); box-shadow: var(--shadow-md); color: var(--ink);
    }
    .toast--success { border-inline-start-color: var(--ok); }
    .toast--error { border-inline-start-color: var(--danger); }
    .toast--info { border-inline-start-color: var(--accent); }
    .toast__msg { flex: 1; font-size: var(--step--1); line-height: 1.4; }
    .toast__close { flex: none; }
    @media (prefers-reduced-motion: no-preference) {
      .toast { animation: cad-toast-in 0.18s ease; }
      @keyframes cad-toast-in { from { opacity: 0; transform: translateY(0.5rem); } }
    }
  `]
})
export class ToastHostComponent {
  private readonly svc = inject(ToastService);
  readonly toasts = this.svc.toasts;
  dismiss(id: number): void { this.svc.dismiss(id); }
}
```

- [ ] **Step 4: Run to verify PASS** — 4/4 pass.
- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/ui/toast-host.component.ts frontend/src/app/shared/ui/toast-host.component.spec.ts
git commit -m "feat(ui): add ToastHostComponent (workbench overhaul phase 3a)"
```

---

## Task 3: ConfirmDialogService

**Files:** Create `frontend/src/app/shared/ui/confirm-dialog.service.ts`; Test `…/confirm-dialog.service.spec.ts`.

**Interfaces:** Produces `ConfirmDialogService` with `readonly request = signal<ConfirmOptions | null>`, `confirm(options): Promise<boolean>`, `respond(confirmed: boolean): void`. `interface ConfirmOptions { title: string; body?: string; confirmLabel?: string; cancelLabel?: string; danger?: boolean }`. Opening a second confirm resolves the first as `false`.

- [ ] **Step 1: Write the failing test** — create `confirm-dialog.service.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { ConfirmDialogService } from './confirm-dialog.service';

describe('ConfirmDialogService', () => {
  let svc: ConfirmDialogService;
  beforeEach(() => { TestBed.configureTestingModule({}); svc = TestBed.inject(ConfirmDialogService); });

  it('confirm() sets the active request', () => {
    void svc.confirm({ title: 'Delete?' });
    expect(svc.request()?.title).toBe('Delete?');
  });

  it('respond(true) resolves the promise true and clears the request', async () => {
    const p = svc.confirm({ title: 'Delete?' });
    svc.respond(true);
    await expectAsync(p).toBeResolvedTo(true);
    expect(svc.request()).toBeNull();
  });

  it('respond(false) resolves false', async () => {
    const p = svc.confirm({ title: 'Delete?' });
    svc.respond(false);
    await expectAsync(p).toBeResolvedTo(false);
  });

  it('opening a second confirm resolves the first as false', async () => {
    const first = svc.confirm({ title: 'A' });
    const second = svc.confirm({ title: 'B' });
    await expectAsync(first).toBeResolvedTo(false);
    expect(svc.request()?.title).toBe('B');
    svc.respond(true);
    await expectAsync(second).toBeResolvedTo(true);
  });
});
```

- [ ] **Step 2: Run to verify FAIL** — `ng test --watch=false --include='**/confirm-dialog.service.spec.ts'` → FAIL.

- [ ] **Step 3: Implement** — create `confirm-dialog.service.ts`:

```ts
import { Injectable, signal } from '@angular/core';

export interface ConfirmOptions {
  readonly title: string;
  readonly body?: string;
  readonly confirmLabel?: string;
  readonly cancelLabel?: string;
  /** Style the confirm button as destructive (btn--danger). */
  readonly danger?: boolean;
}

/** Brokers a single confirm modal. `confirm()` returns a Promise resolved by the user's choice
 *  (or false if a newer confirm supersedes it, or the dialog is dismissed). Rendered by ConfirmDialogComponent. */
@Injectable({ providedIn: 'root' })
export class ConfirmDialogService {
  readonly request = signal<ConfirmOptions | null>(null);
  private resolver: ((v: boolean) => void) | null = null;

  confirm(options: ConfirmOptions): Promise<boolean> {
    this.resolve(false); // supersede any open dialog
    this.request.set(options);
    return new Promise<boolean>((res) => { this.resolver = res; });
  }

  respond(confirmed: boolean): void { this.resolve(confirmed); }

  private resolve(value: boolean): void {
    const r = this.resolver;
    this.resolver = null;
    this.request.set(null);
    if (r) r(value);
  }
}
```

- [ ] **Step 4: Run to verify PASS** — 4/4 pass.
- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/ui/confirm-dialog.service.ts frontend/src/app/shared/ui/confirm-dialog.service.spec.ts
git commit -m "feat(ui): add ConfirmDialogService (workbench overhaul phase 3a)"
```

---

## Task 4: ConfirmDialogComponent

**Files:** Create `frontend/src/app/shared/ui/confirm-dialog.component.ts`; Test `…/confirm-dialog.component.spec.ts`.

**Interfaces:** Consumes `ConfirmDialogService`. Produces `ConfirmDialogComponent`, selector `app-confirm-dialog` (no inputs). Renders the modal when `request()` is non-null; confirm/cancel/ESC/backdrop resolve via the service.

- [ ] **Step 1: Write the failing test** — create `confirm-dialog.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ConfirmDialogComponent } from './confirm-dialog.component';
import { ConfirmDialogService } from './confirm-dialog.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

describe('ConfirmDialogComponent', () => {
  let fixture: ComponentFixture<ConfirmDialogComponent>;
  let el: HTMLElement;
  let svc: ConfirmDialogService;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ConfirmDialogComponent] });
    svc = TestBed.inject(ConfirmDialogService);
    fixture = TestBed.createComponent(ConfirmDialogComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });
  afterEach(() => detachFromBody(el));

  it('renders nothing when no request is active', () => {
    expect(el.querySelector('.cd-panel')).toBeNull();
  });

  it('renders an accessible modal when a request is set', () => {
    svc.confirm({ title: 'Delete candidate?', body: 'This cannot be undone.' });
    fixture.detectChanges();
    const panel = el.querySelector('.cd-panel');
    expect(panel?.getAttribute('role')).toBe('dialog');
    expect(panel?.getAttribute('aria-modal')).toBe('true');
    expect(el.querySelector('.cd-title')?.textContent).toContain('Delete candidate?');
  });

  it('confirm button resolves the request true', async () => {
    const p = svc.confirm({ title: 'Delete?' });
    fixture.detectChanges();
    (el.querySelector('.cd-confirm') as HTMLButtonElement).click();
    await expectAsync(p).toBeResolvedTo(true);
  });

  it('cancel button resolves the request false', async () => {
    const p = svc.confirm({ title: 'Delete?' });
    fixture.detectChanges();
    (el.querySelector('.cd-cancel') as HTMLButtonElement).click();
    await expectAsync(p).toBeResolvedTo(false);
  });

  it('has zero axe violations while open', async () => {
    svc.confirm({ title: 'Delete?', body: 'Sure?' });
    fixture.detectChanges();
    const v = await axeViolations(el);
    expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
  });
});
```

- [ ] **Step 2: Run to verify FAIL** — `ng test --watch=false --include='**/confirm-dialog.component.spec.ts'` → FAIL.

- [ ] **Step 3: Implement** — create `confirm-dialog.component.ts`:

```ts
import { Component, HostListener, inject } from '@angular/core';
import { A11yModule } from '@angular/cdk/a11y';
import { ConfirmDialogService } from './confirm-dialog.service';

/** Single hand-rolled modal (no CDK Overlay — the app imports no overlay CSS). Focus is trapped via
 *  cdkTrapFocus with auto-capture; closing the @if destroys the trap and restores focus. ESC and
 *  backdrop-click cancel. Mounted once in AppComponent; driven entirely by ConfirmDialogService. */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [A11yModule],
  template: `
    @if (req(); as r) {
      <div class="cd-backdrop" (click)="cancel()">
        <div class="cd-panel" role="dialog" aria-modal="true"
             [attr.aria-labelledby]="titleId" [attr.aria-describedby]="r.body ? bodyId : null"
             cdkTrapFocus [cdkTrapFocusAutoCapture]="true" (click)="$event.stopPropagation()">
          <h2 class="cd-title" [id]="titleId">{{ r.title }}</h2>
          @if (r.body) { <p class="cd-body" [id]="bodyId">{{ r.body }}</p> }
          <div class="cd-actions">
            <button type="button" class="cd-cancel btn btn--ghost" (click)="cancel()">{{ r.cancelLabel || defaultCancel }}</button>
            <button type="button" class="cd-confirm btn" [class.btn--danger]="r.danger" [class.btn--primary]="!r.danger"
                    (click)="confirm()">{{ r.confirmLabel || defaultConfirm }}</button>
          </div>
        </div>
      </div>
    }
  `,
  styles: [`
    .cd-backdrop {
      position: fixed; inset: 0; z-index: calc(var(--z-overlay) + 10);
      display: grid; place-items: center; padding: var(--space-4);
      background: rgb(40 33 24 / 0.45);
    }
    .cd-panel {
      width: min(30rem, 100%); background: var(--surface-raised);
      border: 1px solid var(--line); border-radius: var(--radius-lg);
      box-shadow: var(--shadow-lg); padding: var(--space-6);
    }
    .cd-title { margin: 0 0 var(--space-2); font-size: var(--step-1); }
    .cd-body { margin: 0 0 var(--space-5); color: var(--ink-faint); }
    .cd-actions { display: flex; justify-content: flex-end; gap: var(--space-2); }
    @media (prefers-reduced-motion: no-preference) {
      .cd-panel { animation: cad-cd-in 0.16s ease; }
      @keyframes cad-cd-in { from { opacity: 0; transform: translateY(0.5rem) scale(0.98); } }
    }
  `]
})
export class ConfirmDialogComponent {
  private readonly svc = inject(ConfirmDialogService);
  readonly req = this.svc.request;
  readonly titleId = 'cd-title';
  readonly bodyId = 'cd-body';
  readonly defaultConfirm = $localize`:@@confirm.default.confirm:Confirm`;
  readonly defaultCancel = $localize`:@@confirm.default.cancel:Cancel`;

  confirm(): void { this.svc.respond(true); }
  cancel(): void { this.svc.respond(false); }

  @HostListener('document:keydown.escape')
  onEsc(): void { if (this.req()) this.cancel(); }
}
```

- [ ] **Step 4: Run to verify PASS** — 5/5 pass.
- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/ui/confirm-dialog.component.ts frontend/src/app/shared/ui/confirm-dialog.component.spec.ts
git commit -m "feat(ui): add ConfirmDialogComponent (workbench overhaul phase 3a)"
```

---

## Task 5: Mount both hosts in AppComponent + suite gate

**Files:** Modify `frontend/src/app/app.component.ts` and `frontend/src/app/app.component.html`; touch `app.component.spec.ts` only if it breaks.

**Interfaces:** Consumes `ToastHostComponent`, `ConfirmDialogComponent`. Produces the global mount so Phase 3b callers just inject the services.

- [ ] **Step 1: Add the imports** — in `app.component.ts`, add to the TS imports and the `@Component imports: [...]` array:

```ts
import { ToastHostComponent } from './shared/ui/toast-host.component';
import { ConfirmDialogComponent } from './shared/ui/confirm-dialog.component';
```
and append `ToastHostComponent, ConfirmDialogComponent` to `imports: [RouterOutlet, TopBarComponent]`.

- [ ] **Step 2: Mount the hosts globally** — in `app.component.html`, after the existing `#main-content` block, add:

```html
<app-toast-host />
<app-confirm-dialog />
```
(Outside the `@if (showChrome())` block — feedback/dialogs are available on every route.)

- [ ] **Step 3: Run the app spec** — `ng test --watch=false --include='**/app.component.spec.ts'`. If it fails only because the new child components need no extra providers (they use root services), it should pass as-is. If the existing spec does a strict shallow assertion that now breaks, update it minimally to accommodate the two new host tags (do not remove existing assertions).

- [ ] **Step 4: Full-suite gate** — `ng test --watch=false` → all green (Phase 2's 412 + the new Phase 3a specs).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/app.component.ts frontend/src/app/app.component.html
# include app.component.spec.ts in the add ONLY if you modified it
git commit -m "feat(ui): mount toast host + confirm dialog globally (workbench overhaul phase 3a)"
```

---

## Self-Review (completed at authoring time)

- **Spec coverage:** Phase 3a of the design = build the toast + confirm-dialog primitives and mount them. Tasks 1–4 build the four pieces; Task 5 mounts + gates. Phase 3b (wiring into destructive actions + success toasts) is a separate plan.
- **Placeholder scan:** none — full service/component/spec code in every task; the only per-file unknown (whether `app.component.spec.ts` needs a tweak) is an explicit conditional step, not a vague directive.
- **Consistency:** `ToastService` (`toasts`/`show`/`success`/`error`/`info`/`dismiss`) and `ConfirmDialogService` (`request`/`confirm`/`respond`) signatures match between service, component, and specs. Selectors `app-toast-host`/`app-confirm-dialog`. Tokens (`--ok`/`--danger`/`--accent`/`--surface-raised`/`--shadow-md`/`--shadow-lg`/`--radius-sm`/`--radius-lg`/`--z-overlay`), `.btn--danger`/`.btn--ghost`/`.btn--sm`, and the axe helper path `../../../testing/axe` are all verified against the codebase. CDK a11y (`A11yModule`, `cdkTrapFocus`, `cdkTrapFocusAutoCapture`) is in the installed `@angular/cdk` 17.3.10.
- **Scope guard:** this phase builds + mounts only; it wires nothing into existing screens (that's Phase 3b). No CDK Overlay/Dialog, no overlay CSS import, no new dependency.
