import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { InvitationView, MemberSummary } from './auth.models';

/**
 * Auth API client + session state. The session lives in the HttpOnly cad_session cookie (not
 * readable by JS), so identity is learned via me(). All requests carry credentials + XSRF via the
 * functional interceptor (research D8).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  private readonly currentMember$ = new BehaviorSubject<MemberSummary | null>(null);
  readonly member$ = this.currentMember$.asObservable();

  /** SSO is the primary path — full-page navigation to the backend authorization endpoint. */
  startSso(): void {
    window.location.href = '/oauth2/authorization/cadence-oidc';
  }

  me(): Observable<MemberSummary> {
    return this.http
      .get<MemberSummary>(`${this.base}/internal/auth/me`)
      .pipe(tap((m) => this.currentMember$.next(m)));
  }

  loginWithPassword(workspaceId: string, email: string, password: string): Observable<MemberSummary> {
    return this.http
      .post<MemberSummary>(`${this.base}/public/auth/login`, { workspaceId, email, password })
      .pipe(tap((m) => this.currentMember$.next(m)));
  }

  logout(): Observable<void> {
    return this.http
      .post<void>(`${this.base}/internal/auth/logout`, {})
      .pipe(tap(() => this.currentMember$.next(null)));
  }

  requestReset(workspaceId: string, email: string): Observable<void> {
    return this.http.post<void>(`${this.base}/public/auth/password-reset/request`, { workspaceId, email });
  }

  confirmReset(token: string, newPassword: string): Observable<void> {
    return this.http.post<void>(`${this.base}/public/auth/password-reset/confirm`, { token, newPassword });
  }

  validateInvite(token: string): Observable<InvitationView> {
    return this.http.get<InvitationView>(`${this.base}/public/auth/invitations/${encodeURIComponent(token)}`);
  }

  acceptInvite(token: string, password: string): Observable<MemberSummary> {
    return this.http
      .post<MemberSummary>(`${this.base}/public/auth/invitations/${encodeURIComponent(token)}/accept`, { password })
      .pipe(tap((m) => this.currentMember$.next(m)));
  }
}
