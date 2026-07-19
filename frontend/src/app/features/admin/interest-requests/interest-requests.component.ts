import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  InterestRequestRow,
  InterestRequestsService,
  InterestStatusFilter
} from './interest-requests.service';
import { Role } from '../../../core/auth/auth.models';
import { PageHeaderComponent } from '../../../shared/ui/page-header.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { SkeletonComponent } from '../../../shared/ui/skeleton.component';
import { TableScrollComponent } from '../../../shared/ui/table-scroll.component';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';

/**
 * F70 interest-request review queue (US2) — internal Admin-only screen. Lists the workspace's interest requests
 * with a status filter (default `open`, which EXCLUDES REVIEWED — FR-013/US2 Sc.2), and acts on each row:
 * review, dismiss, invite (with a role selector → a real invitation via the existing invitation flow), or erase.
 * Email and organization are submitter-claimed, so they are labelled "unverified". No candidate-facing §IX gate
 * (the F31/F50 internal-screen precedent). All field values are rendered via Angular interpolation (auto-escaped)
 * — never innerHTML — so a malicious `<script>`/`=cmd` value displays inert (SC-012). All strings $localize.
 *
 * Phase 3b (workbench overhaul): `erase(r)` (⚠ danger) and `dismiss(r)` (light) are gated behind the shared
 * `ConfirmDialogService`. `review`/`invite`/`showAll` stay ungated. Action outcomes are surfaced via
 * `ToastService`, replacing the old per-row `noteFor` map.
 */
@Component({
  selector: 'app-interest-requests',
  standalone: true,
  imports: [
    CommonModule, FormsModule, DatePipe,
    PageHeaderComponent, EmptyStateComponent, SkeletonComponent, TableScrollComponent
  ],
  template: `
    <section class="interest-requests">
      <app-page-header
        eyebrow="Administration" i18n-eyebrow="@@interestAdmin.eyebrow"
        heading="Access requests" i18n-heading="@@interestAdmin.title"
        subtitle="Review, invite, or dismiss requests to join your workspace." i18n-subtitle="@@interestAdmin.subtitle">
      </app-page-header>

      <div class="filter toolbar">
        <label for="statusFilter">{{ filterLabel }}</label>
        <select class="input" id="statusFilter" name="statusFilter" [(ngModel)]="filter" (ngModelChange)="load()">
          <option value="open" i18n="@@interestAdmin.filter.open">Open</option>
          <option value="reviewed" i18n="@@interestAdmin.filter.reviewed">Reviewed</option>
          <option value="invited" i18n="@@interestAdmin.filter.invited">Invited</option>
          <option value="dismissed" i18n="@@interestAdmin.filter.dismissed">Dismissed</option>
          <option value="all" i18n="@@interestAdmin.filter.all">All</option>
        </select>
        <button type="button" class="act-export btn btn--outline" (click)="exportCsv()"
                i18n="@@interestAdmin.act.export">Export CSV</button>
      </div>

      <p class="error alert alert--danger" role="alert" *ngIf="error()">{{ error() }}</p>

      @if (loading()) {
        <app-skeleton variant="table" />
      } @else if (requests().length === 0) {
        <app-empty-state
          heading="No requests to review" i18n-heading="@@interestAdmin.empty.heading"
          body="There are no access requests matching this filter." i18n-body="@@interestAdmin.empty.body">
          <button type="button" class="act-show-all btn btn--primary" (click)="showAll()" i18n="@@interestAdmin.empty.cta">Show all requests</button>
        </app-empty-state>
      } @else {
        <app-table-scroll ariaLabel="Access requests" i18n-ariaLabel="@@interestAdmin.tableLabel">
          <table class="rows table table--stack">
            <thead>
              <tr>
                <th i18n="@@interestAdmin.col.name">Name</th>
                <th i18n="@@interestAdmin.col.email">Email</th>
                <th i18n="@@interestAdmin.col.org">Organization</th>
                <th i18n="@@interestAdmin.col.message">Message</th>
                <th i18n="@@interestAdmin.col.status">Status</th>
                <th i18n="@@interestAdmin.col.submitted">Submitted</th>
                <th i18n="@@interestAdmin.col.actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let r of requests()" class="request-row">
                <td class="cell-name" data-label="Name">{{ r.name }}</td>
                <td class="cell-email" data-label="Email">
                  {{ r.email }}
                  <span class="unverified" *ngIf="r.emailUnverified" i18n="@@interestAdmin.unverified">(unverified)</span>
                </td>
                <td class="cell-org" data-label="Organization">
                  <ng-container *ngIf="r.organization">
                    {{ r.organization }}
                    <span class="unverified" *ngIf="r.organizationUnverified" i18n="@@interestAdmin.unverified2">(unverified)</span>
                  </ng-container>
                </td>
                <td class="cell-message" data-label="Message">{{ r.message }}</td>
                <td class="cell-status" data-label="Status">{{ r.status }}</td>
                <td class="cell-submitted" data-label="Submitted">{{ r.submittedAt | date: 'short' }}</td>
                <td class="cell-actions" data-label="Actions">
                  <button type="button" class="act-review btn btn--ghost btn--sm" (click)="review(r)"
                          [disabled]="busy() || r.status !== 'NEW'" i18n="@@interestAdmin.act.review">Mark reviewed</button>
                  <button type="button" class="act-dismiss btn btn--ghost btn--sm" (click)="dismiss(r)"
                          [disabled]="busy() || (r.status !== 'NEW' && r.status !== 'REVIEWED')"
                          i18n="@@interestAdmin.act.dismiss">Dismiss</button>

                  <span class="invite-controls" *ngIf="r.status === 'NEW' || r.status === 'REVIEWED'">
                    <select class="input" [name]="'role-' + r.id" [(ngModel)]="roleFor[r.id]" [attr.aria-label]="roleSelectLabel">
                      <option *ngFor="let role of roles" [value]="role">{{ role }}</option>
                    </select>
                    <button type="button" class="act-invite btn btn--primary btn--sm" (click)="invite(r)" [disabled]="busy()"
                            i18n="@@interestAdmin.act.invite">Invite</button>
                  </span>

                  <button type="button" class="act-erase btn btn--danger-soft btn--sm" (click)="erase(r)" [disabled]="busy()"
                          i18n="@@interestAdmin.act.erase">Erase</button>
                </td>
              </tr>
            </tbody>
          </table>
        </app-table-scroll>
      }
    </section>
  `,
  styles: [`
    .interest-requests { padding: var(--space-4); }
    .filter { margin-block: var(--space-3) var(--space-4); }
    .rows td { vertical-align: top; }
    .cell-actions button, .invite-controls { margin-inline-end: var(--space-1); margin-block-end: var(--space-1); }
    .unverified { color: var(--ink-faint); font-size: 0.85em; }
  `]
})
export class InterestRequestsComponent implements OnInit {
  private readonly api = inject(InterestRequestsService);
  private readonly confirm = inject(ConfirmDialogService);
  private readonly toast = inject(ToastService);

