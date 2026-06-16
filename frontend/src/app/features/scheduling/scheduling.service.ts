import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface InitiateRequest {
  templateId: string;
  locationText?: string | null;
  rangeStart?: string | null;
  rangeEnd?: string | null;
}

export interface InitiateResponse {
  schedulingRequestId: string;
  status: string;
  offeredSlotCount: number;
  sentAt: string;
  expiresAt: string;
}

export interface StatusResponse {
  status: string;
  sentAt: string;
  expiresAt: string;
  chosenStart: string | null;
}

/** F13 recruiter scheduling API (contract A). */
@Injectable({ providedIn: 'root' })
export class SchedulingService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  initiate(candidateId: string, req: InitiateRequest): Observable<InitiateResponse> {
    return this.http.post<InitiateResponse>(
      `${this.base}/internal/candidates/${encodeURIComponent(candidateId)}/scheduling`, req);
  }

  status(candidateId: string): Observable<StatusResponse> {
    return this.http.get<StatusResponse>(
      `${this.base}/internal/candidates/${encodeURIComponent(candidateId)}/scheduling`);
  }
}
