import { expect, test } from '@playwright/test';
import {
  BASE_URL,
  SIMULATOR_FROM_APP,
  configureReceiver,
  deleteEndpoint,
  deleteReceiver,
  deliveryStatus,
  receivedRequests,
  registerEndpointInUi,
  submitEvent,
  uniqueName,
} from './support';

test('an API key is shown once, works, and stops working when revoked', async ({ page, request }) => {
  const name = uniqueName('key');
  await page.goto('/api-keys');
  await page.getByRole('button', { name: 'Create API key' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Name').fill(name);
  await dialog.getByRole('button', { name: 'Create key' }).click();
  await expect(dialog.getByText('Copy the key now')).toBeVisible();
  const key = (await dialog.locator('pre, code').filter({ hasText: 'whk_' }).first().innerText()).trim();
  await dialog.getByRole('button', { name: 'Done' }).click();

  const asKey = { headers: { Authorization: `Bearer ${key}` } };
  expect((await request.get(`${BASE_URL}/api/v1/deliveries`, asKey)).status()).toBe(200);
  // The list never shows the full key again.
  await expect(page.getByText(key)).toHaveCount(0);

  const row = page.getByRole('row').filter({ hasText: name });
  await row.getByRole('button', { name: 'Revoke' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Revoke key' }).click();
  await expect(page.getByText(`Revoked ${name}`)).toBeVisible();
  expect((await request.get(`${BASE_URL}/api/v1/deliveries`, asKey)).status()).toBe(401);
});

test('a rotated secret is shown once and both secrets sign during the grace period', async ({ page, request }) => {
  const receiver = uniqueName('rot');
  const endpoint = await registerEndpointInUi(page, receiver, `${SIMULATOR_FROM_APP}/receivers/${receiver}`);
  try {
    await page.getByRole('button', { name: 'Rotate' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Grace period (hours)').fill('1');
    await dialog.getByRole('button', { name: 'Rotate secret' }).click();
    await expect(dialog.getByText('Copy the new secret now')).toBeVisible();
    const rotated = (await dialog.locator('pre, code').filter({ hasText: 'whsec_' }).first().innerText()).trim();
    expect(rotated).not.toBe(endpoint.secret);
    await dialog.getByRole('button', { name: 'Done' }).click();
    await expect(page.getByText('Previous secret also signs until')).toBeVisible();

    // A receiver still on the old secret and one already on the new secret both verify.
    for (const secret of [endpoint.secret, rotated]) {
      await configureReceiver(request, receiver, { status: 200, secret });
      const deliveryId = await submitEvent(request, endpoint.id);
      await expect.poll(() => deliveryStatus(request, deliveryId), { timeout: 30_000 }).toBe('DELIVERED');
      expect((await receivedRequests(request, receiver)).at(-1)?.signatureResult).toBe('VALID');
    }
  } finally {
    await deleteEndpoint(page, endpoint.id);
    await deleteReceiver(request, receiver);
  }
});
