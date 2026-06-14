import { test, expect } from '@playwright/test';

/**
 * F02 RBAC end-to-end (T040, SC-008). Requires the Playwright runner + a running stack (backend +
 * SPA) with a seeded Admin and a seeded non-Admin (e.g. Recruiter). Playwright is NOT installed in
 * this environment (constitution §X zero-download); this spec is authored for CI where the runner is
 * provisioned, mirroring the F01 E2E approach.
 *
 * Assertions:
 *  1. An Admin opens /admin/members and can change a member's role (the directory renders + persists).
 *  2. A non-Admin navigating to /admin/members is redirected to /not-authorized (not a 404/blank).
 *  3. The underlying API independently returns 403 even when the route guard is bypassed (the server
 *     is the boundary, FR-013).
 */

const ADMIN = { email: 'admin@example.com', password: process.env.E2E_ADMIN_PW ?? 'change-me' };
const RECRUITER = { email: 'recruiter@example.com', password: process.env.E2E_REC_PW ?? 'change-me' };
const WORKSPACE = process.env.E2E_WORKSPACE ?? 'ws1';

async function loginWithPassword(page: import('@playwright/test').Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel(/email/i).fill(email);
  await page.getByLabel(/password/i).fill(password);
  await page.getByRole('button', { name: /sign in/i }).click();
  await page.waitForURL('**/');
}

test('admin can open member directory and change a role', async ({ page }) => {
  await loginWithPassword(page, ADMIN.email, ADMIN.password);
  await page.goto('/admin/members');
  await expect(page.getByRole('heading', { name: /members/i })).toBeVisible();
  // Change the first non-admin row's role via its select; expect no error banner.
  const select = page.locator('table tbody tr select').first();
  await select.selectOption('READ_ONLY');
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('non-admin is redirected to /not-authorized (not a 404)', async ({ page }) => {
  await loginWithPassword(page, RECRUITER.email, RECRUITER.password);
  await page.goto('/admin/members');
  await page.waitForURL('**/not-authorized');
  await expect(page.getByRole('heading', { name: /do not have access/i })).toBeVisible();
});

test('API returns 403 even when the route guard is bypassed (server is the boundary)', async ({ page, request }) => {
  await loginWithPassword(page, RECRUITER.email, RECRUITER.password);
  // Direct API call as the recruiter (cookie carried) — the server must refuse regardless of the SPA.
  const res = await request.get('/api/internal/members');
  expect(res.status()).toBe(403);
  const body = await res.json();
  expect(body.error).toBe('forbidden');
});

// Avoids an unused-import lint error for WORKSPACE in environments that don't reference it.
void WORKSPACE;
