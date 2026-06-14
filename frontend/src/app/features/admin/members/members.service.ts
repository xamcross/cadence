import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Role } from '../../../core/auth/auth.models';

export interface MemberRow {
  memberId: string;
  displayName: string;
  role: Role;
  status: 'ACTIVE' | 'DEACTIVATED';
}

/** Admin member-directory API client (F02 US1). */
@Injectable({ providedIn: 'root' })
export class MembersService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  getMembers(): Observable<MemberRow[]> {
    return this.http.get<MemberRow[]>(`${this.base}/internal/members`);
  }

  changeRole(memberId: string, role: Role): Observable<{ memberId: string; role: Role }> {
    return this.http.patch<{ memberId: string; role: Role }>(
      `${this.base}/internal/members/${encodeURIComponent(memberId)}/role`,
      { role }
    );
  }
}
