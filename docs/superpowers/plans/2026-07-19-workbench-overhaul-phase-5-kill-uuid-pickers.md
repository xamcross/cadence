# Workbench Overhaul — Phase 5: Kill-UUID Pickers — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 6 raw-UUID text fields in the recruiter workbench (candidate/template/member/requisition ids) with a searchable combobox picker, so operators pick by name instead of pasting ids.

**Architecture (settled by discovery):** **Zero new backend endpoints.** Candidate PII is AES-GCM encrypted at rest with random IVs and there is no name-hash/text index, so a server-side name typeahead is architecturally infeasible (a separate PII-search-index project, out of scope). Every picker instead loads an **existing, already-RBAC-scoped list endpoint once** and filters client-side: candidate → `GET /api/internal/pipeline` (returns decrypted `name`+`candidateId`, scopes Hiring Managers to assigned candidates server-side, capped at 1000); template → `/interview-templates`; member → `/members`; requisition → `/requisitions`. A single new presentational `SearchPickerComponent` (accessible ARIA combobox, no CDK Overlay) is reused for all four. **No backend, no new dependency.**

**Tech Stack:** Angular 17.3 standalone, Jasmine + TestBed (EdgeHeadless), axe via `frontend/src/testing/axe.ts`.

## Global Constraints
- Standalone components only. The picker lives in `frontend/src/app/shared/ui/search-picker.component.ts`.
- **No backend change.** Reuse existing list services; do NOT add server endpoints or send candidate PII anywhere new (the picker filters names already returned to the authorized role).
- Design tokens only; reuse `.input`, `.muted`, `.btn`. Spec axe-helper import (from `shared/ui/`): `../../../testing/axe`.
- **i18n:** the only in-kit string is the "No matches" empty text (`@@picker.noMatch`); all labels/placeholders are consumer-supplied via inputs (`i18n-…` at the call site).
- **a11y:** ARIA combobox pattern — input `role="combobox"` + `aria-expanded` + `aria-autocomplete="list"` + `aria-controls`/`aria-activedescendant` (only while open), listbox `role="listbox"` with `role="option"`/`aria-selected`; ArrowUp/Down + Enter + Escape keyboard. Every new/changed spec asserts axe 0.
- **Testing** from `frontend/`: `ng test --watch=false --include='**/<spec>'`.
- **Git:** stage only each task's files (never `-A`; leave `CLAUDE.md`/`environment.prod.ts`); commit per task; trailer convention; no push.

## File Structure
- Create `frontend/src/app/shared/ui/search-picker.component.ts` (+ spec) — `app-search-picker`.
- Modify `scheduling.component.ts` (candidate + template pickers) — reuse pipeline + interview-template list services.
- Modify `email-templates.component.ts` (candidate picker) — reuse pipeline list service.
- Modify `requisitions.component.ts` (member + candidate + requisition pickers) — reuse member + pipeline list services + its own already-loaded requisitions list.

---

## Task 1: SearchPickerComponent

**Files:** Create `frontend/src/app/shared/ui/search-picker.component.ts` (+ `.spec.ts`).

**Interfaces:** Produces `PickerOption { id: string; label: string; hint?: string }` and `SearchPickerComponent`, selector `app-search-picker`. Inputs: `options` (required, `readonly PickerOption[]`), `value` (string|null, for parent reset), `placeholder`, `label`, `disabled`. Output: `valueChange: EventEmitter<string|null>`. Consumers use `[options] [value] (valueChange)`.

- [ ] **Step 1: Write the failing test** — create `search-picker.component.spec.ts`:

