import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RequisitionDto, RequisitionsService } from './requisitions.service';
import { PageHeaderComponent } from '../../../shared/ui/page-header.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { SkeletonComponent } from '../../../shared/ui/skeleton.component';
import { TableScrollComponent } from '../../../shared/ui/table-scroll.component';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';
import { PickerOption, SearchPickerComponent } from '../../../shared/ui/search-picker.component';
import { MembersService } from '../members/members.service';
import { PipelineService } from '../../pipeline/pipeline.service';

/**
 * F51 requisition management (Admin internal screen): create/close requisitions, assign a Hiring Manager, and link
 * a candidate to a requisition. Minimal-but-real surface (the backlog scope for F51). All strings $localize.
 *
 * Phase 3b (workbench overhaul): `close(r)` is gated behind the shared `ConfirmDialogService`; `reopen`
 * is intentionally left ungated. create/close/reopen/assign/link each surface a per-action outcome via
 * `ToastService`; the boolean `error` signal now covers only the initial list-load failure.
 */
@Component({
  selector: 'app-requisitions',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    PageHeaderComponent, EmptyStateComponent, SkeletonComponent, TableScrollComponent, SearchPickerComponent
  ],
  template: `
    <app-page-header
      eyebrow="Administration" i18n-eyebrow="@@req.eyebrow"
      heading="Requisitions" i18n-heading="@@req.title"
      subtitle="Open roles and hiring-manager assignment." i18n-subtitle="@@req.subtitle">
    </app-page-header>

    <section class="create">
      <h2 i18n="@@req.create.title">Create requisition</h2>
      <input class="input" [(ngModel)]="newTitle" placeholder="Job title" i18n-placeholder="@@req.title.ph" />
      <input class="input" [(ngModel)]="newLabel" placeholder="External label (optional)" i18n-placeholder="@@req.label.ph" />
      <button type="button" class="btn btn--primary" (click)="create()" [disabled]="!newTitle.trim()" i18n="@@req.create.btn">Create</button>
    </section>

    @if (error()) { <p class="error alert alert--danger" role="alert">{{ errorMsg }}</p> }

    @if (loading()) {
      <app-skeleton variant="table" />
    } @else if (requisitions().length === 0) {
      <app-empty-state
        heading="No requisitions yet" i18n-heading="@@req.empty.heading"
        body="Create your first requisition to start assigning candidates." i18n-body="@@req.empty.body">
      </app-empty-state>
    } @else {
      <app-table-scroll ariaLabel="Requisitions" i18n-ariaLabel="@@req.tableLabel">
        <table class="table">
          <thead><tr>
            <th i18n="@@req.col.title">Title</th>
            <th i18n="@@req.col.status">Status</th>
            <th i18n="@@req.col.label">Label</th>
            <th i18n="@@req.col.actions">Actions</th>
          </tr></thead>
          <tbody>
            @for (r of requisitions(); track r.id) {
              <tr>
                <td>{{ r.title }}</td>
                <td>{{ r.status }}</td>
                <td>{{ r.externalLabel || '-' }}</td>
                <td>
                  @if (r.status === 'OPEN') {
                    <button type="button" class="btn btn--danger-soft btn--sm" (click)="close(r)" i18n="@@req.close">Close</button>
                  } @else {
                    <button type="button" class="btn btn--outline btn--sm" (click)="reopen(r)" i18n="@@req.reopen">Reopen</button>
                  }
                  <app-search-picker class="member-picker" [options]="memberOpts()" [value]="assignMemberId[r.id]"
                    (valueChange)="assignMemberId[r.id] = $event ?? ''"
                    label="Hiring manager" i18n-label="@@req.hm.picker.label"
                    placeholder="Search members…" i18n-placeholder="@@req.hm.picker.placeholder">
                  </app-search-picker>
                  <button type="button" class="btn btn--outline btn--sm" (click)="assign(r)" i18n="@@req.assign">Assign HM</button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </app-table-scroll>
    }

    <section class="link">
      <h2 i18n="@@req.link.title">Link candidate to requisition</h2>
      <div class="field">
        <app-search-picker [options]="candidateOpts()" [value]="linkCandidateId"
          (valueChange)="linkCandidateId = $event ?? ''"
          label="Candidate" i18n-label="@@req.cand.picker.label"
          placeholder="Search candidates…" i18n-placeholder="@@req.cand.picker.placeholder">
        </app-search-picker>
      </div>
      <div class="field">
        <app-search-picker [options]="requisitionOpts()" [value]="linkRequisitionId"
          (valueChange)="linkRequisitionId = $event ?? ''"
          label="Requisition" i18n-label="@@req.req.picker.label"
          placeholder="Search requisitions…" i18n-placeholder="@@req.req.picker.placeholder">
        </app-search-picker>
      </div>
      <button type="button" class="btn btn--primary" (click)="link()" i18n="@@req.link.btn">Link</button>
    </section>
  `,
  styles: [`
    section { margin-bottom: var(--space-6); }
    .create input, .link input { max-width: 22rem; margin-right: var(--space-2); margin-bottom: var(--space-2); }
    td input { max-width: 12rem; margin-right: var(--space-2); }
    td .btn { margin-right: var(--space-2); }
    .error { margin-bottom: var(--space-4); }
  `]
})
export class RequisitionsComponent implements OnInit {
  private readonly svc = inject(RequisitionsService);
  private readonly confirm = inject(ConfirmDialogService);
  private readonly toast = inject(ToastService);
  private readonly membersApi = inject(MembersService);
  private readonly pipelineApi = inject(PipelineService);

