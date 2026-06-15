import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { of, throwError, firstValueFrom, isObservable, Observable } from 'rxjs';
import { roleGuard } from './role.guard';
import { AuthService } from './auth.service';
import { Role, MemberSummary } from './auth.models';

/**
 * SC-011 (T039): the role guard lets a permitted role through and redirects EACH disallowed role to
 * /not-authorized; a missing session goes to /login. Defense-in-depth only — the server is the
 * boundary. The guard sources the role from auth.me() (Observable), never a synchronous snapshot.
 */
describe('roleGuard', () => {
  const ALL_ROLES: Role[] = ['ADMIN', 'RECRUITER', 'HIRING_MANAGER', 'INTERVIEWER', 'READ_ONLY'];

  function setup(meResult: Observable<MemberSummary>) {
    TestBed.resetTestingModule();
    const authStub: Partial<AuthService> = { me: () => meResult };
    const navigated: string[] = [];
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: authStub },
        {
          provide: Router,
          useValue: {
            createUrlTree: (commands: string[]) => {
              navigated.push(commands.join('/'));
              return { __url: commands.join('/') } as unknown as UrlTree;
            }
          }
        }
      ]
    });
    return { navigated };
  }

  function run(guardRoles: Role[]) {
    const guard = roleGuard(...guardRoles);
    return TestBed.runInInjectionContext(() => guard({} as never, {} as never));
  }

  async function resolve(result: unknown): Promise<unknown> {
    return isObservable(result) ? firstValueFrom(result as Observable<unknown>) : result;
  }

  function member(role: Role): MemberSummary {
    return { memberId: 'm1', workspaceId: 'ws1', role, displayName: 'X', email: 'x@x.com', workspaceConfigured: true };
  }

  it('lets the permitted role through (true)', async () => {
    setup(of(member('ADMIN')));
    const result = await resolve(run(['ADMIN']));
    expect(result).toBe(true);
  });

  it('redirects EACH disallowed role to /not-authorized', async () => {
    for (const role of ALL_ROLES.filter((r) => r !== 'ADMIN')) {
      const { navigated } = setup(of(member(role)));
      await resolve(run(['ADMIN']));
      expect(navigated).toContain('/not-authorized');
    }
  });

  it('redirects to /login when there is no valid session', async () => {
    const { navigated } = setup(throwError(() => ({ status: 401 })));
    await resolve(run(['ADMIN']));
    expect(navigated).toContain('/login');
  });

  // F04 SC-011: the erasure-action route is guarded roleGuard('ADMIN','RECRUITER') — cover BOTH the
  // Admin-only routes and the mixed route, per the F03 single-route lesson.
  describe("roleGuard('ADMIN','RECRUITER') — the F04 erasure-action route", () => {
    it('lets RECRUITER through (true)', async () => {
      setup(of(member('RECRUITER')));
      const result = await resolve(run(['ADMIN', 'RECRUITER']));
      expect(result).toBe(true);
    });

    it('lets ADMIN through (true)', async () => {
      setup(of(member('ADMIN')));
      const result = await resolve(run(['ADMIN', 'RECRUITER']));
      expect(result).toBe(true);
    });

    it('redirects HM / Interviewer / Read-only to /not-authorized', async () => {
      for (const role of ['HIRING_MANAGER', 'INTERVIEWER', 'READ_ONLY'] as Role[]) {
        const { navigated } = setup(of(member(role)));
        await resolve(run(['ADMIN', 'RECRUITER']));
        expect(navigated).toContain('/not-authorized');
      }
    });
  });

  // The Admin-only F04 routes (audit/requests/retention) reject RECRUITER too.
  it("roleGuard('ADMIN') redirects RECRUITER (Admin-only F04 surfaces)", async () => {
    const { navigated } = setup(of(member('RECRUITER')));
    await resolve(run(['ADMIN']));
    expect(navigated).toContain('/not-authorized');
  });
});
