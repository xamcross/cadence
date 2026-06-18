import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export type DashboardWindow = 'LAST_7_DAYS' | 'LAST_30_DAYS' | 'LAST_90_DAYS';

export interface TimeToScheduleMetric {
  hasData: boolean;
  medianHours: number | null;
  sampleCount: number;
}

export interface NoShowMetric {
  applicable: boolean;
  rate: number | null;
  noShowCount: number;
  qualifyingCount: number;
}

export interface SilenceRow {
  candidateId: string;
  candidateName: string;
  severity: 'RED' | 'AMBER';
  daysSilent: number;
}

export interface DashboardSnapshot {
  window: DashboardWindow;
  generatedAt: string;
  timeToSchedule: TimeToScheduleMetric;
  noShow: NoShowMetric;
  silenceList: SilenceRow[];
}

/**
 * F50 Core Dashboard API client (internal Admin/Recruiter/Read-only screen). The selected window is held in this
 * singleton (FR-014) so it survives navigation within a session. Export is a same-origin GET (session cookie),
 * triggered as a file download; the backend is the authoritative role gate (Read-only is denied export server-side).
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/internal/dashboard`;

  /** Persists across navigation within the session (FR-014). */
  readonly selectedWindow = signal<DashboardWindow>('LAST_30_DAYS');

  snapshot(window: DashboardWindow): Observable<DashboardSnapshot> {
    return this.http.get<DashboardSnapshot>(this.base, { params: { window } });
  }

  exportUrl(window: DashboardWindow): string {
    return `${this.base}/export?window=${encodeURIComponent(window)}`;
  }

  /** Trigger a CSV download for the window (same-origin GET; the browser saves the attachment). */
  download(window: DashboardWindow): void {
    const a = document.createElement('a');
    a.href = this.exportUrl(window);
    a.rel = 'noopener';
    a.click();
  }
}
