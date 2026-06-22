import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RequisitionDto, RequisitionsService } from './requisitions.service';

/**
 * F51 requisition management (Admin internal screen): create/close requisitions, assign a Hiring Manager, and link
 * a candidate to a requisition. Minimal-but-real surface (the backlog scope for F51). All strings $localize.
 */
@Component({
  selector: 'app-requisitions',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <h1 i18n="@@req.title">Requisitions</h1>

    <section class="create">
      <h2 i18n="@@req.create.title">Create requisition</h2>
      <input [(ngModel)]="newTitle" placeholder="Job title" i18n-placeholder="@@req.title.ph" />
      <input [(ngModel)]="newLabel" placeholder="External label (optional)" i18n-placeholder="@@req.label.ph" />
      <button type="button" (click)="create()" [disabled]="!newTitle.trim()" i18n="@@req.create.btn">Create</button>
    </section>

    @if (error()) { <p class="error" role="alert">{{ errorMsg }}</p> }

    <table>
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
                <button type="button" (click)="close(r)" i18n="@@req.close">Close</button>
              } @else {
                <button type="button" (click)="reopen(r)" i18n="@@req.reopen">Reopen</button>
              }
              <input [(ngModel)]="assignMemberId[r.id]" placeholder="HM member id" i18n-placeholder="@@req.hm.ph" />
              <button type="button" (click)="assign(r)" i18n="@@req.assign">Assign HM</button>
            </td>
          </tr>
        }
      </tbody>
    </table>

    <section class="link">
      <h2 i18n="@@req.link.title">Link candidate to requisition</h2>
      <input [(ngModel)]="linkCandidateId" placeholder="Candidate id" i18n-placeholder="@@req.cand.ph" />
      <input [(ngModel)]="linkRequisitionId" placeholder="Requisition id" i18n-placeholder="@@req.req.ph" />
      <button type="button" (click)="link()" i18n="@@req.link.btn">Link</button>
    </section>
  `,
  styles: [`
    section { margin-bottom: 1.5rem; }
    input, button { min-height: 44px; margin-right: 0.5rem; }
    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: 0.5rem; border-bottom: 1px solid var(--line); }
    .error { color: var(--danger); }
  `]
})
export class RequisitionsComponent implements OnInit {
  private readonly svc = inject(RequisitionsService);

  readonly requisitions = signal<RequisitionDto[]>([]);
  readonly error = signal(false);
  readonly errorMsg = $localize`:@@req.error:Action failed. Try again.`;

  newTitle = '';
  newLabel = '';
  assignMemberId: Record<string, string> = {};
  linkCandidateId = '';
  linkRequisitionId = '';

  ngOnInit(): void {
    this.reload();
  }

  create(): void {
    if (!this.newTitle.trim()) { return; }
    this.svc.create(this.newTitle.trim(), this.newLabel.trim() || undefined).subscribe({
      next: () => { this.newTitle = ''; this.newLabel = ''; this.reload(); },
      error: () => this.error.set(true)
    });
  }

  close(r: RequisitionDto): void {
    this.svc.update(r.id, { status: 'CLOSED' }).subscribe({ next: () => this.reload(), error: () => this.error.set(true) });
  }

  reopen(r: RequisitionDto): void {
    this.svc.update(r.id, { status: 'OPEN' }).subscribe({ next: () => this.reload(), error: () => this.error.set(true) });
  }

  assign(r: RequisitionDto): void {
    const m = (this.assignMemberId[r.id] || '').trim();
    if (!m) { return; }
    this.svc.assignHm(r.id, m).subscribe({ next: () => { this.assignMemberId[r.id] = ''; }, error: () => this.error.set(true) });
  }

  link(): void {
    if (!this.linkCandidateId.trim()) { return; }
    this.svc.linkCandidate(this.linkCandidateId.trim(), this.linkRequisitionId.trim() || null).subscribe({
      next: () => { this.linkCandidateId = ''; this.linkRequisitionId = ''; },
      error: () => this.error.set(true)
    });
  }

  private reload(): void {
    this.svc.list().subscribe({ next: (r) => this.requisitions.set(r), error: () => this.error.set(true) });
  }
}
