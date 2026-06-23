import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** F70 public interest submission payload (the honeypot `website` MUST be empty). */
export interface InterestSubmission {
  name: string;
  email: string;
  organization: string | null;
  message: string | null;
  /** Honeypot — kept empty by humans; a non-empty value is silently dropped server-side (no oracle). */
  website: string;
  /**
   * Client-side render timestamp (epoch ms) captured when the form is shown. The backend min-fill heuristic
   * (FR-002/R6) treats an impossibly fast fill (< the configured min-fill window) as a bot. A normal human fill
   * passes; absent -> the heuristic is skipped server-side.
   */
  formRenderedAtMillis: number;
}

/** F70 submit response envelope — byte-identical no-oracle 202 across every branch. */
export interface InterestSubmitResponse {
  status: 'received';
}

/**
 * F70 join / express-interest API client (contract: POST /api/public/interest) — PUBLIC, no session, no token.
 * The owning workspace is resolved server-side from config (never sent). The response is a neutral
 * `202 {"status":"received"}` whatever the email is (member / pending-invite / existing / unknown) — the client
 * never learns account existence. No data is persisted to web storage by the caller.
 */
@Injectable({ providedIn: 'root' })
export class InterestService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  submit(payload: InterestSubmission): Observable<InterestSubmitResponse> {
    return this.http.post<InterestSubmitResponse>(`${this.base}/public/interest`, payload);
  }
}
