import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface RequisitionDto {
  id: string;
  title: string;
  status: 'OPEN' | 'CLOSED';
  externalLabel: string | null;
  createdAt: string;
}

/**
 * F51 requisition management API client (Admin surface; candidate-link also usable by Recruiter). Server is the
 * authoritative role gate.
 */
@Injectable({ providedIn: 'root' })
export class RequisitionsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/internal/requisitions`;
  private readonly candidates = `${environment.apiBaseUrl}/internal/candidates`;

  list(): Observable<RequisitionDto[]> {
    return this.http.get<RequisitionDto[]>(this.base);
  }

  create(title: string, externalLabel?: string): Observable<RequisitionDto> {
    return this.http.post<RequisitionDto>(this.base, { title, externalLabel });
  }

  update(id: string, patch: { title?: string; status?: 'OPEN' | 'CLOSED' }): Observable<RequisitionDto> {
    return this.http.patch<RequisitionDto>(`${this.base}/${encodeURIComponent(id)}`, patch);
  }

  assignHm(id: string, memberId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${encodeURIComponent(id)}/assignments`, { memberId });
  }

  linkCandidate(candidateId: string, requisitionId: string | null): Observable<void> {
    return this.http.put<void>(`${this.candidates}/${encodeURIComponent(candidateId)}/requisition`, { requisitionId });
  }
}
