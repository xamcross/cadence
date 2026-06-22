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
    <main class="auth-page">
      <h1 i18n="@@invite.title">Join your workspace</h1>

      @if (loading()) {
        <p role="status" i18n="@@invite.loading">Checking your invitation…</p>
      } @else if (invalid()) {
        <p class="error" role="alert" i18n="@@invite.invalid">
          This invitation is no longer valid. Ask your administrator to resend it.
        </p>
      } @else if (done()) {
        <p role="status" i18n="@@invite.done">Your account is ready. Redirecting…</p>
      } @else if (invite()) {
        <p i18n="@@invite.for">Invitation for {{ invite()!.email }} as {{ invite()!.role }}.</p>
        @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
        @if (invite()!.needsPassword) {
          <form (ngSubmit)="submit()" novalidate>
            <label for="password" i18n="@@invite.password">Choose a password (min 8 characters)</label>
            <input id="password" name="password" type="password" [(ngModel)]="password"
                   required minlength="8" autocomplete="new-password" />
            <button type="submit" [disabled]="submitting()" i18n="@@invite.submit">Create account</button>
          </form>
        } @else {
          <button type="button" (click)="auth.startSso()" i18n="@@invite.sso">Continue with SSO</button>
        }
      }
    </main>
  `,
  styles: [`
    .auth-page { max-width: 24rem; margin: 2rem auto; padding: 1rem; display: flex; flex-direction: column; gap: 0.75rem; }
    input { width: 100%; padding: 0.5rem; min-height: 44px; box-sizing: border-box; }
    button { min-height: 44px; }
    .error { color: var(--danger); }
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
