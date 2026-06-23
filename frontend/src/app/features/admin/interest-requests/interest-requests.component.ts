import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  InterestRequestRow,
  InterestRequestsService,
  InterestStatusFilter
} from './interest-requests.service';
import { Role } from '../../../core/auth/auth.models';

/**
 * F70 interest-request review queue (US2) — internal Admin-only screen. Lists the workspace's interest requests
 * with a status filter (default `open`, which EXCLUDES REVIEWED — FR-013/US2 Sc.2), and acts on each row:
 * review, dismiss, invite (with a role selector → a real invitation via the existing invitation flow), or erase.
 * Email and organization are submitter-claimed, so they are labelled "unverified". No candidate-facing §IX gate
 * (the F31/F50 internal-screen precedent). All field values are rendered via Angular interpolation (auto-escaped)
 * — never innerHTML — so a malicious `<script>`/`=cmd` value displays inert (SC-012). All strings $localize.
 */
@Component({
  selector: 'app-interest-requests',
  standalone: true,
  imports: [CommonModule, FormsModule, DatePipe],
  template: `
    <section class="interest-requests">
      <h1>{{ title }}</h1>

      <div class="filter">
        <label for="statusFilter">{{ filterLabel }}</label>
        <select id="statusFilter" name="statusFilter" [(ngModel)]="filter" (ngModelChange)="load()">
          <option value="open" i18n="@@interestAdmin.filter.open">Open</option>
          <option value="reviewed" i18n="@@interestAdmin.filter.reviewed">Reviewed</option>
          <option value="invited" i18n="@@interestAdmin.filter.invited">Invited</option>
          <option value="dismissed" i18n="@@interestAdmin.filter.dismissed">Dismissed</option>
          <option value="all" i18n="@@interestAdmin.filter.all">All</option>
        </select>
        <button type="button" class="act-export" (click)="exportCsv()"
                i18n="@@interestAdmin.act.export">Export CSV</button>
      </div>

      <p class="error" *ngIf="error()">{{ error() }}</p>
      <p class="empty" *ngIf="!loading() && requests().length === 0" i18n="@@interestAdmin.empty">No requests in this view.</p>

      <table class="rows" *ngIf="requests().length">
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
            <td class="cell-name">{{ r.name }}</td>
            <td class="cell-email">
              {{ r.email }}
              <span class="unverified" *ngIf="r.emailUnverified" i18n="@@interestAdmin.unverified">(unverified)</span>
            </td>
            <td class="cell-org">
              <ng-container *ngIf="r.organization">
                {{ r.organization }}
                <span class="unverified" *ngIf="r.organizationUnverified" i18n="@@interestAdmin.unverified2">(unverified)</span>
              </ng-container>
            </td>
            <td class="cell-message">{{ r.message }}</td>
            <td class="cell-status">{{ r.status }}</td>
            <td class="cell-submitted">{{ r.submittedAt | date: 'short' }}</td>
            <td class="cell-actions">
              <button type="button" class="act-review" (click)="review(r)"
                      [disabled]="busy() || r.status !== 'NEW'" i18n="@@interestAdmin.act.review">Mark reviewed</button>
              <button type="button" class="act-dismiss" (click)="dismiss(r)"
                      [disabled]="busy() || (r.status !== 'NEW' && r.status !== 'REVIEWED')"
                      i18n="@@interestAdmin.act.dismiss">Dismiss</button>

              <span class="invite-controls" *ngIf="r.status === 'NEW' || r.status === 'REVIEWED'">
                <select [name]="'role-' + r.id" [(ngModel)]="roleFor[r.id]" [attr.aria-label]="roleSelectLabel">
                  <option *ngFor="let role of roles" [value]="role">{{ role }}</option>
                </select>
                <button type="button" class="act-invite" (click)="invite(r)" [disabled]="busy()"
                        i18n="@@interestAdmin.act.invite">Invite</button>
              </span>

              <button type="button" class="act-erase" (click)="erase(r)" [disabled]="busy()"
                      i18n="@@interestAdmin.act.erase">Erase</button>

              <span class="row-note" *ngIf="noteFor[r.id]">{{ noteFor[r.id] }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  `,
  styles: [`
    .interest-requests { padding: 1rem; }
    .filter { display: flex; gap: 0.5rem; align-items: center; margin-block: 0.75rem 1rem; }
    .filter select, .filter button, .invite-controls select { min-height: 44px; }
    .rows { width: 100%; border-collapse: collapse; }
    .rows th, .rows td { text-align: start; padding: 0.5rem; border-bottom: 1px solid var(--line, #ddd); vertical-align: top; }
    .cell-actions button, .invite-controls { min-height: 44px; margin-inline-end: 0.375rem; margin-block-end: 0.25rem; }
    .cell-actions button { min-height: 44px; }
    .unverified { color: #666; font-size: 0.85em; }
    .error { color: #8a1b1b; font-weight: 600; }
    .row-note { display: inline-block; margin-inline-start: 0.5rem; color: #11337a; }
  `]
})
export class InterestRequestsComponent implements OnInit {
  private readonly api = inject(InterestRequestsService);

