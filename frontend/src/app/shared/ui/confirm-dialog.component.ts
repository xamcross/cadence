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
