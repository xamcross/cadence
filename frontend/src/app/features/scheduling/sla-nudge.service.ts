import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** F31 SLA communication-health state — mirrors the server `SlaState` enum. */
export type SlaState = 'GREEN' | 'AMBER' | 'RED';

/** F31 per-candidate SLA (contract B) / silence-list item (contract A). */
export interface CandidateSla {
  candidateId: string;
  slaState: SlaState;
  lastActivityAt: string | null;
  openDraftId: string | null;
}

/** F31 rendered holding-message preview (contract C). */
export interface DraftPreview {
  messageType: string;
  subject: string;
  body: string;
  missingFields: string[];
}

/** F31 approve/dismiss result (contract D/E). */
export interface ActionResult {
  draftId: string;
  result: string;
}

/**
 * F31 SLA Nudge recruiter API (internal). The silence WINDOW is set via the F03 workspace-settings screen —
 * not here. This service reads the per-candidate SLA state and the queued draft, and approves/dismisses it.
 */
@Injectable({ providedIn: 'root' })
export class SlaNudgeService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  getSla(candidateId: string): Observable<CandidateSla> {
    return this.http.get<CandidateSla>(
      `${this.base}/internal/candidates/${encodeURIComponent(candidateId)}/sla`);
  }

  previewDraft(candidateId: string): Observable<DraftPreview> {
    return this.http.get<DraftPreview>(
      `${this.base}/internal/candidates/${encodeURIComponent(candidateId)}/sla/draft/preview`);
  }

  approve(draftId: string): Observable<ActionResult> {
    return this.http.post<ActionResult>(
      `${this.base}/internal/sla/drafts/${encodeURIComponent(draftId)}/approve`, {});
  }

  dismiss(draftId: string): Observable<ActionResult> {
    return this.http.post<ActionResult>(
      `${this.base}/internal/sla/drafts/${encodeURIComponent(draftId)}/dismiss`, {});
  }
}
