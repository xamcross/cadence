import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from './auth.service';
import { Role } from './auth.models';

/**
 * Role-aware route guard (F02 US5). Sources the role from auth.me() (Observable, self-caching) — NOT
 * a synchronous nullable snapshot — so a cold direct-navigation does not false-redirect a legitimate
 * user (FE-1). A permitted role passes; a disallowed role goes to /not-authorized; no session goes to
 * /login. Defense-in-depth only: the server (method security) is the real boundary.
 *
 * Use after authGuard, e.g. canActivate: [authGuard, roleGuard('ADMIN')].
 */
export function roleGuard(...roles: Role[]): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    return auth.me().pipe(
      map((m) => (roles.includes(m.role) ? true : router.createUrlTree(['/not-authorized']))),
      catchError(() => of(router.createUrlTree(['/login'])))
    );
  };
}
