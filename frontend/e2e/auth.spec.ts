import { expect, test } from '@playwright/test';
import { ADMIN_EMAIL, ADMIN_PASSWORD } from './support';

// These tests sign in themselves, so they start without the saved session.
test.use({ storageState: { cookies: [], origins: [] } });

test('a wrong password is refused and the dashboard stays locked', async ({ page }) => {
  await page.goto('/deliveries');
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  await page.getByLabel('Email').fill(ADMIN_EMAIL);
  await page.getByLabel('Password').fill('not-the-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  // The API agrees: no session was created.
  const response = await page.request.get('/api/v1/endpoints');
  expect(response.status()).toBe(401);
});

test('signing out ends the session on the server', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Email').fill(ADMIN_EMAIL);
  await page.getByLabel('Password').fill(ADMIN_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible();
  expect((await page.request.get('/api/v1/endpoints')).status()).toBe(200);

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  expect((await page.request.get('/api/v1/endpoints')).status()).toBe(401);
});
