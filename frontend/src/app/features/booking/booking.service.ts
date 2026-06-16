import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** A2 reschedule-open slot (times only — same shape as the F13 candidate slot, reused at confirm). */
export interface BookingSlot {
  slotId: string;
  start: string;
  end: string;
  zoneId: string;
}

/** A1 GET /api/candidate/booking/{token} — current booking + manage capabilities. Times only, no PII. */
export interface BookingView {
  status: string; // booked | cancelled | rescheduled
  bookedStart: string | null;
  zoneId: string | null;
  at: string | null; // terminal-state timestamp (cancelled / rescheduled)
  canReschedule: boolean;
  canCancel: boolean;
  rescheduleRemaining: number;
}

/** A2 POST /api/candidate/booking/{token}/reschedule — opens a reschedule round. */
export interface OpenRescheduleResponse {
  rescheduleToken: string;
  zoneHint: string;
  slots: BookingSlot[];
}

/** A3 POST /api/candidate/booking/{token}/cancel — affirmative cancellation. */
export interface CancelResponse {
  status: string; // cancelled | cleanup_incomplete
  at: string;
}

/**
 * F20 candidate booking-management API (contract A) — public, manage-token-only, no session.
 * The token is bound to the booking lifecycle; the server resolves the target booking solely from
 * the credential (FR-017a — no id in the body overrides the binding). The reschedule confirm step
 * reuses the F13 ScheduleService.confirm(rescheduleToken, slotId) verbatim (contract B2).
 */
@Injectable({ providedIn: 'root' })
export class BookingService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  view(token: string): Observable<BookingView> {
    return this.http.get<BookingView>(`${this.base}/candidate/booking/${encodeURIComponent(token)}`);
  }

  openReschedule(token: string): Observable<OpenRescheduleResponse> {
    return this.http.post<OpenRescheduleResponse>(
      `${this.base}/candidate/booking/${encodeURIComponent(token)}/reschedule`, {});
  }

  /** Affirmative cancel — fired ONLY from an explicit confirmation click (never on page load). */
  cancel(token: string): Observable<CancelResponse> {
    return this.http.post<CancelResponse>(
      `${this.base}/candidate/booking/${encodeURIComponent(token)}/cancel`, {});
  }
}
