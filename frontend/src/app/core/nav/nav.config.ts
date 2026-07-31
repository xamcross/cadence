import { Role } from '../auth/auth.models';

export interface NavItem { readonly path: string; readonly label: string; readonly desc: string; readonly roles: readonly Role[]; }
export interface NavGroup { readonly title: string; readonly items: readonly NavItem[]; }

export const NAV_GROUPS: readonly NavGroup[] = [
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
        desc: $localize`:@@launch.ats.desc:Connect Greenhouse or Lever and sync status.` },
      { path: '/admin/billing', roles: ['ADMIN'],
        label: $localize`:@@launch.billing:Billing & plan`,
        desc: $localize`:@@launch.billing.desc:Your plan, upgrades, and the customer portal.` }
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
