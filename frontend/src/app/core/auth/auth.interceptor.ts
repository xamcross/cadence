import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';

/** Public auth routes where a 401 must NOT trigger a redirect (avoids loops — FE-6). */
const PUBLIC_AUTH_ROUTES = ['/login', '/accept-invite', '/reset', '/reset/confirm'];

/**
 * True when the current route is the public marketing home (`/`). F60 (026-seo-aeo): the home fires a
 * background me() to redirect a signed-in member to /app; an anonymous 401 there must NOT redirect to
 * /login, or every anonymous visitor/crawler is bounced off `/` and root indexing breaks. Exact match
 * (`startsWith('/')` would match every route).
 */
function isPublicHome(url: string): boolean {
  return url.split('?')[0].split('#')[0] === '/';
}

/** Adds credentials so the HttpOnly cad_session cookie rides along on every API call (FE-5). */
export const apiInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.url.startsWith(environment.apiBaseUrl)) {
    return next(req.clone({ withCredentials: true }));
  }
  return next(req);
};

/**
 * On 401 (and only 401, not 410 link-invalid — FE-3), redirect to /login. On 403 (F02 — the member
 * is authenticated but unauthorized, e.g. their role changed mid-session), redirect to
 * /not-authorized and invalidate the cached member so the next me() refetches the now-current role
 * (FE-2). Both skip the redirect when already on a public auth route to avoid loops.
 */
export const authErrorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const auth = inject(AuthService);
  return next(req).pipe(
    catchError((err) => {
      const onPublic = isPublicHome(router.url) || PUBLIC_AUTH_ROUTES.some((p) => router.url.startsWith(p));
      if (err?.status === 401 && !onPublic) {
        router.navigate(['/login']);
      } else if (err?.status === 403) {
        auth.invalidateMember();
        if (!onPublic) {
          router.navigate(['/not-authorized']);
        }
      }
      return throwError(() => err);
    })
  );
};
