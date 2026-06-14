import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { roleGuard } from './core/auth/role.guard';

/**
 * Public auth pages are top-level siblings (NOT children) of the guarded shell so the guard never
 * fires on them and there is no redirect loop (FE-4/FE-6). /not-authorized is likewise a top-level
 * un-guarded sibling (F02 FE-5) so a redirected member cannot loop through authGuard.
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login/login.component').then((m) => m.LoginComponent)
  },
  {
    path: 'not-authorized',
    loadComponent: () =>
      import('./shared/not-authorized/not-authorized.component').then((m) => m.NotAuthorizedComponent)
  },
  {
    // Admin member directory + role administration (F02 US1). roleGuard runs after authGuard.
    path: 'admin/members',
    canActivate: [authGuard, roleGuard('ADMIN')],
    loadComponent: () =>
      import('./features/admin/members/members.component').then((m) => m.MembersComponent)
  },
  {
    // First-run workspace setup wizard (F03 US1). Admin-only; non-Admins are never routed here
    // (the shell shows a neutral "setup pending" panel instead — US6 AS-5).
    path: 'workspace/setup',
    canActivate: [authGuard, roleGuard('ADMIN')],
    loadComponent: () =>
      import('./features/admin/workspace/workspace-setup-wizard.component').then((m) => m.WorkspaceSetupWizardComponent)
  },
  {
    // Ongoing workspace configuration (F03 US2-US5). Admin-only.
    path: 'admin/workspace',
    canActivate: [authGuard, roleGuard('ADMIN')],
    loadComponent: () =>
      import('./features/admin/workspace/workspace-settings.component').then((m) => m.WorkspaceSettingsComponent)
  },
  {
    path: 'accept-invite',
    loadComponent: () =>
      import('./features/auth/accept-invite/accept-invite.component').then((m) => m.AcceptInviteComponent)
  },
  {
    path: 'reset',
    loadComponent: () =>
      import('./features/auth/reset-request/reset-request.component').then((m) => m.ResetRequestComponent)
  },
  {
    path: 'reset/confirm',
    loadComponent: () =>
      import('./features/auth/reset-confirm/reset-confirm.component').then((m) => m.ResetConfirmComponent)
  },
  {
    // Guarded application shell (dashboard etc. land here as features are built).
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./features/shell/shell.component').then((m) => m.ShellComponent)
  }
];
