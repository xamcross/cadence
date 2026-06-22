import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../../core/auth/auth.service';

/** Request a password-reset link. Always shows the same confirmation (enumeration-safe, FR-032). */
@Component({
  selector: 'app-reset-request',
  standalone: true,
  imports: [FormsModule],
  template: `
    <main class="auth-shell">
      <section class="auth-card reveal">
        <p class="eyebrow eyebrow--rule" i18n="@@reset.request.eyebrow">Account recovery</p>
        <h1 i18n="@@reset.request.title">Reset your password</h1>
        @if (done()) {
          <p class="notice" role="status" i18n="@@reset.request.sent">
            If an account exists for that email, a reset link has been sent.
          </p>
        } @else {
          <form (ngSubmit)="submit()" novalidate>
            <div class="field">
              <label for="workspaceId" i18n="@@reset.request.workspace">Workspace ID</label>
              <input class="input" id="workspaceId" name="workspaceId" [(ngModel)]="workspaceId" required />
            </div>
            <div class="field">
              <label for="email" i18n="@@reset.request.email">Email</label>
              <input class="input" id="email" name="email" type="email" [(ngModel)]="email" required autocomplete="username" />
            </div>
            <button type="submit" class="btn btn--primary block" [disabled]="submitting()" i18n="@@reset.request.submit">Send reset link</button>
          </form>
        }
      </section>
    </main>
  `,
  styles: [`
    .auth-card > h1 { margin-bottom: var(--space-5); }
    .block { width: 100%; }
    .notice {
      margin: 0; padding: var(--space-4);
      background: var(--accent-wash); color: var(--accent-ink);
      border-radius: var(--radius-sm); line-height: 1.5;
    }
  `]
})
export class ResetRequestComponent {
  private readonly auth = inject(AuthService);
  workspaceId = '';
  email = '';
  readonly submitting = signal(false);
  readonly done = signal(false);

  submit(): void {
    this.submitting.set(true);
    this.auth.requestReset(this.workspaceId, this.email).subscribe({
      // Same outcome whether or not the account exists.
      next: () => this.done.set(true),
      error: () => this.done.set(true)
    });
  }
}
