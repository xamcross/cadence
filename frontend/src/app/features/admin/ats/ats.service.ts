import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface AtsHealth {
  provider: string;
  status: string;
  credentialSet: boolean;
  lastVerifiedAt: string | null;
  lastSyncAt: string | null;
  degraded: boolean;
  deadLetterCount: number;
  // 032 T7/T9: true when a previously configured connection is retained but sync/write-back is
  // paused because the workspace has fallen back to the FREE plan (US2-AS2).
  pausedForPlan: boolean;
}

export interface AtsSyncStatus {
  lastSyncAt: string | null;
  lastOutcome: string | null;
  processed: number;
  created: number;
  updated: number;
  skipped: number;
}

export interface AtsDeadLetter {
  writeBackId: string;
  candidateId: string;
  type: string;
  attemptCount: number;
  lastOutcomeCategory: string | null;
  updatedAt: string | null;
}

/**
 * F40/F41 ATS integration API client. The provider API key is write-only — it is sent on connect() but is
 * NEVER present in any response (only credentialSet). F41: every call is provider-scoped, and getConnections()
 * lists every provider's health (Greenhouse + Lever) for the both-providers Admin surface. Internal Admin
 * screen (no candidate PII surface).
 */
@Injectable({ providedIn: 'root' })
export class AtsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/internal/ats`;

  /** Health for every provider (the both-providers status surface). */
  getConnections(): Observable<AtsHealth[]> {
    return this.http.get<AtsHealth[]>(`${this.base}/connections`);
  }

  connect(provider: string, apiKey: string): Observable<AtsHealth> {
    return this.http.post<AtsHealth>(`${this.base}/${provider}/connection`, { apiKey });
  }

  disconnect(provider: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${provider}/connection`);
  }

  syncStatus(provider: string): Observable<AtsSyncStatus> {
    return this.http.get<AtsSyncStatus>(`${this.base}/${provider}/sync-status`);
  }

  deadLetters(provider: string): Observable<AtsDeadLetter[]> {
    return this.http.get<AtsDeadLetter[]>(`${this.base}/${provider}/dead-letters`);
  }
}
