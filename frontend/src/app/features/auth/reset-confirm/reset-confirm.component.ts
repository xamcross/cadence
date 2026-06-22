import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';

/** Set a new password from a reset link. Renders an accessible invalid-link state (FE-9). */
@Component({
  selector: 'app-reset-confirm',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <main class="auth-shell">
      <section class="auth-card reveal">
        <p class="eyebrow eyebrow--rule" i18n="@@reset.confirm.eyebrow">Account recovery</p>
        <h1 i18n="@@reset.confirm.title">Choose a new password</h1>

        @if (!token) {
          <p class="alert" role="alert" i18n="@@reset.confirm.noToken">This reset link is invalid.</p>
          <a class="btn btn--outline block" routerLink="/reset" i18n="@@reset.confirm.requestNew">Request a new link</a>
        } @else if (done()) {
          <p class="notice" role="status" i18n="@@reset.confirm.done">Your password has been updated. You can now sign in.</p>
          <a class="btn btn--primary block" routerLink="/login" i18n="@@reset.confirm.toLogin">Go to sign in</a>
        } @else {
          @if (error()) { <p class="alert" role="alert">{{ error() }}</p> }
          <form (ngSubmit)="submit()" novalidate>
            <div class="field">
              <label for="newPassword" i18n="@@reset.confirm.password">New password (min 8 characters)</label>
              <input class="input" id="newPassword" name="newPassword" type="password" [(ngModel)]="newPassword"
                     required minlength="8" autocomplete="new-password" />
            </div>
            <button type="submit" class="btn btn--primary block" [disabled]="submitting()" i18n="@@reset.confirm.submit">Update password</button>
          </form>
        }
      </section>
    </main>
  `,
  styles: [`
    .auth-card > h1 { margin-bottom: var(--space-5); }
    .block { width: 100%; }
    .alert {
      margin: 0 0 var(--space-4); padding: var(--space-3) var(--space-4);
      background: var(--danger-wash); color: var(--danger);
      border-radius: var(--radius-sm); font-weight: 600;
    }
    .notice {
      margin: 0 0 var(--space-4); padding: var(--space-4);
      background: var(--ok-wash); color: var(--ok);
      border-radius: var(--radius-sm);
    }
  `]
})
export class ResetConfirmComponent {
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly token = this.route.snapshot.queryParamMap.get('token') ?? '';
  newPassword = '';
  readonly submitting = signal(false);
  readonly done = signal(false);
  readonly error = signal<string | null>(null);

  submit(): void {
    this.error.set(null);
    this.submitting.set(true);
    this.auth.confirmReset(this.token, this.newPassword).subscribe({
      next: () => this.done.set(true),
      error: (e) => {
        this.submitting.set(false);
        this.error.set(
          e?.status === 410
            ? $localize`:@@reset.confirm.invalid:This link has expired or was already used. Request a new one.`
            : $localize`:@@reset.confirm.weak:Password must be at least 8 characters.`
        );
      }
    });
  }
}
