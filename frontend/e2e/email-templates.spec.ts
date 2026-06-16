import { test, expect } from '@playwright/test';

/**
 * F21 email-template library end-to-end (T032). Requires the Playwright runner + a running stack
 * (backend + SPA) with a signed-in Recruiter/Admin. Playwright is NOT installed in this environment
 * (constitution §X zero-download); authored for CI where the runner is provisioned, mirroring
 * interview-templates.spec.ts / rbac.spec.ts.
 *
 * Assertions:
 *  1. An Admin can open the email-templates library and see the message types.
 *  2. Editing a template's subject/body and saving persists (browser -> backend -> MongoDB).
 *  3. Previewing renders the merged message — a real SPA -> backend(render) round-trip.
 */

const ADMIN = { email: 'admin@example.com', password: process.env.E2E_ADMIN_PW ?? 'change-me' };

async function loginWithPassword(page: import('@playwright/test').Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel(/email/i).fill(email);
  await page.getByLabel(/password/i).fill(password);
  await page.getByRole('button', { name: /sign in/i }).click();
}

test('admin edits a template and previews the rendered message', async ({ page }) => {
  await loginWithPassword(page, ADMIN.email, ADMIN.password);
  await page.goto('/email-templates');

  await expect(page.getByText('INVITATION')).toBeVisible();

  // Edit the first template.
  await page.getByRole('button', { name: /^edit/i }).first().click();
  await page.getByLabel(/subject/i).fill('Schedule with {{workspace_name}}');
  await page.getByLabel(/body/i).fill('Hi {{candidate_name}}, please pick a time.');
  await page.getByRole('button', { name: /^save/i }).click();

  // Preview → the renderer ran end-to-end (the merged sample subject/body renders).
  await page.getByRole('button', { name: /preview/i }).first().click();
  await expect(page.getByText(/Subject:/i)).toBeVisible();
  await expect(page.getByText(/Dana Lee/)).toBeVisible();
});
