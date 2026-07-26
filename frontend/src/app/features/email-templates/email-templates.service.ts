import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface EmailTemplate {
  messageType: string;
  stageKey: string;
  subject: string;
  body: string;
  locked: boolean;
  version: number | null;
  source: string; // BUILTIN | OVERRIDE
  permittedTokens: string[];
  updatedByMemberId?: string;
  updatedAt?: string;
}

export interface TemplateList {
  templates: EmailTemplate[];
}

export interface RenderedMessage {
  subject: string;
  bodyText: string;
  bodyHtml: string;
  missingFields: string[];
}

/** The value-free dispatch outcome (F22 contract A) — ids + status only, never recipient/subject/body. */
export interface SendResult {
  dispatchId: string;
  status: string; // SENT | PENDING | FAILED
  messageType: string;
}

/**
 * Email-template library API client (F21). Admin/Recruiter only (server-enforced; the route guard is
 * defense-in-depth). Requests carry credentials + XSRF via the functional interceptor.
 */
@Injectable({ providedIn: 'root' })
export class EmailTemplatesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/internal/email-templates`;

  list(stageKey = 'BASE'): Observable<TemplateList> {
    return this.http.get<TemplateList>(this.base, { params: new HttpParams().set('stageKey', stageKey) });
  }

  get(messageType: string, stageKey = 'BASE'): Observable<EmailTemplate> {
    return this.http.get<EmailTemplate>(`${this.base}/${messageType}`,
      { params: new HttpParams().set('stageKey', stageKey) });
  }

  edit(messageType: string, body: { stageKey: string; subject: string; body: string; expectedVersion: number | null }):
    Observable<EmailTemplate> {
    return this.http.put<EmailTemplate>(`${this.base}/${messageType}`, body);
  }

  applyTone(messageType: string, body: { stageKey: string; tone: string; expectedVersion: number | null }):
    Observable<EmailTemplate> {
    return this.http.post<EmailTemplate>(`${this.base}/${messageType}/apply-tone`, body);
  }

  applyPresetStarter(messageType: string,
    body: { stageKey: string; presetKey: string; expectedVersion: number | null }): Observable<EmailTemplate> {
    return this.http.post<EmailTemplate>(`${this.base}/${messageType}/apply-preset-starter`, body);
  }

  reset(messageType: string, body: { stageKey: string; expectedVersion: number | null }): Observable<EmailTemplate> {
    return this.http.post<EmailTemplate>(`${this.base}/${messageType}/reset`, body);
  }

  lock(messageType: string, body: { stageKey: string; expectedVersion: number | null }): Observable<EmailTemplate> {
    return this.http.post<EmailTemplate>(`${this.base}/${messageType}/lock`, body);
  }

  unlock(messageType: string, body: { stageKey: string; expectedVersion: number | null }): Observable<EmailTemplate> {
    return this.http.post<EmailTemplate>(`${this.base}/${messageType}/unlock`, body);
  }

  preview(messageType: string, body: { stageKey: string; candidateId?: string; sampleValues?: Record<string, string> }):
    Observable<RenderedMessage> {
    return this.http.post<RenderedMessage>(`${this.base}/${messageType}/preview`, body);
  }

  /**
   * Send a templated message to a candidate now (F22 contract A). The candidate name is resolved
   * server-side; only the message type/stage + non-PII sample scalars are sent. The consent gate may
   * refuse (409 not_contactable, value-free reason); a foreign/missing candidate is 404.
   */
  sendToCandidate(candidateId: string,
                  body: { messageType: string; stageKey: string; sampleValues?: Record<string, string> }):
    Observable<SendResult> {
    return this.http.post<SendResult>(`${environment.apiBaseUrl}/internal/candidates/${candidateId}/emails`, body);
  }
}
