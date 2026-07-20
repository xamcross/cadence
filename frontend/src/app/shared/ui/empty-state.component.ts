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
