import { expect, test } from '@playwright/test';
import {
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

let endpointId: string | undefined;
let receiver: string;

test.beforeEach(() => {
  receiver = uniqueName('rcv');
  endpointId = undefined;
});

test.afterEach(async ({ page, request }) => {
  if (endpointId) await deleteEndpoint(page, endpointId);
  await deleteReceiver(request, receiver);
});

async function setUp(page: import('@playwright/test').Page, request: import('@playwright/test').APIRequestContext, behaviour: object) {
  const endpoint = await registerEndpointInUi(page, receiver, `${SIMULATOR_FROM_APP}/receivers/${receiver}`);
  endpointId = endpoint.id;
  await configureReceiver(request, receiver, { ...behaviour, secret: endpoint.secret });
  return endpoint;
}

test('an event is delivered, signed, and shown in the dashboard', async ({ page, request }) => {
  await setUp(page, request, { status: 200 });
  const deliveryId = await submitEvent(request, endpointId!);

  await expect.poll(() => deliveryStatus(request, deliveryId), { timeout: 30_000 }).toBe('DELIVERED');
  const received = await receivedRequests(request, receiver);
  expect(received).toHaveLength(1);
  expect(received[0].signatureResult).toBe('VALID');

  await page.goto(`/deliveries/${deliveryId}`);
  await expect(page.getByRole('heading', { name: 'Delivery' })).toBeVisible();
  await expect(page.getByText('DELIVERED', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('Attempt 1')).toBeVisible();
  await expect(page.getByText('HTTP 200')).toBeVisible();

  // A deep link survives a full reload: the server hands the route to the dashboard.
  await page.reload();
  await expect(page.getByText('Attempt 1')).toBeVisible();
});

test('failed attempts are retried until the receiver recovers', async ({ page, request }) => {
  await setUp(page, request, { status: 200, script: [{ status: 500 }, { status: 503 }] });
  const deliveryId = await submitEvent(request, endpointId!);

  await expect.poll(() => deliveryStatus(request, deliveryId), { timeout: 60_000 }).toBe('DELIVERED');
  const received = await receivedRequests(request, receiver);
  expect(received.map((r) => r.respondedWith)).toEqual([500, 503, 200]);
  // Every attempt is the same event: one webhook-id, seen again on each retry.
  expect(new Set(received.map((r) => r.webhookId)).size).toBe(1);

  await page.goto(`/deliveries/${deliveryId}`);
  await expect(page.getByText('Attempt 3')).toBeVisible();
  await expect(page.getByText('HTTP 500')).toBeVisible();
  await expect(page.getByText('HTTP 503')).toBeVisible();
  await expect(page.getByText('HTTP 200')).toBeVisible();
});

test('an exhausted delivery is dead-lettered and can be replayed from the dashboard', async ({ page, request }) => {
  await setUp(page, request, { status: 503 });
  const deliveryId = await submitEvent(request, endpointId!);
  await expect.poll(() => deliveryStatus(request, deliveryId), { timeout: 60_000 }).toBe('DEAD_LETTERED');

  await page.goto('/dead-letters');
  await expect(page.getByRole('heading', { name: 'Dead letters' })).toBeVisible();
  const row = page.getByRole('row').filter({ hasText: receiver });
  await expect(row).toBeVisible();
  await expect(row.getByText('DEAD LETTERED')).toBeVisible();

  // The receiver is fixed; the operator replays.
  await configureReceiver(request, receiver, { status: 200 });
  await row.getByRole('button', { name: 'Replay' }).click();
  await expect(page.getByText('Replay queued as a new delivery')).toBeVisible();

  await page.goto(`/deliveries/${deliveryId}`);
  await expect(page.getByText('Replay chain')).toBeVisible();
  await expect.poll(async () => {
    const received = await receivedRequests(request, receiver);
    return received.at(-1)?.respondedWith;
  }, { timeout: 30_000 }).toBe(200);
  // The original dead letter is kept as it was.
  expect(await deliveryStatus(request, deliveryId)).toBe('DEAD_LETTERED');
  const received = await receivedRequests(request, receiver);
  expect(new Set(received.map((r) => r.webhookId)).size).toBe(1);
});

test('a URL without a receiver path is explained, fixed with Edit, and the dead letter replayed', async ({ page, request }) => {
  // The mistake from the first manual run: the simulator's base URL instead of /receivers/{name}.
  const endpoint = await registerEndpointInUi(page, receiver, SIMULATOR_FROM_APP);
  endpointId = endpoint.id;
  await configureReceiver(request, receiver, { status: 200, secret: endpoint.secret });

  await page.getByRole('button', { name: 'Send test event' }).click();
  await expect(page.getByText('Test event queued')).toBeVisible();
  const row = page.getByRole('row').filter({ hasText: 'DEAD LETTERED' });
  await expect(row).toBeVisible({ timeout: 30_000 });
  await row.getByRole('link').first().click();
  await expect(page.getByText('HTTP 404').first()).toBeVisible();
  await expect(page.getByText(/Webhooks go to \/receivers\/\{name\}/)).toBeVisible();
  const deadLetterUrl = page.url();

  await page.getByRole('link', { name: receiver }).click();
  await page.getByRole('button', { name: 'Edit' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('URL').fill(`${SIMULATOR_FROM_APP}/receivers/${receiver}`);
  await dialog.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.getByText('Endpoint updated')).toBeVisible();
  await expect(page.getByText(`${SIMULATOR_FROM_APP}/receivers/${receiver}`)).toBeVisible();

  await page.goto(deadLetterUrl);
  await page.getByRole('button', { name: 'Replay' }).click();
  await expect(page.getByText('Replay queued as a new delivery')).toBeVisible();
  await expect.poll(async () => (await receivedRequests(request, receiver)).at(-1)?.signatureResult, { timeout: 30_000 }).toBe('VALID');
  expect((await receivedRequests(request, receiver)).at(-1)?.respondedWith).toBe(200);
});
