import { test, expect } from '@playwright/test';

/**
 * F03 Workspace Setup & Configuration end-to-end (T047). Requires the Playwright runner + a running
 * stack (backend + SPA) with a seeded Admin and a seeded non-Admin. Playwright is NOT installed in
 * this environment (constitution §X zero-download); authored for CI where the runner is provisioned,
 * mirroring the F02 rbac.spec.ts approach.
 *
 * Assertions:
 *  1. An Admin on an unconfigured workspace lands on the setup wizard and can complete it.
 *  2. A non-Admin navigating to /admin/workspace is redirected to /not-authorized.
 *  3. The public branding endpoint serves an image without a session (candidate-facing surface).
 */

const ADMIN = { email: 'admin@example.com', password: process.env.E2E_ADMIN_PW ?? 'change-me' };
const RECRUITER = { email: 'recruiter@example.com', password: process.env.E2E_REC_PW ?? 'change-me' };

async function loginWithPassword(page: import('@playwright/test').Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel(/email/i).fill(email);
  await page.getByLabel(/password/i).fill(password);
  await page.getByRole('button', { name: /sign in/i }).click();
}

test('admin completes the first-run setup wizard', async ({ page }) => {
  await loginWithPassword(page, ADMIN.email, ADMIN.password);
  // An unconfigured workspace routes the Admin to the wizard.
  await page.goto('/workspace/setup');
  await expect(page.getByRole('heading', { name: /set up your workspace/i })).toBeVisible();
  await page.getByLabel(/workspace name/i).fill('Acme Talent');
  await page.getByLabel(/time zone/i).fill('Europe/London');
  await page.getByLabel(/data-retention/i).fill('365');
  await page.getByLabel(/acknowledge the data-retention/i).check();
  await page.getByRole('button', { name: /finish setup/i }).click();
  await page.waitForURL('**/');
});

test('non-admin is redirected from /admin/workspace to /not-authorized', async ({ page }) => {
  await loginWithPassword(page, RECRUITER.email, RECRUITER.password);
  await page.goto('/admin/workspace');
  await page.waitForURL('**/not-authorized');
  await expect(page.getByRole('heading', { name: /do not have access/i })).toBeVisible();
});

test('public branding endpoint serves an image without a session', async ({ request }) => {
  const res = await request.get('/api/public/workspace/logo');
  expect(res.status()).toBe(200);
  expect(res.headers()['content-type']).toMatch(/image\/(png|jpeg)/);
  expect(res.headers()['x-content-type-options']).toBe('nosniff');
});
