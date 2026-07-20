import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MembersService, MemberRow } from './members.service';
import { Role } from '../../../core/auth/auth.models';
import { PageHeaderComponent } from '../../../shared/ui/page-header.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { SkeletonComponent } from '../../../shared/ui/skeleton.component';
import { TableScrollComponent } from '../../../shared/ui/table-scroll.component';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';

/**
 * Admin member directory + role change (F02 US1). ADMIN-guarded route. Surfaces server messages
 * (e.g. last-admin 409, forbidden 403). Strings externalized via i18n.
 *
 * Phase 3b (workbench overhaul): `onRoleChange` is gated behind the shared `ConfirmDialogService`
 * (⚠ danger) using the select-revert pattern — declining (or a failed server call) reverts the
 * bound `member.role` so the native `<select>` snaps back to its previous value. The outcome is
 * surfaced via `ToastService` (the "last admin" 409 message is preserved verbatim).
 */
@Component({
  selector: 'app-members',
  standalone: true,
  imports: [FormsModule, PageHeaderComponent, EmptyStateComponent, SkeletonComponent, TableScrollComponent],
  template: `
    <main class="members">
      <app-page-header
        eyebrow="Administration" i18n-eyebrow="@@members.eyebrow"
        heading="Workspace members" i18n-heading="@@members.title"
        subtitle="Invite teammates and manage roles." i18n-subtitle="@@members.subtitle">
      </app-page-header>
      @if (error) {
        <p class="error alert alert--danger" role="alert">{{ error }}</p>
      }
      @if (loading()) {
        <app-skeleton variant="table" />
      } @else if (members.length === 0) {
        <app-empty-state
          heading="No members yet" i18n-heading="@@members.empty.heading"
          body="Invite teammates to get started." i18n-body="@@members.empty.body">
        </app-empty-state>
      } @else {
        <app-table-scroll ariaLabel="Workspace members" i18n-ariaLabel="@@members.tableLabel">
          <table class="table table--stack">
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
                  <td data-label="Name">{{ m.displayName }}</td>
                  <td data-label="Role">
                    <select class="input" [(ngModel)]="m.role" (ngModelChange)="onRoleChange(m)" [attr.aria-label]="m.displayName">
                      @for (r of roles; track r) {
                        <option [value]="r">{{ r }}</option>
                      }
                    </select>
                  </td>
                  <td data-label="Status">{{ m.status }}</td>
                </tr>
              }
            </tbody>
          </table>
        </app-table-scroll>
      }
    </main>
  `,
  styles: [`
    .members { padding: var(--space-4); }
    .error { margin-bottom: var(--space-4); }
  `]
})
export class MembersComponent implements OnInit {
  private readonly api = inject(MembersService);
  private readonly confirm = inject(ConfirmDialogService);
  private readonly toast = inject(ToastService);

  readonly roles: Role[] = ['ADMIN', 'RECRUITER', 'HIRING_MANAGER', 'INTERVIEWER', 'READ_ONLY'];
  readonly loading = signal(true);
  members: MemberRow[] = [];
  error = '';

  /**
   * Last CONFIRMED role per member — the value the native `<select>` reverts to on a decline or a
   * failed server call. The `<select>` is two-way bound (`[(ngModel)]="m.role"`), so a selection
   * mutates `member.role` optimistically *before* `onRoleChange` runs; the confirmed baseline must
   * therefore be tracked separately (reverting `member.role` to it is a real model change, which the
   * one-way half of `[(ngModel)]` propagates back to the DOM).
   */
  private readonly confirmedRole = new Map<string, Role>();

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.api.getMembers().subscribe({
      next: (rows) => {
        this.members = rows;
        this.confirmedRole.clear();
        rows.forEach((m) => this.confirmedRole.set(m.memberId, m.role));
        this.loading.set(false);
      },
      error: () => { this.error = $localize`:@@members.loadError:Could not load members.`; this.loading.set(false); }
    });
  }

  async onRoleChange(member: MemberRow): Promise<void> {
    const previous = this.confirmedRole.get(member.memberId) ?? member.role;
    const role = member.role; // two-way binding already wrote the new selection into member.role
    if (role === previous) { return; }
    const ok = await this.confirm.confirm({
      title: $localize`:@@confirm.members.roleChange.title:Change role?`,
      body: $localize`:@@confirm.members.roleChange.body:Change this member's role? They will immediately gain or lose access.`,
      confirmLabel: $localize`:@@confirm.members.roleChange.cta:Change role`,
      danger: true
    });
    if (!ok) { member.role = previous; return; } // revert → two-way writes back, the <select> snaps back
    this.api.changeRole(member.memberId, role).subscribe({
      next: () => {
        this.confirmedRole.set(member.memberId, role);
        this.toast.success($localize`:@@toast.members.roleChanged:Role changed.`);
      },
      error: (err) => {
        member.role = previous; // revert → two-way writes back, the <select> snaps back
        this.toast.error(
          err?.status === 409
            ? $localize`:@@toast.members.lastAdmin:Cannot change the last administrator's role.`
            : $localize`:@@toast.members.changeError:Could not change the role.`
        );
      }
    });
  }
}
