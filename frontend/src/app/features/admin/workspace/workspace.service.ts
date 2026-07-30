import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface WorkingHours {
  start: string;
  end: string;
}

export interface WorkspaceConfig {
  configured: boolean;
  name: string | null;
  timeZone: string | null;
  workingHours: WorkingHours | null;
  slaSilenceWindowDays: number | null;
  retentionPeriodDays: number | null;
  retentionAcknowledgedAt: string | null;
  brandColor: string | null;
  hasLogo: boolean;
  emailSendingDomain: string | null;
  credentialSet: boolean;
  templateLocks: Record<string, boolean>;
  // F23 No-show defense cascade settings (ISO-8601 Duration, e.g. "PT24H"). null = workspace uses the
  // global default (NoShowProperties). 032 T9: gated to the Team plan on the FREE workspace, but the
  // settings themselves are always retained/editable (only cascade INITIATION is gated server-side).
  confirmationLeadTime: string | null;
  unconfirmedEscalationDeadline: string | null;
}

export interface SetupRequest {
  name: string;
  timeZone: string;
  workingHours: WorkingHours;
  slaSilenceWindowDays: number;
  retentionPeriodDays: number;
  retentionAcknowledged: boolean;
}

/** Partial settings update — any omitted/null field is left unchanged (targeted $set), mirroring the
 *  backend's WorkspaceDtos.SettingsPatch. Distinct from SetupRequest (the first-run wizard body). */
export interface WorkspaceSettingsPatch extends Partial<SetupRequest> {
  confirmationLeadTime?: string | null;
  unconfirmedEscalationDeadline?: string | null;
}

/** Admin workspace-configuration API client (F03). The provider credential is write-only — it is
 *  sent on putEmail but never present in any response (credentialSet only). */
@Injectable({ providedIn: 'root' })
export class WorkspaceService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/internal/workspace`;

  getConfig(): Observable<WorkspaceConfig> {
    return this.http.get<WorkspaceConfig>(`${this.base}/config`);
  }

  completeSetup(req: SetupRequest): Observable<WorkspaceConfig> {
    return this.http.post<WorkspaceConfig>(`${this.base}/setup`, req);
  }

  patchConfig(patch: WorkspaceSettingsPatch): Observable<WorkspaceConfig> {
    return this.http.patch<WorkspaceConfig>(`${this.base}/config`, patch);
  }

  putBranding(brandColor: string): Observable<WorkspaceConfig> {
    return this.http.put<WorkspaceConfig>(`${this.base}/branding`, { brandColor });
  }

  uploadLogo(file: File): Observable<{ hasLogo: boolean }> {
    // Build FormData and DO NOT set Content-Type — the browser sets the multipart boundary.
    // The CSRF token rides the X-XSRF-TOKEN header via the interceptor (not a form part).
    const form = new FormData();
    form.append('file', file);
    return this.http.post<{ hasLogo: boolean }>(`${this.base}/logo`, form);
  }

  deleteLogo(): Observable<void> {
    return this.http.delete<void>(`${this.base}/logo`);
  }

  putEmail(sendingDomain: string, credential: string): Observable<WorkspaceConfig> {
    return this.http.put<WorkspaceConfig>(`${this.base}/email`, { sendingDomain, credential });
  }

  deleteCredential(): Observable<void> {
    return this.http.delete<void>(`${this.base}/email/credential`);
  }

  putTemplateLock(key: string, locked: boolean): Observable<WorkspaceConfig> {
    return this.http.put<WorkspaceConfig>(`${this.base}/templates/${encodeURIComponent(key)}/lock`, { locked });
  }
}
