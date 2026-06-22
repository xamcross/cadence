import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MembersService, MemberRow } from './members.service';
import { Role } from '../../../core/auth/auth.models';

/**
 * Admin member directory + role change (F02 US1). ADMIN-guarded route. Surfaces server messages
 * (e.g. last-admin 409, forbidden 403). Strings externalized via i18n.
 */
@Component({
  selector: 'app-members',
  standalone: true,
  imports: [FormsModule],
  template: `
    <main class="members">
      <h1 i18n="@@members.title">Workspace members</h1>
      @if (error) {
        <p class="error" role="alert">{{ error }}</p>
      }
      <table>
        <thead>
          <tr>
            <th i18n="@@members.name">Name</th>
            <th i18n="@@members.role">Role</th>
            <th i18n="@@members.status">Status</th>
          </tr>
        </thead>
        <tbody>
          @for (m of members; track m.memberId) {
            <tr>
              <td>{{ m.displayName }}</td>
              <td>
                <select [ngModel]="m.role" (ngModelChange)="onRoleChange(m, $event)" [attr.aria-label]="m.displayName">
                  @for (r of roles; track r) {
                    <option [value]="r">{{ r }}</option>
                  }
                </select>
              </td>
              <td>{{ m.status }}</td>
            </tr>
          }
        </tbody>
      </table>
    </main>
  `,
  styles: [`
    .members { padding: 1rem; }
    table { border-collapse: collapse; width: 100%; }
    th, td { text-align: left; padding: 0.5rem; border-bottom: 1px solid var(--line); }
    select { min-height: 44px; }
    .error { color: var(--danger); }
  `]
})
export class MembersComponent implements OnInit {
  private readonly api = inject(MembersService);

  readonly roles: Role[] = ['ADMIN', 'RECRUITER', 'HIRING_MANAGER', 'INTERVIEWER', 'READ_ONLY'];
  members: MemberRow[] = [];
  error = '';

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.api.getMembers().subscribe({
      next: (rows) => (this.members = rows),
      error: () => (this.error = $localize`:@@members.loadError:Could not load members.`)
    });
  }

  onRoleChange(member: MemberRow, role: Role): void {
    this.error = '';
    const previous = member.role;
    this.api.changeRole(member.memberId, role).subscribe({
      next: () => (member.role = role),
      error: (err) => {
        member.role = previous; // revert the optimistic select
        this.error =
          err?.status === 409
            ? $localize`:@@members.lastAdmin:Cannot change the last administrator's role.`
            : $localize`:@@members.changeError:Could not change the role.`;
      }
    });
  }
}