  readonly requisitions = signal<RequisitionDto[]>([]);
  readonly loading = signal(true);
  readonly error = signal(false);
  readonly errorMsg = $localize`:@@req.error:Action failed. Try again.`;

  // Workbench overhaul phase 5: picker options for the member/candidate/requisition combobox fields.
  readonly memberOpts = signal<readonly PickerOption[]>([]);
  readonly candidateOpts = signal<readonly PickerOption[]>([]);
  /** No new fetch — reuses the already-loaded requisitions() list. */
  readonly requisitionOpts = computed<readonly PickerOption[]>(() =>
    this.requisitions().map((r) => ({ id: r.id, label: r.title, hint: r.status })));

  newTitle = '';
  newLabel = '';
  assignMemberId: Record<string, string> = {};
  linkCandidateId = '';
  linkRequisitionId = '';

  ngOnInit(): void {
    this.reload();
    this.membersApi.getMembers().subscribe({
      next: (members) => this.memberOpts.set(members.map((m) => ({ id: m.memberId, label: m.displayName, hint: m.role }))),
      error: () => this.memberOpts.set([])
    });
    this.pipelineApi.list({ status: 'ACTIVE', size: 1000 }).subscribe({
      next: (p) => this.candidateOpts.set(p.rows.map((r) => ({ id: r.candidateId, label: r.name, hint: r.stage }))),
      error: () => this.candidateOpts.set([])
    });
  }

  create(): void {
    if (!this.newTitle.trim()) { return; }
    this.svc.create(this.newTitle.trim(), this.newLabel.trim() || undefined).subscribe({
      next: () => {
        this.newTitle = '';
        this.newLabel = '';
        this.toast.success($localize`:@@toast.req.created:Requisition created.`);
        this.reload();
      },
      error: () => this.toast.error($localize`:@@toast.req.createFailed:Couldn't create the requisition.`)
    });
  }

  async close(r: RequisitionDto): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.req.close.title:Close this requisition?`,
      body: $localize`:@@confirm.req.close.body:"${r.title}:title:" will be closed. You can reopen it later.`,
      confirmLabel: $localize`:@@confirm.req.close.cta:Close requisition`
    });
    if (!ok) { return; }
    this.svc.update(r.id, { status: 'CLOSED' }).subscribe({
      next: () => {
        this.toast.success($localize`:@@toast.req.closed:Requisition closed.`);
        this.reload();
      },
      error: () => this.toast.error($localize`:@@toast.req.closeFailed:Couldn't close requisition.`)
    });
  }

  reopen(r: RequisitionDto): void {
    this.svc.update(r.id, { status: 'OPEN' }).subscribe({
      next: () => {
        this.toast.success($localize`:@@toast.req.reopened:Requisition reopened.`);
        this.reload();
      },
      error: () => this.toast.error($localize`:@@toast.req.reopenFailed:Couldn't reopen the requisition.`)
    });
  }

  assign(r: RequisitionDto): void {
    const m = (this.assignMemberId[r.id] || '').trim();
    if (!m) { return; }
    this.svc.assignHm(r.id, m).subscribe({
      next: () => {
        this.assignMemberId[r.id] = '';
        this.toast.success($localize`:@@toast.req.assigned:Hiring manager assigned.`);
      },
      error: () => this.toast.error($localize`:@@toast.req.assignFailed:Couldn't assign the hiring manager.`)
    });
  }

  link(): void {
    if (!this.linkCandidateId.trim()) { return; }
    this.svc.linkCandidate(this.linkCandidateId.trim(), this.linkRequisitionId.trim() || null).subscribe({
      next: () => {
        this.linkCandidateId = '';
        this.linkRequisitionId = '';
        this.toast.success($localize`:@@toast.req.linked:Candidate linked.`);
      },
      error: () => this.toast.error($localize`:@@toast.req.linkFailed:Couldn't link the candidate.`)
    });
  }

  private reload(): void {
    this.svc.list().subscribe({
      next: (r) => { this.requisitions.set(r); this.loading.set(false); },
      error: () => { this.error.set(true); this.loading.set(false); }
    });
  }
}
