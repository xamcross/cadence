import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface ConnectionRow {
  provider: string; // GOOGLE | MICROSOFT
  status: string; // CONNECTED | NEEDS_RECONNECTION
  connectedAccount: string | null;
  connectedAt: string | null;
}
export interface ConnectionList {
  connections: ConnectionRow[];
}
export interface StartResponse {
  authorizationUrl: string;
}
export interface BusyInterval {
  start: string;
  end: string;
}
export interface AvailabilityPreview {
  provider: string | null;
  status: string; // DATA | NOT_CONNECTED | NEEDS_RECONNECTION | TEMPORARILY_UNAVAILABLE
  windowStart: string;
  windowEnd: string;
  busy: BusyInterval[];
}

/**
 * Member-self calendar connection API client (F01.1). Every call is scoped server-side to the
 * authenticated member (no memberId in any path). Requests carry credentials + XSRF via the
 * functional interceptor (same base as the other internal services).
 */
@Injectable({ providedIn: 'root' })
export class CalendarService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/internal/calendar/connections`;

  list(): Observable<ConnectionList> {
    return this.http.get<ConnectionList>(this.base);
  }

  start(provider: string): Observable<StartResponse> {
    return this.http.post<StartResponse>(`${this.base}/${provider}/start`, {});
  }

  disconnect(provider: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${provider}`);
  }

  /** F10: the signed-in member's own availability for the next preview window (self-scoped server-side). */
  previewAvailability(): Observable<AvailabilityPreview> {
    return this.http.get<AvailabilityPreview>(
      `${environment.apiBaseUrl}/internal/calendar/availability/preview`
    );
  }
}