```ts
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PickerOption, SearchPickerComponent } from './search-picker.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

@Component({
  standalone: true,
  imports: [SearchPickerComponent],
  template: `<app-search-picker [options]="opts" label="Candidate" placeholder="Search…"
              [value]="picked" (valueChange)="picked = $event"></app-search-picker>`
})
class HostComponent {
  opts: PickerOption[] = [
    { id: 'c1', label: 'Dana Okafor', hint: 'Technical' },
    { id: 'c2', label: 'Marek Novak', hint: 'Screen' },
    { id: 'c3', label: 'Priya Shah' }
  ];
  picked: string | null = null;
}

describe('SearchPickerComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  let el: HTMLElement;

  const input = () => el.querySelector('.picker__input') as HTMLInputElement;
  const type = (v: string) => { input().value = v; input().dispatchEvent(new Event('input')); fixture.detectChanges(); };

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });
  afterEach(() => detachFromBody(el));

  it('renders a combobox input', () => {
    expect(input().getAttribute('role')).toBe('combobox');
    expect(input().getAttribute('aria-expanded')).toBe('false');
  });

  it('filters options by case-insensitive substring as you type', () => {
    input().dispatchEvent(new Event('focus'));
    type('nov');
    const opts = el.querySelectorAll('.picker__opt');
    expect(opts.length).toBe(1);
    expect(opts[0].textContent).toContain('Marek Novak');
  });

  it('selecting an option emits its id and shows its label', () => {
    input().dispatchEvent(new Event('focus'));
    type('dana');
    (el.querySelector('.picker__opt') as HTMLElement).click();
    fixture.detectChanges();
    expect(host.picked).toBe('c1');
    expect(input().value).toBe('Dana Okafor');
    expect(el.querySelector('.picker__list')).toBeNull(); // closed after pick
  });

  it('keyboard: ArrowDown + Enter selects the active option', () => {
    input().dispatchEvent(new Event('focus'));
    type('a'); // matches all three
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();
    expect(host.picked).not.toBeNull();
  });

  it('editing after a selection clears the emitted value', () => {
    input().dispatchEvent(new Event('focus'));
    type('dana');
    (el.querySelector('.picker__opt') as HTMLElement).click();
    fixture.detectChanges();
    expect(host.picked).toBe('c1');
    type('dan');
    expect(host.picked).toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations while open', async () => {
    input().dispatchEvent(new Event('focus'));
    type('a');
    const v = await axeViolations(el);
    expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
  });
});
```

- [ ] **Step 2: Run to verify FAIL** — `ng test --watch=false --include='**/search-picker.component.spec.ts'` → FAIL.

- [ ] **Step 3: Implement** — create `search-picker.component.ts`:

