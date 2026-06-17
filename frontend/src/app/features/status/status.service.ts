import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * F30 candidate status view (contract A) — the server resolves a single `displayState` and returns only the
 * data that state needs (recruiter-authored status text the candidate is meant to see + `workspaceZone`). No
 * candidate id, no PII beyond the status text, no display copy (the page owns copy). Times-only / enum.
 */
export interface CandidateStatusView {
  displayState: 'PUBLISHED' | 'PAST_DATE' | 'TERMINAL' | 'UNDER_REVIEW';
  stage?: string | null;
  nextStep?: string | null;
  expectedDate?: string | null; // ISO date (LocalDate) — present for PUBLISHED/PAST_DATE
  outcome?: 'IN_PROGRESS' | 'COMPLETE_OFFER' | 'COMPLETE_REJECTED' | null;
  workspaceZone: string;
}

/** F30 erasure-submit ack (contract B) — constant, oracle-free. */
export interface ErasureAckResponse {
  status: string; // received
}

/** F03 public branding (logo + colour only — no candidate PII, no setting/credential). */
export interface PublicBranding {
  brandColor: string;
  logoUrl: string;
}

/**
 * F30 candidate status-page API (contract A/B) — PUBLIC, status-token-only, no session. The token is the
 * auth: the server resolves the candidate solely from the hashed token (no id in the path/body overrides the
 * binding). View is a GET; erasure-request is an affirmative POST (a GET → 405 so a prefetch/scanner cannot
 * trigger it). `encodeURIComponent` the token (the `booking.service.ts` pattern); responses are `no-store`
 * server-side and the SPA inherits the global `_headers` (`no-referrer` + CSP).
 */
@Injectable({ providedIn: 'root' })
export class StatusService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  view(token: string): Observable<CandidateStatusView> {
    return this.http.get<CandidateStatusView>(
      `${this.base}/candidate/status/${encodeURIComponent(token)}`,
      { context: new HttpContext() });
  }

  /** Affirmative erasure request — fired ONLY from an explicit "Request data deletion" click (never on load). */
  requestErasure(token: string): Observable<ErasureAckResponse> {
    return this.http.post<ErasureAckResponse>(
      `${this.base}/candidate/status/${encodeURIComponent(token)}/erasure-request`, {});
  }

  /** Public workspace branding (logo + brand colour) — composed onto the status page, never candidate PII. */
  branding(): Observable<PublicBranding> {
    return this.http.get<PublicBranding>(`${this.base}/public/workspace/branding`);
  }
}
