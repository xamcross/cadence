import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface AuditEntry {
  eventType: string;
  outcome: string;
  actorMemberId: string | null;
  occurredAt: string;
}
export interface AuditLog {
  entries: AuditEntry[];
}
export interface ErasureRequestView {
  id: string;
  candidateId: string;
  status: string;
  reasonCode: string | null;
  createdAt: string;
}
export interface RequestsView {
  requests: ErasureRequestView[];
}
export interface FlaggedView {
  candidateId: string;
  retentionFlaggedAt: string;
  lastContactAt: string;
}
export interface FlaggedList {
  flagged: FlaggedView[];
}

/**
 * Admin/Recruiter GDPR API client (F04). Keyed by candidate internal id (no candidate browser — that
 * is F51). Responses carry no candidate PII. The server (method security) is the boundary; this is a
 * UX + defense-in-depth surface.
 */
@Injectable({ providedIn: 'root' })
export class GdprService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/internal`;

  erase(candidateId: string): Observable<{ status: string }> {
    return this.http.post<{ status: string }>(`${this.base}/candidates/${candidateId}/erasure`, {});
  }

  recordBasis(candidateId: string, lawfulBasis: string): Observable<{ basisRecorded: boolean }> {
    return this.http.put<{ basisRecorded: boolean }>(`${this.base}/candidates/${candidateId}/basis`, { lawfulBasis });
  }

  withdrawBasis(candidateId: string): Observable<{ basisWithdrawn: boolean }> {
    return this.http.delete<{ basisWithdrawn: boolean }>(`${this.base}/candidates/${candidateId}/basis`);
  }

  audit(candidateId: string): Observable<AuditLog> {
    return this.http.get<AuditLog>(`${this.base}/candidates/${candidateId}/audit`);
  }

  listRequests(): Observable<RequestsView> {
    return this.http.get<RequestsView>(`${this.base}/erasure-requests`);
  }

  confirmRequest(id: string): Observable<{ status: string }> {
    return this.http.post<{ status: string }>(`${this.base}/erasure-requests/${id}/confirm`, {});
  }

  rejectRequest(id: string, reasonCode: string): Observable<{ status: string }> {
    return this.http.post<{ status: string }>(`${this.base}/erasure-requests/${id}/reject`, { reasonCode });
  }

  listFlagged(): Observable<FlaggedList> {
    return this.http.get<FlaggedList>(`${this.base}/retention/flagged`);
  }

  deleteFlagged(candidateId: string): Observable<{ status: string }> {
    return this.http.post<{ status: string }>(`${this.base}/retention/${candidateId}/delete`, {});
  }
}
