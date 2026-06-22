import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { Role } from '../../core/auth/auth.models';

/**
 * Persistent authenticated chrome (027-ui-design-system). Rendered by AppComponent on every
 * internal route (data.shell === true) so a member is never stranded one level deep — the brand
 * mark always links back to the /app launchpad, and sign-out is always reachable. The server +
 * authGuard remain the security boundary; this bar only appears once a member is resolved.
 */
@Component({
  selector: 'app-top-bar',
  standalone: true,
  imports: [RouterLink],
  template: `
    @if (member(); as m) {
      <header class="topbar">
        <a class="brand" routerLink="/app" aria-label="Cadence home">
          <svg class="brand__mark" viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
            <rect x="3" y="9" width="3.6" height="12" rx="1.8" />
            <rect x="10.2" y="4" width="3.6" height="17" rx="1.8" />
            <rect x="17.4" y="13" width="3.6" height="8" rx="1.8" />
          </svg>
          <span class="brand__name" i18n="@@shell.brand">Cadence</span>
        </a>
        <span class="spacer"></span>
        <span class="who">
          <span class="who__name">{{ m.displayName }}</span>
          <span class="badge">{{ roleLabel(m.role) }}</span>
        </span>
        <button type="button" class="btn btn--ghost" (click)="logout()" i18n="@@shell.signout">Sign out</button>
      </header>
    }
  `,
  styles: [`
    .topbar {
      position: sticky; top: 0; z-index: 10;
      display: flex; align-items: center; gap: var(--space-4);
      padding: var(--space-3) var(--space-5);
      background: var(--surface);
      border-bottom: 1px solid var(--line);
      box-shadow: var(--shadow-sm);
    }
    .spacer { flex: 1; }
    .brand { display: inline-flex; align-items: center; gap: var(--space-2); text-decoration: none; color: var(--ink); }
    .brand__mark { color: var(--accent); }
    .brand__name { font-family: var(--font-display); font-weight: 600; font-size: var(--step-1); letter-spacing: -0.01em; }
    .who { display: inline-flex; align-items: center; gap: var(--space-3); }
    .who__name { font-weight: 600; }
    @media (max-width: 32rem) { .who__name { display: none; } }
  `]
})
export class TopBarComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly member = toSignal(this.auth.member$, { initialValue: null });

  roleLabel(role: Role): string {
    switch (role) {
      case 'ADMIN': return $localize`:@@role.admin:Admin`;
      case 'RECRUITER': return $localize`:@@role.recruiter:Recruiter`;
      case 'HIRING_MANAGER': return $localize`:@@role.hiringManager:Hiring manager`;
      case 'INTERVIEWER': return $localize`:@@role.interviewer:Interviewer`;
      case 'READ_ONLY': return $localize`:@@role.readOnly:Read-only`;
    }
  }

  logout(): void {
    this.auth.logout().subscribe({
      next: () => this.router.navigate(['/login']),
      error: () => this.router.navigate(['/login'])
    });
  }
}
