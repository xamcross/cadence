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
