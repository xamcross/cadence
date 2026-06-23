import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Role } from '../../../core/auth/auth.models';

/** F70 status filter (FR-013): `open` is the default triage view and EXCLUDES REVIEWED. */
export type InterestStatusFilter = 'open' | 'reviewed' | 'invited' | 'dismissed' | 'all';

export type InterestStatus = 'NEW' | 'REVIEWED' | 'INVITED' | 'DISMISSED';

/** One row in the admin queue. Email/organization are submitter-claimed → constant `*Unverified` flags. */
export interface InterestRequestRow {
  id: string;
  name: string;
  email: string;
  emailUnverified: boolean;
  organization: string | null;
  organizationUnverified: boolean;
  message: string | null;
  status: InterestStatus;
  submittedAt: string;
}

export interface InterestListResponse {
  requests: InterestRequestRow[];
}

/** Action result envelopes. `invite` may carry `invitationId` and/or `alreadyMember` (FR-015). */
export interface InterestActionResponse {
  status: string;
  invitationId?: string | null;
  alreadyMember?: boolean | null;
}

/**
 * F70 interest-request review-queue API client (internal Admin-only screen). All endpoints are workspace-scoped
 * server-side from the session principal; the actor/workspace are NEVER sent. Invite issues a real invitation via
 * the existing invitation flow (the chosen role is passed in the body). RBAC is enforced server-side (the route
 * roleGuard('ADMIN') is the client-side gate; role.guard.spec covers it).
 */
@Injectable({ providedIn: 'root' })
export class InterestRequestsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/internal/interest-requests`;

  list(status: InterestStatusFilter): Observable<InterestListResponse> {
    return this.http.get<InterestListResponse>(`${this.base}?status=${encodeURIComponent(status)}`);
  }

  review(id: string): Observable<InterestActionResponse> {
    return this.http.post<InterestActionResponse>(`${this.base}/${encodeURIComponent(id)}/review`, {});
  }

  dismiss(id: string): Observable<InterestActionResponse> {
    return this.http.post<InterestActionResponse>(`${this.base}/${encodeURIComponent(id)}/dismiss`, {});
  }

  invite(id: string, role: Role): Observable<InterestActionResponse> {
    return this.http.post<InterestActionResponse>(`${this.base}/${encodeURIComponent(id)}/invite`, { role });
  }

  erase(id: string): Observable<InterestActionResponse> {
    return this.http.post<InterestActionResponse>(`${this.base}/${encodeURIComponent(id)}/erase`, {});
  }

  /** CSV export URL for the current status filter (same-origin GET; the session cookie authenticates). */
  exportUrl(status: InterestStatusFilter): string {
    return `${this.base}/export?status=${encodeURIComponent(status)}`;
  }

  /**
   * Trigger a CSV download for the current status filter (same-origin GET; the browser saves the attachment). The
   * backend neutralizes every free-text cell via CsvInjectionEscaper and is the authoritative Admin-only role gate
   * (the DashboardService.download precedent).
   */
  exportCsv(status: InterestStatusFilter): void {
    const a = document.createElement('a');
    a.href = this.exportUrl(status);
    a.rel = 'noopener';
    a.click();
  }
}