  readonly title = $localize`:@@interestAdmin.title:Access requests`;
  readonly filterLabel = $localize`:@@interestAdmin.filterLabel:Show:`;
  readonly roleSelectLabel = $localize`:@@interestAdmin.roleLabel:Invite as role`;
  private readonly loadFailed = $localize`:@@interestAdmin.loadFailed:Could not load requests. Please try again.`;
  private readonly actionFailed = $localize`:@@interestAdmin.actionFailed:That action could not be completed.`;
  private readonly conflictNote = $localize`:@@interestAdmin.conflict:Already actioned by someone else.`;
  private readonly invitedNote = $localize`:@@interestAdmin.invited:Invitation sent.`;
  private readonly alreadyMemberNote = $localize`:@@interestAdmin.alreadyMember:Already a member — marked resolved.`;
  private readonly erasedNote = $localize`:@@interestAdmin.erased:Erased.`;

  /** Default triage view EXCLUDES REVIEWED (FR-013). */
  filter: InterestStatusFilter = 'open';
  readonly roles: Role[] = ['RECRUITER', 'HIRING_MANAGER', 'INTERVIEWER', 'READ_ONLY', 'ADMIN'];
  /** Per-row selected invite role; defaults to RECRUITER. */
  roleFor: Record<string, Role> = {};
  /** Per-row transient outcome note. */
  noteFor: Record<string, string> = {};

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
    this.noteFor[r.id] = '';
    this.api.review(r.id).subscribe({
      next: () => this.afterAction(),
      error: (e) => this.onActionError(r, e)
    });
  }

  dismiss(r: InterestRequestRow): void {
    this.busy.set(true);
    this.noteFor[r.id] = '';
    this.api.dismiss(r.id).subscribe({
      next: () => this.afterAction(),
      error: (e) => this.onActionError(r, e)
    });
  }

  invite(r: InterestRequestRow): void {
    this.busy.set(true);
    this.noteFor[r.id] = '';
    const role = this.roleFor[r.id] ?? 'RECRUITER';
    this.api.invite(r.id, role).subscribe({
      next: (res) => {
        this.noteFor[r.id] = res.alreadyMember ? this.alreadyMemberNote : this.invitedNote;
        this.afterAction();
      },
      error: (e) => this.onActionError(r, e)
    });
  }

  /** Download the queue as injection-safe CSV for the current status filter (the backend neutralizes every cell). */
  exportCsv(): void {
    this.api.exportCsv(this.filter);
  }

  erase(r: InterestRequestRow): void {
    this.busy.set(true);
    this.noteFor[r.id] = '';
    this.api.erase(r.id).subscribe({
      next: () => {
        this.noteFor[r.id] = this.erasedNote;
        this.afterAction();
      },
      error: (e) => this.onActionError(r, e)
    });
  }

  private afterAction(): void {
    this.busy.set(false);
    this.load();
  }

  private onActionError(r: InterestRequestRow, e: { status?: number }): void {
    this.busy.set(false);
    this.noteFor[r.id] = e?.status === 409 ? this.conflictNote : this.actionFailed;
  }
}
