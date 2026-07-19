import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription, interval } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { TableScrollComponent } from '../../shared/ui/table-scroll.component';
import {
  BulkResult, PipelineListQuery, PipelineRow, PipelineService, PipelineSort, PipelineStatusFilter,
  SchedulingStatus, SlaState
} from './pipeline.service';

/**
 * F51 Pipeline View — the recruiter's primary working list (internal staff screen; no candidate-facing §IX gate,
 * the F50/F51 precedent). Sortable/filterable rows colour-coded by SLA + scheduling status, bulk actions
 * (Admin/Recruiter only), and a per-row link to the candidate timeline. Refreshes on a 60s poll (FR-006). All
 * server-side scoping (HM -> assigned requisitions) is enforced by the backend; the UI never receives out-of-scope
 * rows. All strings $localize.
 */
@Component({
  selector: 'app-pipeline-list',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink,
    PageHeaderComponent, EmptyStateComponent, SkeletonComponent, TableScrollComponent
  ],
  templateUrl: './pipeline-list.component.html',
  styleUrls: ['./pipeline-list.component.scss']
})
export class PipelineListComponent implements OnInit, OnDestroy {
  private readonly pipeline = inject(PipelineService);
  private readonly auth = inject(AuthService);
  private poll?: Subscription;

  readonly rows = signal<PipelineRow[]>([]);
  readonly loading = signal(false);
  readonly error = signal(false);
  readonly truncated = signal(false);
  readonly canBulk = signal(false);
  readonly selected = signal<Set<string>>(new Set());
  readonly bulkResults = signal<BulkResult[] | null>(null);

  // filters / sort (bound via ngModel)
  status: PipelineStatusFilter = 'ACTIVE';
  sla: SlaState | '' = '';
  scheduling: SchedulingStatus | '' = '';
  stage = '';
  sort: PipelineSort = 'RECENT';

  readonly title = $localize`:@@pipeline.title:Pipeline`;
  readonly errorMsg = $localize`:@@pipeline.error:Could not load the pipeline. Try again.`;
  readonly truncatedMsg = $localize`:@@pipeline.truncated:Showing the first results only - narrow the filters to see more`;
  readonly sendUpdate = $localize`:@@pipeline.bulk.update:Send update email`;

  ngOnInit(): void {
    this.auth.me().subscribe({
      next: (m) => this.canBulk.set(m.role === 'ADMIN' || m.role === 'RECRUITER'),
      error: () => this.canBulk.set(false)
    });
    this.load();
    // 60s poll refresh (FR-006/SC-003). Pure client timer; no realtime push (constitution IV).
    this.poll = interval(60000).subscribe(() => this.load());
  }

  ngOnDestroy(): void {
    this.poll?.unsubscribe();
  }

  applyFilters(): void {
    this.load();
  }

  toggle(id: string): void {
    const next = new Set(this.selected());
    if (next.has(id)) { next.delete(id); } else { next.add(id); }
    this.selected.set(next);
  }

  isSelected(id: string): boolean {
    return this.selected().has(id);
  }

  sendUpdateEmail(): void {
    const ids = Array.from(this.selected());
    if (ids.length === 0) { return; }
    this.bulkResults.set(null);
    this.pipeline.bulk('SEND_UPDATE_EMAIL', ids).subscribe({
      next: (r) => { this.bulkResults.set(r.results); this.selected.set(new Set()); this.load(); },
      error: () => this.error.set(true)
    });
  }

  private query(): PipelineListQuery {
    return {
      status: this.status,
      sla: this.sla || undefined,
      scheduling: this.scheduling || undefined,
      stage: this.stage || undefined,
      sort: this.sort
    };
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(false);
    this.pipeline.list(this.query()).subscribe({
      next: (p) => {
        this.rows.set(p.rows);
        this.truncated.set(p.truncated);
        this.loading.set(false);
      },
      error: () => { this.error.set(true); this.loading.set(false); }
    });
  }
}
