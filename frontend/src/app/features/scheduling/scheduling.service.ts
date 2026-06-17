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
  // F23 no-show defense: confirmation-cascade state surfaced for the booking-status view (all optional —
  // present once the booking is BOOKED and the cascade has run). Times-only / boolean — no PII.
  confirmationRequested?: boolean;
  candidateConfirmed?: boolean;
  escalated?: boolean;
}

/** F30 candidate-status outcome — mirrors the server `CandidateStatusOutcome` enum. */
export type StatusOutcome = 'IN_PROGRESS' | 'COMPLETE_OFFER' | 'COMPLETE_REJECTED';

/** F30 publish-status request (contract C). */
export interface PublishStatusRequest {
  outcome: StatusOutcome;
  stage?: string | null;
  nextStep: string;
  expectedDate?: string | null; // ISO date — required for IN_PROGRESS
}

/** F30 recruiter status read (contract D) — decrypted status + the current candidate status link. */
export interface RecruiterStatusResponse {
  displayState?: string | null;
  outcome?: StatusOutcome | null;
  stage?: string | null;
  nextStep?: string | null;
  expectedDate?: string | null;
  statusLink: string;
  publishedAt?: string | null;
}

/** F30 rotate-link response (contract E). */
export interface RotateLinkResponse {
  statusLink: string;
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

  /** F20: recruiter-initiated reschedule — re-invites the candidate, preserving the existing booking (US2). */
  reschedule(candidateId: string): Observable<{ status: string; invitedAt: string }> {
    return this.http.post<{ status: string; invitedAt: string }>(
      `${this.base}/internal/candidates/${encodeURIComponent(candidateId)}/scheduling/reschedule`, {});
  }

  /** F20: recruiter-initiated cancellation — notifies the candidate (US3). */
  cancel(candidateId: string): Observable<{ status: string; at: string }> {
    return this.http.post<{ status: string; at: string }>(
      `${this.base}/internal/candidates/${encodeURIComponent(candidateId)}/scheduling/cancel`, {});
  }

  /**
   * F23: recruiter one-tap release of an unconfirmed slot (US2). Removes the calendar events for all
   * participants, frees the slot for re-scheduling, and notifies the candidate — turning a likely no-show
   * into a recovered slot. Reuses the F20 cancellation primitive server-side.
   */
  release(candidateId: string): Observable<{ status: string; at: string; cleanupIncomplete: boolean }> {
    return this.http.post<{ status: string; at: string; cleanupIncomplete: boolean }>(
      `${this.base}/internal/candidates/${encodeURIComponent(candidateId)}/scheduling/release`, {});
  }

  // ---- F30 candidate status (contracts C/D/E) ----

  /** F30: publish/update the candidate's honest status (Recruiter/Admin). */
  publishStatus(candidateId: string, req: PublishStatusRequest): Observable<RecruiterStatusResponse> {
    return this.http.put<RecruiterStatusResponse>(
      `${this.base}/internal/candidates/${encodeURIComponent(candidateId)}/status`, req);
  }

  /** F30: read the persisted status + the current candidate status link (re-derived). */
  readStatus(candidateId: string): Observable<RecruiterStatusResponse> {
    return this.http.get<RecruiterStatusResponse>(
      `${this.base}/internal/candidates/${encodeURIComponent(candidateId)}/status`);
  }

  /** F30: rotate the status link — the previous link stops resolving (SC-011). */
  rotateStatusLink(candidateId: string): Observable<RotateLinkResponse> {
    return this.http.post<RotateLinkResponse>(
      `${this.base}/internal/candidates/${encodeURIComponent(candidateId)}/status/rotate-link`, {});
  }
}
