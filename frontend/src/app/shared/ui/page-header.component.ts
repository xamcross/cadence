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