```ts
import { Component, EventEmitter, Input, Output, computed, signal } from '@angular/core';

export interface PickerOption { readonly id: string; readonly label: string; readonly hint?: string; }

let pickerSeq = 0;

/** Accessible search combobox (workbench overhaul phase 5). Presentational: the parent loads `options`
 *  (id + display label) from an existing RBAC-scoped list endpoint and filters happen client-side. No
 *  CDK Overlay. Emits the selected id via valueChange; editing after a pick clears the value. */
@Component({
  selector: 'app-search-picker',
  standalone: true,
  template: `
    <div class="picker">
      <input #input type="text" class="input picker__input" role="combobox"
             [attr.aria-expanded]="open()" aria-autocomplete="list"
             [attr.aria-controls]="open() ? listId : null"
             [attr.aria-activedescendant]="activeDescendant()"
             [attr.aria-label]="label || null" [placeholder]="placeholder" [disabled]="disabled"
             [value]="text()"
             (input)="onInput($any($event.target).value)"
             (focus)="open.set(true)" (blur)="open.set(false)" (keydown)="onKeydown($event)" />
      @if (open() && filtered().length) {
        <ul class="picker__list" [id]="listId" role="listbox">
          @for (opt of filtered(); track opt.id; let i = $index) {
            <li class="picker__opt" [id]="listId + '-' + i" role="option"
                [class.picker__opt--active]="i === activeIndex()"
                [attr.aria-selected]="opt.id === selectedId()"
                (mousedown)="$event.preventDefault()" (click)="select(opt)">
              <span class="picker__opt-label">{{ opt.label }}</span>
              @if (opt.hint) { <span class="picker__opt-hint muted"> — {{ opt.hint }}</span> }
            </li>
          }
        </ul>
      } @else if (open() && query()) {
        <div class="picker__empty muted" role="status" i18n="@@picker.noMatch">No matches</div>
      }
    </div>
  `,
  styles: [`
    .picker { position: relative; }
    .picker__input { width: 100%; }
    .picker__list {
      position: absolute; z-index: var(--z-overlay); inset-inline: 0; margin: var(--space-1) 0 0; padding: var(--space-1);
      list-style: none; max-height: 16rem; overflow-y: auto;
      background: var(--surface-raised); border: 1px solid var(--line-strong);
      border-radius: var(--radius-sm); box-shadow: var(--shadow-md);
    }
    .picker__opt { padding: var(--space-2) var(--space-3); border-radius: var(--radius-sm); cursor: pointer; font-size: var(--step--1); }
    .picker__opt--active, .picker__opt:hover { background: var(--accent-wash); color: var(--accent-ink); }
    .picker__opt-hint { font-size: var(--step--1); }
    .picker__empty { position: absolute; z-index: var(--z-overlay); inset-inline: 0; margin-top: var(--space-1);
      padding: var(--space-3); background: var(--surface-raised); border: 1px solid var(--line); border-radius: var(--radius-sm); font-size: var(--step--1); }
  `]
})
export class SearchPickerComponent {
  @Input({ required: true }) options!: readonly PickerOption[];
  @Input() placeholder = '';
  @Input() label = '';
  @Input() disabled = false;
  /** Parent-driven reset: setting value to null/'' clears the display (used after a successful action). */
  @Input() set value(v: string | null) { if (!v) { this._selectedId.set(null); this._text.set(''); } }
  @Output() valueChange = new EventEmitter<string | null>();

  readonly listId = `picker-${++pickerSeq}`;

  private readonly _text = signal('');
  private readonly _selectedId = signal<string | null>(null);
  readonly open = signal(false);
  readonly activeIndex = signal(0);

  readonly text = this._text.asReadonly();
  readonly selectedId = this._selectedId.asReadonly();
  readonly query = computed(() => this._text().trim());
  readonly filtered = computed<readonly PickerOption[]>(() => {
    const q = this.query().toLowerCase();
    const list = q ? this.options.filter((o) => o.label.toLowerCase().includes(q)) : this.options;
    return list.slice(0, 50);
  });
  readonly activeDescendant = computed(() =>
    this.open() && this.filtered().length ? `${this.listId}-${this.activeIndex()}` : null);

  onInput(v: string): void {
    this._text.set(v);
    this.open.set(true);
    this.activeIndex.set(0);
    if (this._selectedId()) { this._selectedId.set(null); this.valueChange.emit(null); }
  }
  select(opt: PickerOption): void {
    this._selectedId.set(opt.id);
    this._text.set(opt.label);
    this.open.set(false);
    this.valueChange.emit(opt.id);
  }
  onKeydown(e: KeyboardEvent): void {
    if (e.key === 'ArrowDown') { e.preventDefault(); this.open.set(true); this.move(1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); this.move(-1); }
    else if (e.key === 'Enter') {
      const opt = this.filtered()[this.activeIndex()];
      if (this.open() && opt) { e.preventDefault(); this.select(opt); }
    } else if (e.key === 'Escape') { this.open.set(false); }
  }
  private move(delta: number): void {
    const n = this.filtered().length; if (!n) return;
    this.activeIndex.set((this.activeIndex() + delta + n) % n);
  }
}
```

- [ ] **Step 4: Run to verify PASS** — 6/6 pass.
- [ ] **Step 5: Commit**
```bash
git add frontend/src/app/shared/ui/search-picker.component.ts frontend/src/app/shared/ui/search-picker.component.spec.ts
git commit -m "feat(ui): add accessible SearchPickerComponent (workbench overhaul phase 5)"
```

---

## Adoption pattern (Tasks 2–4)

