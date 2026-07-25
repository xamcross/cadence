import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface PoolRule {
  memberIds: string[];
  n: number;
}

export interface TemplateRequest {
  name: string;
  durationMinutes: number;
  slotCadenceMinutes?: number | null;
  bufferBeforeMinutes: number;
  bufferAfterMinutes: number;
  dailyCapPerInterviewer: number;
  requiredMemberIds: string[];
  optionalMemberIds?: string[];
  pools?: PoolRule[];
}

export interface TemplateResponse {
  id: string;
  name: string;
  status: string; // ACTIVE | RETIRED
  durationMinutes: number;
  slotCadenceMinutes: number;
  bufferBeforeMinutes: number;
  bufferAfterMinutes: number;
  dailyCapPerInterviewer: number;
  requiredMemberIds: string[];
  optionalMemberIds: string[];
  pools: PoolRule[];
}

export interface TemplateList {
  templates: TemplateResponse[];
}

export interface ComputedSlot {
  start: string;
  end: string;
  zoneId: string;
  requiredMemberIds: string[];
  qualifyingByPool: Record<string, string[]>;
}

export interface MemberUnschedulable {
  memberId: string;
  reason: string; // NOT_CONNECTED | NEEDS_RECONNECTION | TEMPORARILY_UNAVAILABLE
}

export interface SlotComputationResponse {
  slots: ComputedSlot[];
  windowClamped: boolean;
  unschedulable: MemberUnschedulable[];
}

/**
 * Interview-template + rule-engine API client (F12). Recruiter/Admin only (server-enforced — the
 * route guard is defense-in-depth). Requests carry credentials + XSRF via the functional interceptor.
 */
@Injectable({ providedIn: 'root' })
export class InterviewTemplatesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/internal/interview-templates`;

  list(status = 'ACTIVE'): Observable<TemplateList> {
    return this.http.get<TemplateList>(this.base, { params: new HttpParams().set('status', status) });
  }

  create(body: TemplateRequest): Observable<TemplateResponse> {
    return this.http.post<TemplateResponse>(this.base, body);
  }

  update(id: string, body: TemplateRequest): Observable<TemplateResponse> {
    return this.http.put<TemplateResponse>(`${this.base}/${id}`, body);
  }

  retire(id: string): Observable<TemplateResponse> {
    return this.http.post<TemplateResponse>(`${this.base}/${id}/retire`, {});
  }

  computeSlots(id: string, rangeStart: string, rangeEnd: string): Observable<SlotComputationResponse> {
    return this.http.post<SlotComputationResponse>(`${this.base}/${id}/slots`, { rangeStart, rangeEnd });
  }
}
