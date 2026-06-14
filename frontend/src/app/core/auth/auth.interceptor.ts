import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';

/** Public auth routes where a 401 must NOT trigger a redirect (avoids loops — FE-6). */
const PUBLIC_AUTH_ROUTES = ['/login', '/accept-invite', '/reset', '/reset/confirm'];

/** Adds credentials so the HttpOnly cad_session cookie rides along on every API call (FE-5). */
export const apiInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.url.startsWith(environment.apiBaseUrl)) {
    return next(req.clone({ withCredentials: true }));
  }
  return next(req);
};

/** On 401 (and only 401, not 410 link-invalid — FE-3), redirect to /login unless already public. */
export const authErrorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  return next(req).pipe(
    catchError((err) => {
      if (err?.status === 401) {
        const onPublic = PUBLIC_AUTH_ROUTES.some((p) => router.url.startsWith(p));
        if (!onPublic) {
          router.navigate(['/login']);
        }
      }
      return throwError(() => err);
    })
  );
};
