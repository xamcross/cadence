import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { ShellComponent } from './shell.component';
import { AuthService } from '../../core/auth/auth.service';
import { MemberSummary, Role } from '../../core/auth/auth.models';

/**
 * F03 US6: a non-Admin on an UNCONFIGURED workspace sees a neutral "setup pending" panel (not the
 * wizard, not an error); an Admin on an unconfigured workspace is redirected to the setup wizard.
 */
describe('ShellComponent', () => {
  function member(role: Role, workspaceConfigured: boolean): MemberSummary {
    return { memberId: 'm1', workspaceId: 'ws1', role, displayName: 'X', email: 'x@x.com', workspaceConfigured };
  }

  function setup(m: MemberSummary) {
    TestBed.resetTestingModule();
    const authStub: Partial<AuthService> = {
      member$: new BehaviorSubject<MemberSummary | null>(m).asObservable(),
      logout: () => of(void 0)
    };
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: authStub }]
    });
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate');
    const fixture = TestBed.createComponent(ShellComponent);
    fixture.detectChanges();
    return { fixture, navigate };
  }

  it('shows "setup pending" for a non-Admin on an unconfigured workspace', () => {
    const { fixture, navigate } = setup(member('RECRUITER', false));
    expect(fixture.nativeElement.textContent).toContain('Workspace setup pending');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('redirects an Admin on an unconfigured workspace to the setup wizard', () => {
    const { navigate } = setup(member('ADMIN', false));
    expect(navigate).toHaveBeenCalledWith(['/workspace/setup']);
  });

  it('shows the welcome view for a configured workspace and does not redirect', () => {
    const { fixture, navigate } = setup(member('ADMIN', true));
    expect(fixture.nativeElement.textContent).toContain('Welcome to Cadence');
    expect(navigate).not.toHaveBeenCalled();
  });
});
