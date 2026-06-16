import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface CandidateSlot {
  slotId: string;
  start: string;
  end: string;
  zoneId: string;
}

export interface CandidateSlotsResponse {
  status: string; // open | booked
  zoneHint: string;
  bookedStart: string | null;
  slots: CandidateSlot[];
}

export interface ConfirmResponse {
  status: string;
  bookedStart: string;
  zoneId: string;
}

/** F13 candidate self-scheduling API (contract B) — public, token-only, no session. */
@Injectable({ providedIn: 'root' })
export class ScheduleService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  view(token: string): Observable<CandidateSlotsResponse> {
    return this.http.get<CandidateSlotsResponse>(`${this.base}/candidate/scheduling/${encodeURIComponent(token)}`);
  }

  confirm(token: string, slotId: string): Observable<ConfirmResponse> {
    return this.http.post<ConfirmResponse>(
      `${this.base}/candidate/scheduling/${encodeURIComponent(token)}/confirm`, { slotId });
  }
}
