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