  readonly filterLabel = $localize`:@@interestAdmin.filterLabel:Show:`;
  readonly roleSelectLabel = $localize`:@@interestAdmin.roleLabel:Invite as role`;
  private readonly loadFailed = $localize`:@@interestAdmin.loadFailed:Could not load requests. Please try again.`;
  private readonly actionFailed = $localize`:@@toast.interestAdmin.actionFailed:That action could not be completed.`;
  private readonly conflictNote = $localize`:@@toast.interestAdmin.conflict:Already actioned by someone else.`;
  private readonly reviewedNote = $localize`:@@toast.interestAdmin.reviewed:Marked reviewed.`;
  private readonly dismissedNote = $localize`:@@toast.interestAdmin.dismissed:Request dismissed.`;
  private readonly invitedNote = $localize`:@@toast.interestAdmin.invited:Invitation sent.`;
  private readonly alreadyMemberNote = $localize`:@@toast.interestAdmin.alreadyMember:Already a member — marked resolved.`;
  private readonly erasedNote = $localize`:@@toast.interestAdmin.erased:Request erased.`;

  /** Default triage view EXCLUDES REVIEWED (FR-013). */
  filter: InterestStatusFilter = 'open';
  readonly roles: Role[] = ['RECRUITER', 'HIRING_MANAGER', 'INTERVIEWER', 'READ_ONLY', 'ADMIN'];
  /** Per-row selected invite role; defaults to RECRUITER. */
  roleFor: Record<string, Role> = {};

  readonly requests = signal<InterestRequestRow[]>([]);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list(this.filter).subscribe({
      next: (res) => {
        for (const r of res.requests) {
          if (!this.roleFor[r.id]) { this.roleFor[r.id] = 'RECRUITER'; }
        }
        this.requests.set(res.requests);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(this.loadFailed);
        this.loading.set(false);
      }
    });
  }

  review(r: InterestRequestRow): void {
    this.busy.set(true);
    this.api.review(r.id).subscribe({
      next: () => {
        this.toast.success(this.reviewedNote);
        this.afterAction();
      },
      error: (e) => this.onActionError(e)
    });
  }

  async dismiss(r: InterestRequestRow): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.interestAdmin.dismiss.title:Dismiss this request?`,
      body: $localize`:@@confirm.interestAdmin.dismiss.body:${r.name}:name:'s request will be marked dismissed and no invitation will be sent.`,
      confirmLabel: $localize`:@@confirm.interestAdmin.dismiss.cta:Dismiss`
    });
    if (!ok) {
      return;
    }
    this.busy.set(true);
    this.api.dismiss(r.id).subscribe({
      next: () => {
        this.toast.success(this.dismissedNote);
        this.afterAction();
      },
      error: (e) => this.onActionError(e)
    });
  }

  invite(r: InterestRequestRow): void {
    this.busy.set(true);
    const role = this.roleFor[r.id] ?? 'RECRUITER';
    this.api.invite(r.id, role).subscribe({
      next: (res) => {
        this.toast.success(res.alreadyMember ? this.alreadyMemberNote : this.invitedNote);
        this.afterAction();
      },
      error: (e) => this.onActionError(e)
    });
  }

  /** Download the queue as injection-safe CSV for the current status filter (the backend neutralizes every cell). */
  exportCsv(): void {
    this.api.exportCsv(this.filter);
  }

  /** Empty-state CTA: widen the current filter to the unfiltered "all" view and reload. */
  showAll(): void {
    this.filter = 'all';
    this.load();
  }

  async erase(r: InterestRequestRow): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.interestAdmin.erase.title:Erase this request?`,
      body: $localize`:@@confirm.interestAdmin.erase.body:${r.name}:name:'s access request and personal data will be permanently erased.`,
      confirmLabel: $localize`:@@confirm.interestAdmin.erase.cta:Erase`,
      danger: true
    });
    if (!ok) {
      return;
    }
    this.busy.set(true);
    this.api.erase(r.id).subscribe({
      next: () => {
        this.toast.success(this.erasedNote);
        this.afterAction();
      },
      error: (e) => this.onActionError(e)
    });
  }

  private afterAction(): void {
    this.busy.set(false);
    this.load();
  }

  private onActionError(e: { status?: number }): void {
    this.busy.set(false);
    this.toast.error(e?.status === 409 ? this.conflictNote : this.actionFailed);
  }
}
