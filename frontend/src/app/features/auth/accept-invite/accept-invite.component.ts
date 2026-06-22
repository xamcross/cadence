import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { InvitationView } from '../../../core/auth/auth.models';

/** Accept an invitation: pre-validate the token, branch on needsPassword, handle invalid link (FE-9). */
@Component({
  selector: 'app-accept-invite',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <main class="auth-shell">
      <section class="auth-card reveal">
        <p class="eyebrow eyebrow--rule" i18n="@@invite.eyebrow">You're invited</p>
        <h1 i18n="@@invite.title">Join your workspace</h1>

        @if (loading()) {
          <p class="notice" role="status" i18n="@@invite.loading">Checking your invitation…</p>
        } @else if (invalid()) {
          <p class="alert" role="alert" i18n="@@invite.invalid">
            This invitation is no longer valid. Ask your administrator to resend it.
          </p>
        } @else if (done()) {
          <p class="notice notice--ok" role="status" i18n="@@invite.done">Your account is ready. Redirecting…</p>
        } @else if (invite()) {
          <p class="invite-for" i18n="@@invite.for">Invitation for {{ invite()!.email }} as {{ invite()!.role }}.</p>
          @if (error()) { <p class="alert" role="alert">{{ error() }}</p> }
          @if (invite()!.needsPassword) {
            <form (ngSubmit)="submit()" novalidate>
              <div class="field">
                <label for="password" i18n="@@invite.password">Choose a password (min 8 characters)</label>
                <input class="input" id="password" name="password" type="password" [(ngModel)]="password"
                       required minlength="8" autocomplete="new-password" />
              </div>
              <button type="submit" class="btn btn--primary block" [disabled]="submitting()" i18n="@@invite.submit">Create account</button>
            </form>
          } @else {
            <button type="button" class="btn btn--primary block" (click)="auth.startSso()" i18n="@@invite.sso">Continue with SSO</button>
          }
        }
      </section>
    </main>
  `,
  styles: [`
    .auth-card > h1 { margin-bottom: var(--space-5); }
    .block { width: 100%; }
    .invite-for { color: var(--ink-muted); margin-bottom: var(--space-5); }
    .alert {
      margin: 0 0 var(--space-4); padding: var(--space-3) var(--space-4);
      background: var(--danger-wash); color: var(--danger);
      border-radius: var(--radius-sm); font-weight: 600;
    }
    .notice {
      margin: 0; padding: var(--space-4);
      background: var(--accent-wash); color: var(--accent-ink);
      border-radius: var(--radius-sm);
    }
    .notice--ok { background: var(--ok-wash); color: var(--ok); }
  `]
})
export class AcceptInviteComponent {
  readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  private readonly token = this.route.snapshot.queryParamMap.get('token') ?? '';
  password = '';
  readonly loading = signal(true);
  readonly invalid = signal(false);
  readonly done = signal(false);
  readonly submitting = signal(false);
  readonly error = signal<string | null>(null);
  readonly invite = signal<InvitationView | null>(null);

  constructor() {
    if (!this.token) {
      this.loading.set(false);
      this.invalid.set(true);
    } else {
      this.auth.validateInvite(this.token).subscribe({
        next: (v) => {
          this.invite.set(v);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.invalid.set(true);
        }
      });
    }
  }

  submit(): void {
    this.error.set(null);
    this.submitting.set(true);
    this.auth.acceptInvite(this.token, this.password).subscribe({
      next: () => {
        this.done.set(true);
        this.router.navigate(['/app']);
      },
      error: (e) => {
        this.submitting.set(false);
        if (e?.status === 410) {
          this.invalid.set(true);
        } else {
          this.error.set($localize`:@@invite.weak:Password must be at least 8 characters.`);
        }
      }
    });
  }
}
