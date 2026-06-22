import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { roleGuard } from './core/auth/role.guard';
import { PRIVATE, PUBLIC_HOME } from './core/seo/route-seo.model';

/**
 * Public auth pages are top-level siblings (NOT children) of the guarded shell so the guard never
 * fires on them and there is no redirect loop (FE-4/FE-6). /not-authorized is likewise a top-level
 * un-guarded sibling (F02 FE-5) so a redirected member cannot loop through authGuard.
 *
 * F60 (026-seo-aeo): the PUBLIC marketing home now owns `path: ''` (indexable); the authenticated
 * shell moved to `path: 'app'`. Every route carries `data.seo` — `PUBLIC_HOME` (index) on `''`,
 * `PRIVATE` (noindex,nofollow) everywhere else. SeoService treats a missing/PRIVATE seo as
 * deny-by-default, and the wildcard `**` 404 stops unknown URLs being served as the indexable home.
 */
export const routes: Routes = [
  {
    // F60 public marketing home — the one indexable page. No guard (anonymous + crawler reachable).
    path: '',
    pathMatch: 'full',
    data: { seo: PUBLIC_HOME },
    loadComponent: () => import('./features/home/home.component').then((m) => m.HomeComponent)
  },
  {
    path: 'login',
    data: { seo: PRIVATE },
    loadComponent: () => import('./features/auth/login/login.component').then((m) => m.LoginComponent)
  },
  {
    path: 'not-authorized',
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./shared/not-authorized/not-authorized.component').then((m) => m.NotAuthorizedComponent)
  },
  {
    // Admin member directory + role administration (F02 US1). roleGuard runs after authGuard.
    path: 'admin/members',
    canActivate: [authGuard, roleGuard('ADMIN')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/admin/members/members.component').then((m) => m.MembersComponent)
  },
  {
    // First-run workspace setup wizard (F03 US1). Admin-only; non-Admins are never routed here
    // (the shell shows a neutral "setup pending" panel instead — US6 AS-5).
    path: 'workspace/setup',
    canActivate: [authGuard, roleGuard('ADMIN')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/admin/workspace/workspace-setup-wizard.component').then((m) => m.WorkspaceSetupWizardComponent)
  },
  {
    // Ongoing workspace configuration (F03 US2-US5). Admin-only.
    path: 'admin/workspace',
    canActivate: [authGuard, roleGuard('ADMIN')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/admin/workspace/workspace-settings.component').then((m) => m.WorkspaceSettingsComponent)
  },
  {
    // F04 GDPR — erasure trigger + lawful-basis record/withdraw. Admin OR Recruiter.
    path: 'admin/gdpr/actions',
    canActivate: [authGuard, roleGuard('ADMIN', 'RECRUITER')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/admin/gdpr/candidate-erasure-action.component').then((m) => m.CandidateErasureActionComponent)
  },
  {
    // F04 GDPR — candidate audit log. Admin only.
    path: 'admin/gdpr/audit',
    canActivate: [authGuard, roleGuard('ADMIN')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/admin/gdpr/candidate-audit.component').then((m) => m.CandidateAuditComponent)
  },
  {
    // F04 GDPR — pending erasure-request queue. Admin only.
    path: 'admin/gdpr/requests',
    canActivate: [authGuard, roleGuard('ADMIN')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/admin/gdpr/erasure-queue.component').then((m) => m.ErasureQueueComponent)
  },
  {
    // F04 GDPR — retention review + confirm deletion. Admin only.
    path: 'admin/gdpr/retention',
    canActivate: [authGuard, roleGuard('ADMIN')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/admin/gdpr/retention-review.component').then((m) => m.RetentionReviewComponent)
  },
  {
    // F12 interview templates + rule-engine slot preview. Recruiter OR Admin (roleGuard).
    path: 'interview-templates',
    canActivate: [authGuard, roleGuard('ADMIN', 'RECRUITER')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/interview-templates/interview-templates.component').then((m) => m.InterviewTemplatesComponent)
  },
  {
    // F21 email template library — list/edit/tone/lock/reset + preview. Recruiter OR Admin (roleGuard).
    path: 'email-templates',
    canActivate: [authGuard, roleGuard('ADMIN', 'RECRUITER')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/email-templates/email-templates.component').then((m) => m.EmailTemplatesComponent)
  },
  {
    // F01.1 calendar connections — member-self surface, any authenticated role (authGuard only).
    path: 'calendar/connections',
    canActivate: [authGuard],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/calendar/calendar-connections.component').then((m) => m.CalendarConnectionsComponent)
  },
  {
    // F13 candidate self-scheduling slot-picker — PUBLIC (token in the URL, no login, no guard).
    // Top-level sibling of the guarded shell so authGuard never fires (the candidate has no session).
    // seo: PRIVATE — token page, must never be indexed.
    path: 'schedule',
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/schedule/schedule.component').then((m) => m.ScheduleComponent)
  },
  {
    // F20 candidate booking-management page (Flow A3 reschedule/cancel) — PUBLIC (manage token in the URL,
    // no login, no guard). Top-level sibling of the guarded shell so authGuard never fires, mirroring /schedule.
    path: 'booking',
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/booking/booking-manage.component').then((m) => m.BookingManageComponent)
  },
  {
    // F20 candidate cancel confirmation — PUBLIC. Explicit affirmative "Yes, cancel" step (the cancel POST
    // fires only on that click, never on page load — FR-012). Reached from /booking; token in the URL.
    path: 'booking/cancel',
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/booking/cancel-confirm.component').then((m) => m.CancelConfirmComponent)
  },
  {
    // F23 candidate attendance confirmation (Flow A4 no-show defense) — PUBLIC. Reached from the candidate's
    // REMINDER_24H email; the confirm token (DISTINCT from the manage token) is in the URL. Explicit
    // affirmative "Confirm attendance" step (the confirm POST fires only on that click, never on page load —
    // FR-006). Top-level sibling of the guarded shell so authGuard never fires, mirroring /booking, /schedule.
    path: 'confirm',
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/booking/confirm-attendance.component').then((m) => m.ConfirmAttendanceComponent)
  },
  {
    // F30 candidate status page (Flow §IX) — PUBLIC (status token in the URL, no login, no guard). Top-level
    // sibling of the guarded shell so authGuard never fires (the candidate has no session), mirroring
    // /schedule, /booking, /confirm. Inherits the global /_headers CSP + Referrer-Policy: no-referrer.
    path: 'status',
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/status/candidate-status.component').then((m) => m.CandidateStatusComponent)
  },
  {
    // F32 interviewer scorecard page (Flow §IX) — PUBLIC (write-only feedback token in the URL, no login, no
    // guard). Top-level sibling of the guarded shell (the candidate-class no-login pattern, mirroring /status,
    // /schedule). Inherits the global /_headers CSP + Referrer-Policy: no-referrer.
    path: 'feedback',
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/feedback/scorecard-page.component').then((m) => m.ScorecardPageComponent)
  },
  {
    // F40 ATS integration (Greenhouse) — connect/sync status/dead-letters. Admin-only internal screen.
    path: 'admin/ats',
    canActivate: [authGuard, roleGuard('ADMIN')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/admin/ats/ats-integration.component').then((m) => m.AtsIntegrationComponent)
  },
  {
    // F42 standalone CSV import — upload candidates, poll status, resolve duplicates. Admin OR Recruiter
    // internal screen (no candidate-facing §IX gate — the F50/F51 precedent).
    path: 'admin/csv-import',
    canActivate: [authGuard, roleGuard('ADMIN', 'RECRUITER')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/admin/csv-import/csv-import.component').then((m) => m.CsvImportComponent)
  },
  {
    // F50 Core Dashboard — time-to-schedule, no-show rate, current silence list + CSV export. Admin OR
    // Recruiter OR Read-only (read); export is Admin/Recruiter only (server-enforced; the button is hidden for
    // Read-only). Internal screen — no candidate-facing §IX gate (the F50/F51 precedent).
    path: 'admin/dashboard',
    canActivate: [authGuard, roleGuard('ADMIN', 'RECRUITER', 'READ_ONLY')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/admin/dashboard/dashboard.component').then((m) => m.DashboardComponent)
  },
  {
    // F13 recruiter "Send scheduling link" surface. Admin OR Recruiter (roleGuard).
    path: 'scheduling',
    canActivate: [authGuard, roleGuard('ADMIN', 'RECRUITER')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/scheduling/scheduling.component').then((m) => m.SchedulingComponent)
  },
  {
    // F51 Pipeline View — the recruiter's primary working list. Admin/Recruiter/Read-only/Hiring-Manager (server
    // scopes HM to assigned requisitions; Interviewer denied). Internal screen — no candidate-facing §IX gate.
    path: 'pipeline',
    canActivate: [authGuard, roleGuard('ADMIN', 'RECRUITER', 'READ_ONLY', 'HIRING_MANAGER')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/pipeline/pipeline-list.component').then((m) => m.PipelineListComponent)
  },
  {
    // F51 candidate timeline drill-down (opened from a pipeline row). Same role gate; server enforces scoping.
    path: 'pipeline/candidate/:candidateId/timeline',
    canActivate: [authGuard, roleGuard('ADMIN', 'RECRUITER', 'READ_ONLY', 'HIRING_MANAGER')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/pipeline/candidate-timeline.component').then((m) => m.CandidateTimelineComponent)
  },
  {
    // F51 requisition management (create/close + assign HM + link candidate). Admin internal screen (the candidate
    // link is also Recruiter-allowed server-side; the screen is Admin-routed for the management surface).
    path: 'admin/requisitions',
    canActivate: [authGuard, roleGuard('ADMIN')],
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/admin/requisitions/requisitions.component').then((m) => m.RequisitionsComponent)
  },
  {
    path: 'accept-invite',
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/auth/accept-invite/accept-invite.component').then((m) => m.AcceptInviteComponent)
  },
  {
    path: 'reset',
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/auth/reset-request/reset-request.component').then((m) => m.ResetRequestComponent)
  },
  {
    path: 'reset/confirm',
    data: { seo: PRIVATE },
    loadComponent: () =>
      import('./features/auth/reset-confirm/reset-confirm.component').then((m) => m.ResetConfirmComponent)
  },
  {
    // Guarded application shell (F60: relocated from '' to 'app'). Signed-in landing after login.
    path: 'app',
    canActivate: [authGuard],
    data: { seo: PRIVATE },
    loadComponent: () => import('./features/shell/shell.component').then((m) => m.ShellComponent)
  },
  {
    // F60 wildcard 404 — noindex; stops unknown/typo URLs being served as the indexable home.
    path: '**',
    data: { seo: PRIVATE },
    loadComponent: () => import('./features/not-found/not-found.component').then((m) => m.NotFoundComponent)
  }
];
