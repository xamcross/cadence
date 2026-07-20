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
