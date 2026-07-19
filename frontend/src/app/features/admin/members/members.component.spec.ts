import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { MembersComponent } from './members.component';
import { MembersService, MemberRow } from './members.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

/**
 * Admin member directory + role change (F02 US1).
 *
 * Phase 3b (workbench overhaul): `onRoleChange` is gated behind `ConfirmDialogService.confirm()`
 * (⚠ danger) using the select-revert pattern — a decline (or a failed server call) reverts the
 * bound `member.role` so the native `<select>` snaps back to its previous value. Success/failure
 * are surfaced via `ToastService` (the "last admin" 409 message is preserved verbatim).
 */
describe('MembersComponent', () => {
  function member(overrides: Partial<MemberRow> = {}): MemberRow {
    return { memberId: 'm1', displayName: 'Ada Lovelace', role: 'RECRUITER', status: 'ACTIVE', ...overrides };
  }

  let attachedEls: HTMLElement[] = [];

  function setup(overrides: Partial<MembersService> = {}) {
    const stub: Partial<MembersService> = { getMembers: () => of([member()]), ...overrides };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [MembersComponent],
      providers: [{ provide: MembersService, useValue: stub }]
    });
    const fixture = TestBed.createComponent(MembersComponent);
    const el = fixture.nativeElement as HTMLElement;
    attachedEls.push(el);
    attachToBody(el);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    attachedEls.forEach(detachFromBody);
    attachedEls = [];
  });

  it('renders the shared page-header masthead', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const fixture = setup();
    const violations = await axeViolations(fixture.nativeElement);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });

  it('shows the guided empty-state when there are no members', () => {
    const fixture = setup({ getMembers: () => of([]) });
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
  });

  it('renders a responsive card-fallback table (table--stack + per-cell data-label)', () => {
    const fixture = setup();
    const table = fixture.nativeElement.querySelector('table.table');
    expect(table?.classList.contains('table--stack')).toBe(true);
    const td = fixture.nativeElement.querySelector('tbody td') as HTMLElement | null;
    expect(td?.getAttribute('data-label')).toBeTruthy();
  });

  describe('onRoleChange (confirm-gate ⚠ danger + toast, select-revert)', () => {
    it('does not change the role when the confirm is declined, and reverts the bound model', async () => {
      const changeSpy = jasmine.createSpy('changeRole').and.returnValue(of({ memberId: 'm1', role: 'ADMIN' as const }));
      const fixture = setup({ changeRole: changeSpy as unknown as MembersService['changeRole'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      const row = fixture.componentInstance.members[0];
      await fixture.componentInstance.onRoleChange(row, 'ADMIN');
      expect(changeSpy).not.toHaveBeenCalled();
      expect(row.role).toBe('RECRUITER'); // reverted to the previous value
    });

    it('gates with a danger confirm, changes the role, and toasts success when confirmed', async () => {
      const changeSpy = jasmine.createSpy('changeRole').and.returnValue(of({ memberId: 'm1', role: 'ADMIN' as const }));
      const fixture = setup({ changeRole: changeSpy as unknown as MembersService['changeRole'] });
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      const row = fixture.componentInstance.members[0];
      await fixture.componentInstance.onRoleChange(row, 'ADMIN');
      expect(confirmSpy).toHaveBeenCalledWith(jasmine.objectContaining({ danger: true }));
      expect(changeSpy).toHaveBeenCalledWith('m1', 'ADMIN');
      expect(row.role).toBe('ADMIN');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('surfaces the "last admin" 409 message and reverts the role on failure', async () => {
      const changeSpy = jasmine.createSpy('changeRole').and.returnValue(throwError(() => ({ status: 409 })));
      const fixture = setup({ changeRole: changeSpy as unknown as MembersService['changeRole'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      const row = fixture.componentInstance.members[0];
      await fixture.componentInstance.onRoleChange(row, 'ADMIN');
      expect(toastSpy).toHaveBeenCalledWith(jasmine.stringContaining('last administrator'));
      expect(row.role).toBe('RECRUITER'); // reverted after the failed call
    });

    it('toasts a generic error on a non-409 failure', async () => {
      const changeSpy = jasmine.createSpy('changeRole').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ changeRole: changeSpy as unknown as MembersService['changeRole'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      const row = fixture.componentInstance.members[0];
      await fixture.componentInstance.onRoleChange(row, 'ADMIN');
      expect(toastSpy).toHaveBeenCalled();
      expect(row.role).toBe('RECRUITER');
    });
  });
});
