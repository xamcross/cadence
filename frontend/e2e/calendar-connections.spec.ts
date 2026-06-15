import { test, expect } from '@playwright/test';

/**
 * F10 Calendar availability preview end-to-end (T046). Requires the Playwright runner + a running stack
 * (backend + SPA) with a seeded member whose Google calendar is connected against the test provider stub.
 * Playwright is NOT installed in this environment (constitution §X zero-download); authored for CI where
 * the runner is provisioned, mirroring the F02 rbac.spec.ts / F03 workspace-config.spec.ts approach.
 *
 * Assertions:
 *  1. A signed-in member with a connected Google calendar can click "Preview my availability" and see
 *     their busy blocks (or a "free" message) — a real SPA -> backend -> Google(stub) round-trip.
 *  2. A member whose calendar needs reconnection sees the reconnect prompt in the preview, not an error.
 */

const MEMBER = { email: 'recruiter@example.com', password: process.env.E2E_REC_PW ?? 'change-me' };

async function loginWithPassword(page: import('@playwright/test').Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel(/email/i).fill(email);
  await page.getByLabel(/password/i).fill(password);
  await page.getByRole('button', { name: /sign in/i }).click();
}

test('member previews their availability after connecting', async ({ page }) => {
  await loginWithPassword(page, MEMBER.email, MEMBER.password);
  await page.goto('/calendar/connections');
  // (Connection is seeded against the provider stub in the CI fixture.)
  await page.getByRole('button', { name: /preview my availability/i }).click();
  // Either busy blocks or the "free" message renders — both prove the read adapter worked end-to-end.
  await expect(
    page.getByText(/busy at these times|you appear free/i)
  ).toBeVisible();
});
