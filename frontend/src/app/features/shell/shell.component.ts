import { Component, OnInit, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { filter, take } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';

/**
 * Authenticated app shell. Routes a first-run Admin to the setup wizard and shows non-Admins a
 * neutral "setup pending" state while the workspace is unconfigured (F03 US6). The redirect runs in
 * an ngOnInit subscription (NOT a template side-effect): it filters out the initial null member$ and
 * fires once. The server + roleGuard remain the security boundary.
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterLink],
  template: `
    @if (member(); as m) {
      <header class="shell-bar">
        <span i18n="@@shell.brand">Cadence</span>
        @if (m.role === 'ADMIN') {
          <a routerLink="/admin/members" i18n="@@shell.members">Members</a>
          <a routerLink="/admin/workspace" i18n="@@shell.workspace">Workspace settings</a>
        }
        <span class="spacer"></span>
        <span>{{ m.displayName }}</span>
        <button type="button" (click)="logout()" i18n="@@shell.signout">Sign out</button>
      </header>
      <main class="shell-main">
        @if (!m.workspaceConfigured && m.role !== 'ADMIN') {
          <h1 i18n="@@workspace.setupPending.title">Workspace setup pending</h1>
          <p i18n="@@workspace.setupPending.body">
            An administrator needs to finish setting up this workspace before you can continue.
          </p>
        } @else {
          <h1 i18n="@@shell.welcome">Welcome to Cadence</h1>
        }
      </main>
    }
  `,
  styles: [`
    .shell-bar { display: flex; align-items: center; gap: 1rem; padding: 0.75rem 1rem; border-bottom: 1px solid #ddd; }
    .spacer { flex: 1; }
    button { min-height: 44px; }
    .shell-main { padding: 1rem; }
  `]
})
export class ShellComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  // Single subscription to the member stream (one async source, no double CD pass).
  readonly member = toSignal(this.auth.member$, { initialValue: null });

  ngOnInit(): void {
    // member$ is populated by the authGuard's me() probe. Wait for the first non-null emission, then
    // route an unconfigured Admin to the wizard. Non-Admins stay on the shell (neutral panel above).
    this.auth.member$
      .pipe(filter((m) => m !== null), take(1))
      .subscribe((member) => {
        if (member && !member.workspaceConfigured && member.role === 'ADMIN') {
          this.router.navigate(['/workspace/setup']);
        }
      });
  }

  logout(): void {
    this.auth.logout().subscribe({
      next: () => this.router.navigate(['/login']),
      error: () => this.router.navigate(['/login'])
    });
  }
}
