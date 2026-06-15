import { test, expect } from '@playwright/test';

/**
 * F12 interview-template + rule-engine slot preview end-to-end (T037). Requires the Playwright runner +
 * a running stack (backend + SPA) with a configured workspace and a signed-in Recruiter/Admin. Playwright
 * is NOT installed in this environment (constitution §X zero-download); authored for CI where the runner
 * is provisioned, mirroring rbac.spec.ts / workspace-config.spec.ts / calendar-connections.spec.ts.
 *
 * Assertions:
 *  1. A Recruiter can create an interview template and see it listed.
 *  2. Clicking "Preview slots" renders the rule engine's output — either computed slots or the
 *     "no compliant slots" / unschedulable panel — a real SPA -> backend(rule engine + availability) round-trip.
 */

const RECRUITER = { email: 'recruiter@example.com', password: process.env.E2E_REC_PW ?? 'change-me' };

async function loginWithPassword(page: import('@playwright/test').Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel(/email/i).fill(email);
  await page.getByLabel(/password/i).fill(password);
  await page.getByRole('button', { name: /sign in/i }).click();
}

test('recruiter creates a template and previews computed slots', async ({ page }) => {
  await loginWithPassword(page, RECRUITER.email, RECRUITER.password);
  await page.goto('/interview-templates');

  // Create a template.
  await page.getByLabel(/^name/i).fill('E2E Phone Screen');
  await page.getByLabel(/duration/i).fill('45');
  await page.getByLabel(/daily cap/i).fill('2');
  await page.getByRole('button', { name: /create template/i }).click();

  // It appears in the list.
  await expect(page.getByText('E2E Phone Screen')).toBeVisible();

  // Preview slots → the rule engine ran end-to-end (slots or the no-slots/unschedulable panel render).
  await page.getByRole('button', { name: /preview slots/i }).first().click();
  await expect(
    page.getByText(/computed slots|no compliant slots|could not be scheduled/i)
  ).toBeVisible();
});
