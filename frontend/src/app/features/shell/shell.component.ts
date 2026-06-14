import { Component, inject } from '@angular/core';
import { AsyncPipe } from '@angular/common';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

/** Authenticated app shell. Placeholder landing for the workspace; feature views mount here later. */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [AsyncPipe],
  template: `
    <header class="shell-bar">
      <span i18n="@@shell.brand">Cadence</span>
      @if (auth.member$ | async; as member) {
        <span class="spacer"></span>
        <span>{{ member.displayName }}</span>
        <button type="button" (click)="logout()" i18n="@@shell.signout">Sign out</button>
      }
    </header>
    <main class="shell-main">
      <h1 i18n="@@shell.welcome">Welcome to Cadence</h1>
    </main>
  `,
  styles: [`
    .shell-bar { display: flex; align-items: center; gap: 1rem; padding: 0.75rem 1rem; border-bottom: 1px solid #ddd; }
    .spacer { flex: 1; }
    button { min-height: 44px; }
    .shell-main { padding: 1rem; }
  `]
})
export class ShellComponent {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  // No me() call here: the authGuard already validated the session and populated member$ via its
  // me() probe (AuthService.me() caches into member$). Avoids a redundant round-trip (FE-1).

  logout(): void {
    this.auth.logout().subscribe({
      next: () => this.router.navigate(['/login']),
      error: () => this.router.navigate(['/login'])
    });
  }
}
