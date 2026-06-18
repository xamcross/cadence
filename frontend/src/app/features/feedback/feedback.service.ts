import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** F32 scorecard form view (contract A) — a 200 state-envelope; the FORM state carries the blank form only. */
export interface ScorecardFormView {
  state: 'FORM' | 'USED' | 'EXPIRED';
  interviewLabel?: string | null;
  recommendationOptions?: string[] | null;
  ratingDimensions?: string[] | null;
}

export interface ScorecardRating {
  dimension: string;
  score: number;
}

export interface ScorecardSubmission {
  recommendation: string;
  ratings: ScorecardRating[];
  comment: string | null;
}

/** F32 submit result envelope. */
export interface SubmitResponse {
  state: 'SUBMITTED' | 'USED' | 'EXPIRED';
}

/**
 * F32 interviewer scorecard API (contract A/B) — PUBLIC, token-only, no session. The token is the auth: the
 * server resolves the request solely from the hashed token. Load is a GET; submit is an affirmative POST. The
 * token is `encodeURIComponent`-encoded (the `status.service.ts` pattern); responses are `no-store` server-side
 * and the SPA inherits the global `_headers` (`no-referrer` + CSP). It is WRITE-ONLY — no endpoint returns a
 * submitted scorecard (the recruiter read is the only content path).
 */
@Injectable({ providedIn: 'root' })
export class FeedbackService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  load(token: string): Observable<ScorecardFormView> {
    return this.http.get<ScorecardFormView>(`${this.base}/feedback/${encodeURIComponent(token)}`);
  }

  submit(token: string, body: ScorecardSubmission): Observable<SubmitResponse> {
    return this.http.post<SubmitResponse>(`${this.base}/feedback/${encodeURIComponent(token)}`, body);
  }
}
