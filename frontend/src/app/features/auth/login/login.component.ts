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
    <main class="auth-shell">
      <section class="auth-card reveal">
        <p class="eyebrow eyebrow--rule" i18n="@@login.eyebrow">Cadence</p>
        <h1 i18n="@@login.title">Sign in to Cadence</h1>
        <p class="muted lede" i18n="@@login.lede">Welcome back. Pick up right where your hiring left off.</p>

        @if (banner()) {
          <p class="alert" role="alert">{{ banner() }}</p>
        }

        <button type="button" class="btn btn--primary block" (click)="auth.startSso()" i18n="@@login.sso">
          Sign in with SSO
        </button>

        <details class="fallback">
          <summary i18n="@@login.fallback.toggle">Use email and password instead</summary>
          <form (ngSubmit)="submit()" #f="ngForm" novalidate>
            <div class="field">
              <label for="workspaceId" i18n="@@login.workspace">Workspace ID</label>
              <input class="input" id="workspaceId" name="workspaceId" [(ngModel)]="workspaceId" required autocomplete="organization" />
            </div>
            <div class="field">
              <label for="email" i18n="@@login.email">Email</label>
              <input class="input" id="email" name="email" type="email" [(ngModel)]="email" required autocomplete="username" />
            </div>
            <div class="field">
              <label for="password" i18n="@@login.password">Password</label>
              <input class="input" id="password" name="password" type="password" [(ngModel)]="password" required
                     autocomplete="current-password" />
            </div>
            <button type="submit" class="btn btn--outline block" [disabled]="submitting()" i18n="@@login.submit">Sign in</button>
          </form>
        </details>

        <a class="forgot" routerLink="/reset" i18n="@@login.forgot">Forgot your password?</a>
      </section>
    </main>
  `,
  styles: [`
    .auth-card > h1 { margin-bottom: var(--space-2); }
    .lede { margin-bottom: var(--space-6); }
    .block { width: 100%; }
    .alert {
      margin: 0 0 var(--space-4); padding: var(--space-3) var(--space-4);
      background: var(--danger-wash); color: var(--danger);
      border-radius: var(--radius-sm); font-weight: 600;
    }
    .fallback { margin-top: var(--space-5); border-top: 1px solid var(--line); padding-top: var(--space-4); }
    .fallback > summary {
      cursor: pointer; font-weight: 600; color: var(--accent-ink);
      list-style: none; min-height: 44px; display: flex; align-items: center;
    }
    .fallback > summary::-webkit-details-marker { display: none; }
    .fallback > summary::before { content: "+"; margin-right: var(--space-2); font-family: var(--font-mono); color: var(--clay-ink); }
    .fallback[open] > summary::before { content: "\\00d7"; }
    .fallback > form { margin-top: var(--space-4); }
    .forgot { display: inline-block; margin-top: var(--space-5); font-size: var(--step--1); }
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
      next: () => this.router.navigate(['/app']),
      error: () => {
        this.submitting.set(false);
        this.banner.set($localize`:@@login.error.invalid:Invalid email or password.`);
      }
    });
  }
}
