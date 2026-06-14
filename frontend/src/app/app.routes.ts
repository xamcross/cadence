import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

/**
 * Public auth pages are top-level siblings (NOT children) of the guarded shell so the guard never
 * fires on them and there is no redirect loop (FE-4/FE-6).
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login/login.component').then((m) => m.LoginComponent)
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
