import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface ScorecardView {
  recommendation: string;
  ratings: { dimension: string; score: number }[];
  comment: string | null;
}

export interface InterviewFeedbackItem {
  interviewerMemberId: string;
  status: 'PENDING' | 'SUBMITTED' | 'INVALIDATED' | 'UNCOLLECTIBLE' | 'EXPIRED';
  scorecard: ScorecardView | null;
  submittedAt: string | null;
}

export interface InterviewFeedbackView {
  interviewEventId: string;
  items: InterviewFeedbackItem[];
}

export interface PendingItem {
  interviewEventId: string;
  interviewerMemberId: string;
  candidateId: string;
  reminderLevelSent: number;
}

export interface PendingListResponse {
  items: PendingItem[];
}

/**
 * F32 recruiter feedback read (contract C/D) — INTERNAL, ADMIN/RECRUITER. Per-interview submission status +
 * decrypted scorecards, and the workspace feedback-pending list. Session-cookie authenticated (the shell's
 * HttpClient + interceptor); responses are `no-store` server-side.
 */
@Injectable({ providedIn: 'root' })
export class InterviewFeedbackService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  getInterviewFeedback(schedulingRequestId: string): Observable<InterviewFeedbackView> {
    return this.http.get<InterviewFeedbackView>(
      `${this.base}/internal/interviews/${encodeURIComponent(schedulingRequestId)}/feedback`);
  }

  pending(): Observable<PendingListResponse> {
    return this.http.get<PendingListResponse>(`${this.base}/internal/feedback/pending`);
  }
}
