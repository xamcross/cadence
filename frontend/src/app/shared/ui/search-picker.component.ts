import { Component, EventEmitter, Input, Output, computed, input, signal } from '@angular/core';

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
             [attr.aria-expanded]="open() && filtered().length > 0" aria-autocomplete="list"
             [attr.aria-controls]="(open() && filtered().length) ? listId : null"
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
  /** Signal input so `filtered` stays reactive to option-list reassignments (not just query changes). */
  readonly options = input.required<readonly PickerOption[]>();
  @Input() placeholder = '';
  @Input() label = '';
  @Input() disabled = false;
  /**
   * Parent-driven reset: clearing the value clears the display, but ONLY when a selection is
   * committed. During a mid-edit, `onInput` has already nulled `_selectedId` and emitted null, so
   * the parent's `''` echo must be ignored here or it would wipe the text the user just typed.
   */
  @Input() set value(v: string | null) { if (!v && this._selectedId()) { this._selectedId.set(null); this._text.set(''); } }
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
    const opts = this.options();
    const list = q ? opts.filter((o) => o.label.toLowerCase().includes(q)) : opts;
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
