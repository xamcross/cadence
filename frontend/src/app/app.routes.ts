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
    // F04 GDPR — erasure trigger + lawful-basis record/withdraw. Admin OR Recruiter.
    path: 'admin/gdpr/actions',
    canActivate: [authGuard, roleGuard('ADMIN', 'RECRUITER')],
    loadComponent: () =>
      import('./features/admin/gdpr/candidate-erasure-action.component').then((m) => m.CandidateErasureActionComponent)
  },
  {
    // F04 GDPR — candidate audit log. Admin only.
    path: 'admin/gdpr/audit',
    canActivate: [authGuard, roleGuard('ADMIN')],
    loadComponent: () =>
      import('./features/admin/gdpr/candidate-audit.component').then((m) => m.CandidateAuditComponent)
  },
  {
    // F04 GDPR — pending erasure-request queue. Admin only.
    path: 'admin/gdpr/requests',
    canActivate: [authGuard, roleGuard('ADMIN')],
    loadComponent: () =>
      import('./features/admin/gdpr/erasure-queue.component').then((m) => m.ErasureQueueComponent)
  },
  {
    // F04 GDPR — retention review + confirm deletion. Admin only.
    path: 'admin/gdpr/retention',
    canActivate: [authGuard, roleGuard('ADMIN')],
    loadComponent: () =>
      import('./features/admin/gdpr/retention-review.component').then((m) => m.RetentionReviewComponent)
  },
  {
    // F12 interview templates + rule-engine slot preview. Recruiter OR Admin (roleGuard).
    path: 'interview-templates',
    canActivate: [authGuard, roleGuard('ADMIN', 'RECRUITER')],
    loadComponent: () =>
      import('./features/interview-templates/interview-templates.component').then((m) => m.InterviewTemplatesComponent)
  },
  {
    // F21 email template library — list/edit/tone/lock/reset + preview. Recruiter OR Admin (roleGuard).
    path: 'email-templates',
    canActivate: [authGuard, roleGuard('ADMIN', 'RECRUITER')],
    loadComponent: () =>
      import('./features/email-templates/email-templates.component').then((m) => m.EmailTemplatesComponent)
  },
  {
    // F01.1 calendar connections — member-self surface, any authenticated role (authGuard only).
    path: 'calendar/connections',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/calendar/calendar-connections.component').then((m) => m.CalendarConnectionsComponent)
  },
  {
    // F13 candidate self-scheduling slot-picker — PUBLIC (token in the URL, no login, no guard).
    // Top-level sibling of the guarded shell so authGuard never fires (the candidate has no session).
    path: 'schedule',
    loadComponent: () =>
      import('./features/schedule/schedule.component').then((m) => m.ScheduleComponent)
  },
  {
    // F20 candidate booking-management page (Flow A3 reschedule/cancel) — PUBLIC (manage token in the URL,
    // no login, no guard). Top-level sibling of the guarded shell so authGuard never fires, mirroring /schedule.
    path: 'booking',
    loadComponent: () =>
      import('./features/booking/booking-manage.component').then((m) => m.BookingManageComponent)
  },
  {
    // F20 candidate cancel confirmation — PUBLIC. Explicit affirmative "Yes, cancel" step (the cancel POST
    // fires only on that click, never on page load — FR-012). Reached from /booking; token in the URL.
    path: 'booking/cancel',
    loadComponent: () =>
      import('./features/booking/cancel-confirm.component').then((m) => m.CancelConfirmComponent)
  },
  {
    // F13 recruiter "Send scheduling link" surface. Admin OR Recruiter (roleGuard).
    path: 'scheduling',
    canActivate: [authGuard, roleGuard('ADMIN', 'RECRUITER')],
    loadComponent: () =>
      import('./features/scheduling/scheduling.component').then((m) => m.SchedulingComponent)
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
