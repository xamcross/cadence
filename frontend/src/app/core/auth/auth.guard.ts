import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Protects the authenticated app shell. Probes /me; on failure routes to /login. Public auth pages
 * are top-level siblings of the shell and are NOT wrapped by this guard (FE-4/FE-6).
 */
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => true),
    catchError(() => of(router.createUrlTree(['/login'])))
  );
};
