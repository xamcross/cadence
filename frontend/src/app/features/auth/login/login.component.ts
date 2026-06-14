import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';

/** Sign-in page: SSO is the primary action; email+password is the secondary fallback (FR-002). */
@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <main class="auth-page">
      <h1 i18n="@@login.title">Sign in to Cadence</h1>

      @if (banner()) {
        <p class="error" role="alert">{{ banner() }}</p>
      }

      <button type="button" class="primary" (click)="auth.startSso()" i18n="@@login.sso">
        Sign in with SSO
      </button>

      <details class="fallback">
        <summary i18n="@@login.fallback.toggle">Use email and password instead</summary>
        <form (ngSubmit)="submit()" #f="ngForm" novalidate>
          <label for="workspaceId" i18n="@@login.workspace">Workspace ID</label>
          <input id="workspaceId" name="workspaceId" [(ngModel)]="workspaceId" required autocomplete="organization" />

          <label for="email" i18n="@@login.email">Email</label>
          <input id="email" name="email" type="email" [(ngModel)]="email" required autocomplete="username" />

          <label for="password" i18n="@@login.password">Password</label>
          <input id="password" name="password" type="password" [(ngModel)]="password" required
                 autocomplete="current-password" />

          <button type="submit" [disabled]="submitting()" i18n="@@login.submit">Sign in</button>
        </form>
      </details>

      <a routerLink="/reset" i18n="@@login.forgot">Forgot your password?</a>
    </main>
  `,
  styles: [`
    .auth-page { max-width: 24rem; margin: 2rem auto; padding: 1rem; display: flex; flex-direction: column; gap: 0.75rem; }
    button.primary { padding: 0.75rem; font-weight: 600; min-height: 44px; }
    input { width: 100%; padding: 0.5rem; min-height: 44px; box-sizing: border-box; }
    .error { color: #b00020; }
  `]
})
export class LoginComponent {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  workspaceId = '';
  email = '';
  password = '';
  readonly submitting = signal(false);
  readonly banner = signal<string | null>(null);

  constructor() {
    const error = this.route.snapshot.queryParamMap.get('error');
    if (error === 'no_access') {
      this.banner.set($localize`:@@login.error.noAccess:You don't have access to this workspace. Contact your administrator.`);
    } else if (error === 'idp_unavailable') {
      this.banner.set($localize`:@@login.error.idp:Sign-in is temporarily unavailable. Please try again.`);
    }
  }

  submit(): void {
    this.banner.set(null);
    this.submitting.set(true);
    this.auth.loginWithPassword(this.workspaceId, this.email, this.password).subscribe({
      next: () => this.router.navigate(['/']),
      error: () => {
        this.submitting.set(false);
        this.banner.set($localize`:@@login.error.invalid:Invalid email or password.`);
      }
    });
  }
}
