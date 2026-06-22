import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';
import { authErrorInterceptor } from './auth.interceptor';

/**
 * F60 (026-seo-aeo) N2: the public home (`/`) is exempt from the 401 -> /login redirect, so an
 * anonymous me() probe on the home page never bounces a visitor/crawler off `/` (FR-022/SC-005).
 * A guarded route's 401 still redirects. The exemption is an EXACT match on `/` — not a prefix.
 */
describe('authErrorInterceptor — public home exemption', () => {
  let router: { url: string; navigate: jasmine.Spy };
  let http: HttpClient;
  let ctrl: HttpTestingController;

  function setup(currentUrl: string) {
    router = { url: currentUrl, navigate: jasmine.createSpy('navigate') };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authErrorInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
        { provide: AuthService, useValue: { invalidateMember: jasmine.createSpy('invalidateMember') } }
      ]
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
  }

  function fire401(url = '/api/internal/auth/me') {
    http.get(url).subscribe({ next: () => {}, error: () => {} });
    ctrl.expectOne(url).flush('', { status: 401, statusText: 'Unauthorized' });
  }

  it('does NOT redirect on a 401 while on the home route (/)', () => {
    setup('/');
    fire401();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('does NOT redirect on the home route even with a query string (/?x=1)', () => {
    setup('/?x=1');
    fire401();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('DOES redirect to /login on a 401 from a non-home, non-public route', () => {
    setup('/scheduling');
    fire401();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });
});
