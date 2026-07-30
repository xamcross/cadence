import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface EntitlementView {
  plan: 'FREE' | 'TEAM';
  status: 'ACTIVE' | 'CANCELLED' | 'EXPIRED' | null;
  expiresAt: string | null;
  boundAt: string | null;
}

/** 032 -- billing API (spec US1/US4). Workspace scoping is server-side from the session cookie. */
@Injectable({ providedIn: 'root' })
export class BillingService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/internal/billing`;

  getEntitlement(): Observable<EntitlementView> {
    return this.http.get<EntitlementView>(`${this.base}/entitlement`);
  }

  createCheckoutSession(): Observable<{ checkoutUrl: string }> {
    return this.http.post<{ checkoutUrl: string }>(`${this.base}/checkout-session`, {});
  }

  claim(licenseId: string): Observable<EntitlementView> {
    return this.http.post<EntitlementView>(`${this.base}/claim`, { licenseId });
  }
}