For each raw-id `<input [(ngModel)]="xId">`:
1. Load the option list once (in `ngOnInit`) from the existing list service — READ the service for its method + response DTO; map to `PickerOption[]` in a `signal`. Candidate options come from the pipeline list service (`status=ACTIVE`, a large `size` up to 1000) mapped `{ id: row.candidateId, label: row.name, hint: row.stage }`.
2. Replace the input with:
```html
<app-search-picker [options]="candidateOpts()" [value]="candidateId"
  (valueChange)="candidateId = $event ?? ''"
  label="Candidate" i18n-label="@@…" placeholder="Search candidates…" i18n-placeholder="@@…"></app-search-picker>
```
   Keep the existing field (`candidateId`, etc.) — the picker just sets it; the rest of the handlers are unchanged. Add `SearchPickerComponent` to the component `imports`.
3. Extend the spec: assert an `app-search-picker` renders for each converted field (provide the list service via the DI-stub convention so options load), and that selecting an option sets the field / enables the action. Keep axe assertions.

Load-service reuse (read each for exact method names/DTOs):
- **Candidates:** the Pipeline list service (`features/pipeline/pipeline.service.ts`) — `GET /api/internal/pipeline`.
- **Interview templates:** `features/interview-templates/interview-templates.service.ts`.
- **Members:** the members list service used by `features/admin/members/` (`MembersService`).
- **Requisitions:** already loaded on the requisitions screen (`requisitions()` signal) — map directly, no new fetch.

### Task 2 — scheduling (`features/scheduling/scheduling.component.ts`)
- [ ] Replace `candidateId` input (row ~37) with a **candidate** picker; replace `templateId` input (row ~40) with a **template** picker. Inject the pipeline + interview-template list services, load both in `ngOnInit` into `candidateOpts`/`templateOpts` signals. Extend spec. Commit `feat(ui): candidate + template pickers on scheduling (workbench overhaul phase 5)`.

### Task 3 — email-templates (`features/email-templates/email-templates.component.ts`)
- [ ] Replace `sendCandidateId` input (row ~99) with a **candidate** picker; load candidate options from the pipeline list service in `ngOnInit`. Extend spec. Commit `feat(ui): candidate picker on email-templates (workbench overhaul phase 5)`.

### Task 4 — requisitions (`features/admin/requisitions/requisitions.component.ts`)
- [ ] Replace `assignMemberId[r.id]` (row ~71) with a **member** picker (per row — `[value]="assignMemberId[r.id]" (valueChange)="assignMemberId[r.id] = $event ?? ''"`); `linkCandidateId` (row ~83) with a **candidate** picker; `linkRequisitionId` (row ~84) with a **requisition** picker (options mapped from the already-loaded `requisitions()`; `{ id, label: title, hint: status }`). Inject the members + pipeline list services; load member + candidate options in `ngOnInit`. Extend spec. Commit `feat(ui): member/candidate/requisition pickers on requisitions (workbench overhaul phase 5)`.

---

## Verification (after all tasks)
- [ ] `ng test --watch=false` — full suite green.
- [ ] `git status --short` — only touched files committed; the 2 SEO files still unstaged.
- [ ] Grep confirms the 6 raw-id `<input>`s are gone: no remaining `ngModel="candidateId"`/`templateId`/`assignMemberId`/`linkCandidateId`/`linkRequisitionId`/`sendCandidateId` bound to a bare text `<input>`.

## Self-Review (completed at authoring time)
- **Coverage vs design:** Phase 5 = kill-UUID pickers. Task 1 builds the combobox; Tasks 2–4 convert all 6 fields (3 candidate, 1 template, 1 member, 1 requisition) discovery identified. No backend endpoint (encryption blocks server-side name search — documented; reuse of the RBAC-scoped pipeline list preserves HM scoping).
- **Placeholder scan:** the picker code + spec are complete; adoption tasks name each field, its picker type, its data source, and the exact call-site binding, with the exact per-service method left to read (an environment fact, not a vague directive).
- **Consistency:** `PickerOption`/`SearchPickerComponent` API (`options`/`value`/`valueChange`) matches between component, spec, and adoption bindings. Tokens + `.input`/`.muted` classes verified. axe-helper depth `../../../testing/axe`.
- **Scope guard:** frontend-only; no new backend endpoint, no new dependency, no PII sent anywhere new (client-side filter over names already returned to the authorized role); candidate options inherit the pipeline endpoint's server-side HM scoping.
