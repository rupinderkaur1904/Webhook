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

/** Regression journeys for the issues found in the product audit. */

test('a double click on Rotate secret rotates once, so a receiver on the old secret keeps verifying', async ({ page, request }) => {
  const receiver = uniqueName('dblrot');
  const endpoint = await registerEndpointInUi(page, receiver, `${SIMULATOR_FROM_APP}/receivers/${receiver}`);
  try {
    await configureReceiver(request, receiver, { status: 200, secret: endpoint.secret });
    const rotations: string[] = [];
    page.on('request', (r) => r.url().endsWith('/rotate-secret') && rotations.push(r.url()));
    await page.getByRole('button', { name: 'Rotate' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('button', { name: 'Rotate secret' }).dblclick();
    await expect(dialog.getByText('Copy the new secret now')).toBeVisible();
    await dialog.getByRole('button', { name: 'Done' }).click();
    expect(rotations).toHaveLength(1);

    // The receiver has not been updated yet: during the grace period the old secret still signs.
    const deliveryId = await submitEvent(request, endpoint.id);
    await expect.poll(() => deliveryStatus(request, deliveryId), { timeout: 30_000 }).toBe('DELIVERED');
    expect((await receivedRequests(request, receiver)).at(-1)?.signatureResult).toBe('VALID');
  } finally {
    await deleteEndpoint(page, endpoint.id);
    await deleteReceiver(request, receiver);
  }
});

test('an event published from the dashboard fans out to the matching endpoint only', async ({ page, request }) => {
  const receiver = uniqueName('pub');
  const eventType = `audit.${Date.now().toString(36)}`;
  await page.goto('/endpoints');
  await page.getByRole('button', { name: 'Register endpoint' }).click();
  let dialog = page.getByRole('dialog');
  await dialog.getByLabel('Name').fill(receiver);
  await dialog.getByLabel('URL').fill(`${SIMULATOR_FROM_APP}/receivers/${receiver}`);
  await dialog.getByLabel('Event types').fill(eventType);
  await dialog.getByRole('button', { name: 'Register', exact: true }).click();
  const secret = (await dialog.locator('code').filter({ hasText: 'whsec_' }).first().innerText()).trim();
  await dialog.getByRole('link', { name: 'Open endpoint' }).click();
  await expect(page.getByRole('heading', { name: receiver, level: 2 })).toBeVisible();
  const endpointId = page.url().split('/').pop()!;
  try {
    await configureReceiver(request, receiver, { status: 200, secret });

    await page.goto('/events');
    await page.getByRole('button', { name: 'Publish event' }).click();
    dialog = page.getByRole('dialog');
    await dialog.getByLabel('Event type').fill(eventType);
    await dialog.getByRole('button', { name: 'Publish event' }).click();
    // Other endpoints that take every event may also be listed; this one is there because its filter matches.
    await expect(dialog.getByText(/deliver(y|ies) queued/)).toBeVisible();
    await dialog.getByRole('listitem').filter({ hasText: receiver }).getByRole('link').click();
    await expect(page).toHaveURL(/\/deliveries\/[0-9a-f-]+$/);
    // Wait on the delivery itself: the page shows the word "Delivered" in labels before the attempt finishes.
    const deliveryId = page.url().split('/').pop()!;
    await expect.poll(() => deliveryStatus(request, deliveryId), { timeout: 30_000 }).toBe('DELIVERED');
    expect((await receivedRequests(request, receiver)).at(-1)?.signatureResult).toBe('VALID');

    // A type outside the endpoint's filter is accepted, but nothing is queued for this endpoint.
    await page.goto('/events');
    await page.getByRole('button', { name: 'Publish event' }).click();
    dialog = page.getByRole('dialog');
    await dialog.getByLabel('Event type').fill(`${eventType}x.unmatched`);
    await dialog.getByRole('button', { name: 'Publish event' }).click();
    await expect(dialog.getByText('Event accepted')).toBeVisible();
    await expect(dialog.getByText(receiver)).toHaveCount(0);
  } finally {
    await deleteEndpoint(page, endpointId);
    await deleteReceiver(request, receiver);
  }
});

test('an endpoint is deleted from its page after confirming', async ({ page }) => {
  const name = uniqueName('del');
  const endpoint = await registerEndpointInUi(page, name, `${SIMULATOR_FROM_APP}/receivers/${name}`);
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText(/can't be replayed/)).toBeVisible();
  await dialog.getByRole('button', { name: 'Delete endpoint' }).click();
  await expect(page).toHaveURL(/\/endpoints$/);
  await expect(page.getByRole('listitem').filter({ hasText: name })).toHaveCount(0);
  expect((await page.request.get(`${BASE_URL}/api/v1/endpoints/${endpoint.id}`)).status()).toBe(404);
});

test('dashboard pages load without Content-Security-Policy violations', async ({ page }) => {
  const violations: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error' && m.text().includes('Content Security Policy')) violations.push(m.text().slice(0, 120));
  });
  for (const path of ['/', '/overview', '/endpoints', '/events', '/deliveries', '/dead-letters', '/api-keys']) {
    await page.goto(path);
    await page.waitForLoadState('networkidle');
  }
  expect(violations).toEqual([]);
});
