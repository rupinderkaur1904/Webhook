import { chromium, expect, type FullConfig } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { ADMIN_EMAIL, ADMIN_PASSWORD, BASE_URL } from './support';

/**
 * Signs in once through the login page and saves the session for every test. One login per run
 * keeps the suite well under the login rate limit (5 per minute per address).
 */
export default async function globalSetup(config: FullConfig) {
  const executablePath = config.projects[0]?.use.launchOptions?.executablePath;
  const browser = await chromium.launch(executablePath ? { executablePath } : {});
  const page = await browser.newPage({ baseURL: BASE_URL });
  await page.goto('/overview');
  await page.getByLabel('Email').fill(ADMIN_EMAIL);
  await page.getByLabel('Password').fill(ADMIN_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible();
  mkdirSync('e2e/.auth', { recursive: true });
  await page.context().storageState({ path: 'e2e/.auth/session.json' });
  await browser.close();
}
