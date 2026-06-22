import { Component, OnInit, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { filter, take } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { Role } from '../../core/auth/auth.models';

interface NavItem { readonly path: string; readonly label: string; readonly desc: string; readonly roles: readonly Role[]; }
interface NavGroup { readonly title: string; readonly items: readonly NavItem[]; }

/**
 * Authenticated landing (027-ui-design-system). The persistent brand bar + identity + sign out now
 * live in the global TopBarComponent (rendered by AppComponent for every internal route), so this
 * component renders only the role-aware "launchpad" — a grouped grid of quick-link cards that is the
 * app's primary navigation. Each card links to a feature the member's role can reach (filtered to the
 * persisted role; the server + roleGuard remain the security boundary). Routes a first-run Admin to
 * the setup wizard and shows non-Admins a neutral "setup pending" state while unconfigured (F03 US6).
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterLink],
  template: `
    @if (member(); as m) {
      <main class="launch container">
        @if (!m.workspaceConfigured && m.role !== 'ADMIN') {
          <div class="notice card">
            <h1 i18n="@@workspace.setupPending.title">Workspace setup pending</h1>
            <p class="muted" i18n="@@workspace.setupPending.body">
              An administrator needs to finish setting up this workspace before you can continue.
            </p>
          </div>
        } @else {
          <header class="launch__head">
            <p class="eyebrow eyebrow--rule" i18n="@@shell.eyebrow">Your workspace</p>
            <h1 i18n="@@shell.welcome">Welcome to Cadence</h1>
            <p class="lede muted" i18n="@@shell.subtitle">Jump straight to your work.</p>
          </header>

          <nav class="launch__nav" aria-label="Sections" i18n-aria-label="@@shell.nav.label">
            @for (group of groups(); track group.title) {
              <section class="launch__group">
                <h2 class="launch__group-title eyebrow eyebrow--quiet">{{ group.title }}</h2>
                <div class="launch__grid">
                  @for (item of group.items; track item.path) {
                    <a class="card launch__card" [routerLink]="item.path">
                      <span class="launch__card-title">{{ item.label }}</span>
                      <span class="launch__card-desc muted">{{ item.desc }}</span>
                      <span class="launch__card-go" aria-hidden="true">&rarr;</span>
                    </a>
                  }
                </div>
              </section>
            }
          </nav>
        }
      </main>
    }
  `,
  styles: [`
    .launch { padding-block: var(--space-8) var(--space-12); }
    .launch__head {
      padding-bottom: var(--space-6); margin-bottom: var(--space-8);
      border-bottom: 1px solid var(--line);
      background: radial-gradient(40rem 14rem at 0% -40%, var(--clay-wash), transparent 70%);
    }
    .launch__head > h1 { margin-bottom: var(--space-1); }
    .lede { font-size: var(--step-1); margin-bottom: 0; }

    .launch__group { margin-bottom: var(--space-8); }
    /* Font/size/tracking/colour come from the shared .eyebrow + .eyebrow--quiet primitives. */
    .launch__group-title { margin-bottom: var(--space-4); }
    .launch__grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr)); gap: var(--space-4); }

    .launch__card {
      position: relative; display: grid; grid-template-columns: 1fr auto; align-items: start; column-gap: var(--space-2);
      padding: var(--space-5); text-decoration: none; color: var(--ink); overflow: hidden;
      transition: border-color 0.15s ease, box-shadow 0.15s ease, transform 0.08s ease;
    }
    /* A thin accent rail that wipes in on hover - the only motion, kept subtle. */
    .launch__card::before {
      content: ""; position: absolute; inset: 0 auto 0 0; width: 3px;
      background: var(--accent); transform: scaleY(0); transform-origin: top;
      transition: transform 0.18s ease;
    }
    .launch__card:hover { border-color: var(--line-strong); box-shadow: var(--shadow-md); transform: translateY(-2px); }
    .launch__card:hover::before { transform: scaleY(1); }
    .launch__card-title { font-weight: 700; font-size: var(--step-0); }
    .launch__card-desc { grid-column: 1 / -1; margin-top: var(--space-1); font-size: var(--step--1); line-height: 1.45; }
    .launch__card-go { color: var(--accent); font-size: var(--step-1); line-height: 1; transition: transform 0.15s ease; }
    .launch__card:hover .launch__card-go { transform: translateX(3px); }

    .notice { max-width: 40rem; }
  `]
})
export class ShellComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly member = toSignal(this.auth.member$, { initialValue: null });

  // Launchpad groups, filtered to the current member's persisted role. Mirrors app.routes.ts role gates;
  // the server + roleGuard are the real boundary (this only hides cards a role can't use).
  readonly groups = computed<NavGroup[]>(() => {
    const role = this.member()?.role;
    if (!role) return [];
    return ALL_GROUPS
      .map((g) => ({ title: g.title, items: g.items.filter((i) => i.roles.includes(role)) }))
      .filter((g) => g.items.length > 0);
  });

  ngOnInit(): void {
    // Wait for the first non-null member, then route an unconfigured Admin to the setup wizard.
    this.auth.member$
      .pipe(filter((m) => m !== null), take(1))
      .subscribe((member) => {
        if (member && !member.workspaceConfigured && member.role === 'ADMIN') {
          this.router.navigate(['/workspace/setup']);
        }
      });
  }
}

const ALL_GROUPS: readonly NavGroup[] = [
  {
    title: $localize`:@@launch.group.work:Your work`,
    items: [
      { path: '/pipeline', roles: ['ADMIN', 'RECRUITER', 'READ_ONLY', 'HIRING_MANAGER'],
        label: $localize`:@@launch.pipeline:Pipeline`,
        desc: $localize`:@@launch.pipeline.desc:Your working candidate list across every stage.` },
      { path: '/admin/dashboard', roles: ['ADMIN', 'RECRUITER', 'READ_ONLY'],
        label: $localize`:@@launch.dashboard:Dashboard`,
        desc: $localize`:@@launch.dashboard.desc:Time-to-schedule, no-show rate, and candidate silence.` },
      { path: '/scheduling', roles: ['ADMIN', 'RECRUITER'],
        label: $localize`:@@launch.scheduling:Send scheduling link`,
        desc: $localize`:@@launch.scheduling.desc:Invite a candidate to self-schedule an interview.` },
      { path: '/admin/csv-import', roles: ['ADMIN', 'RECRUITER'],
        label: $localize`:@@launch.csv:Import candidates`,
        desc: $localize`:@@launch.csv.desc:Bulk-add candidates from a CSV file.` }
    ]
  },
  {
    title: $localize`:@@launch.group.templates:Templates`,
    items: [
      { path: '/interview-templates', roles: ['ADMIN', 'RECRUITER'],
        label: $localize`:@@launch.interviewTemplates:Interview templates`,
        desc: $localize`:@@launch.interviewTemplates.desc:Panels, durations, and slot rules.` },
      { path: '/email-templates', roles: ['ADMIN', 'RECRUITER'],
        label: $localize`:@@launch.emailTemplates:Email templates`,
        desc: $localize`:@@launch.emailTemplates.desc:Candidate message content and tone.` }
    ]
  },
  {
    title: $localize`:@@launch.group.admin:Administration`,
    items: [
      { path: '/admin/members', roles: ['ADMIN'],
        label: $localize`:@@launch.members:Members`,
        desc: $localize`:@@launch.members.desc:Invite teammates and manage roles.` },
      { path: '/admin/requisitions', roles: ['ADMIN'],
        label: $localize`:@@launch.requisitions:Requisitions`,
        desc: $localize`:@@launch.requisitions.desc:Open roles and hiring-manager assignment.` },
      { path: '/admin/workspace', roles: ['ADMIN'],
        label: $localize`:@@launch.workspace:Workspace settings`,
        desc: $localize`:@@launch.workspace.desc:Branding, time zone, retention, and SLAs.` },
      { path: '/admin/ats', roles: ['ADMIN'],
        label: $localize`:@@launch.ats:ATS integration`,
        desc: $localize`:@@launch.ats.desc:Connect Greenhouse or Lever and sync status.` }
    ]
  },
  {
    title: $localize`:@@launch.group.data:Data & privacy`,
    items: [
      { path: '/admin/gdpr/actions', roles: ['ADMIN', 'RECRUITER'],
        label: $localize`:@@launch.candidateData:Candidate data`,
        desc: $localize`:@@launch.candidateData.desc:Lawful basis, withdrawal, and erasure.` },
      { path: '/admin/gdpr/audit', roles: ['ADMIN'],
        label: $localize`:@@launch.audit:Audit log`,
        desc: $localize`:@@launch.audit.desc:Per-candidate access and change history.` },
      { path: '/admin/gdpr/requests', roles: ['ADMIN'],
        label: $localize`:@@launch.erasure:Erasure requests`,
        desc: $localize`:@@launch.erasure.desc:Pending right-to-be-forgotten queue.` },
      { path: '/admin/gdpr/retention', roles: ['ADMIN'],
        label: $localize`:@@launch.retention:Retention`,
        desc: $localize`:@@launch.retention.desc:Review and confirm scheduled deletions.` }
    ]
  },
  {
    title: $localize`:@@launch.group.personal:Personal`,
    items: [
      { path: '/calendar/connections', roles: ['ADMIN', 'RECRUITER', 'HIRING_MANAGER', 'INTERVIEWER', 'READ_ONLY'],
        label: $localize`:@@launch.calendar:Calendar connections`,
        desc: $localize`:@@launch.calendar.desc:Connect Google or Microsoft for availability.` }
    ]
  }
];
