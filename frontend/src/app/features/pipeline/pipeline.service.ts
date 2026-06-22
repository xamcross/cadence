import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export type SlaState = 'GREEN' | 'AMBER' | 'RED';
export type SchedulingStatus =
  | 'NO_LINK_SENT' | 'LINK_SENT' | 'SLOT_PICKED' | 'CONFIRMED' | 'NO_SHOW' | 'RESCHEDULED' | 'CANCELLED' | 'EXPIRED';
export type PipelineSort = 'STAGE' | 'SLA' | 'SCHEDULING' | 'RECENT';
export type PipelineStatusFilter = 'ACTIVE' | 'INCLUDE_CLOSED';
export type BulkAction = 'SEND_SCHEDULING_LINK' | 'SEND_UPDATE_EMAIL';

export interface PipelineRow {
  candidateId: string;
  name: string;
  stage: string;
  slaState: SlaState;
  schedulingStatus: SchedulingStatus;
  requisitionId: string | null;
  requisitionTitle: string | null;
  lastActivityAt: string | null;
}

export interface PipelinePage {
  rows: PipelineRow[];
  page: number;
  size: number;
  totalInScope: number;
  /** Rows after filters, before pagination — the honest pager total (use this, not totalInScope). */
  filteredCount: number;
  truncated: boolean;
}

export interface BulkResult {
  candidateId: string;
  outcome: 'ENQUEUED' | 'SENT' | 'SKIPPED';
  reason: string | null;
}

export interface TimelineEvent {
  occurredAt: string;
  type: string;
  label: string;
}

export interface TimelineResponse {
  candidateId: string;
  events: TimelineEvent[];
  feedbackPending: boolean;
}

export interface PipelineListQuery {
  status?: PipelineStatusFilter;
  requisitionId?: string;
  sla?: SlaState;
  scheduling?: SchedulingStatus;
  stage?: string;
  sort?: PipelineSort;
  page?: number;
  size?: number;
}

/**
 * F51 Pipeline View API client (internal Admin/Recruiter/Read-only/Hiring-Manager screen). Visibility + bulk role
 * gating are enforced server-side; this client only shapes requests. No candidate token / no web storage.
 */
@Injectable({ providedIn: 'root' })
export class PipelineService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/internal/pipeline`;

  list(q: PipelineListQuery): Observable<PipelinePage> {
    let params = new HttpParams();
    if (q.status) params = params.set('status', q.status);
    if (q.requisitionId) params = params.set('requisitionId', q.requisitionId);
    if (q.sla) params = params.set('sla', q.sla);
    if (q.scheduling) params = params.set('scheduling', q.scheduling);
    if (q.stage) params = params.set('stage', q.stage);
    if (q.sort) params = params.set('sort', q.sort);
    if (q.page != null) params = params.set('page', String(q.page));
    if (q.size != null) params = params.set('size', String(q.size));
    return this.http.get<PipelinePage>(this.base, { params });
  }

  bulk(action: BulkAction, candidateIds: string[], opts?: {
    templateId?: string; locationText?: string; rangeStart?: string; rangeEnd?: string; messageType?: string;
  }): Observable<{ results: BulkResult[] }> {
    return this.http.post<{ results: BulkResult[] }>(`${this.base}/bulk`, { action, candidateIds, ...opts });
  }

  timeline(candidateId: string): Observable<TimelineResponse> {
    return this.http.get<TimelineResponse>(`${this.base}/candidates/${encodeURIComponent(candidateId)}/timeline`);
  }
}
