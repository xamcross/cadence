import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface ImportRowResult {
  rowNumber: number;
  status: string;
  failingField: string | null;
  reason: string | null;
  existingCandidateId: string | null;
  candidateId: string | null;
}

export interface ImportJobStatus {
  jobId: string;
  status: string;
  originalFilename: string | null;
  totalRows: number;
  importedCount: number;
  rejectedCount: number;
  duplicatePendingCount: number;
  mergedCount: number;
  skippedCount: number;
  rejectionReason: string | null;
  rowResults: ImportRowResult[];
  createdAt: string | null;
  completedAt: string | null;
}

export interface UploadAccepted {
  jobId: string;
  status: string;
}

export interface ResolveDecision {
  rowNumber: number;
  action: 'MERGE' | 'SKIP';
}

/**
 * F42 CSV import API client (internal Admin/Recruiter screen). Upload returns a jobId immediately (202);
 * the component then polls status. The file is sent as multipart FormData — do NOT set Content-Type so the
 * browser adds the multipart boundary.
 */
@Injectable({ providedIn: 'root' })
export class CsvImportService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/internal/import`;

  upload(file: File): Observable<UploadAccepted> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<UploadAccepted>(`${this.base}/csv`, form);
  }

  status(jobId: string): Observable<ImportJobStatus> {
    return this.http.get<ImportJobStatus>(`${this.base}/${jobId}/status`);
  }

  resolve(jobId: string, decisions: ResolveDecision[], defaultAction?: 'MERGE' | 'SKIP'): Observable<ImportJobStatus> {
    return this.http.post<ImportJobStatus>(`${this.base}/${jobId}/resolve`, {
      decisions,
      defaultAction: defaultAction ?? null
    });
  }
}
