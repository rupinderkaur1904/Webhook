import { execSync } from 'node:child_process';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import {
  API_KEY,
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

/** Audit scenarios: the critical browser journeys not already covered by delivery.spec and secrets.spec. */

let endpointId: string | undefined;
let receiver: string;

test.beforeEach(() => {
  receiver = uniqueName('scn');
  endpointId = undefined;
});

test.afterEach(async ({ page, request }) => {
  if (endpointId) await deleteEndpoint(page, endpointId);
  await deleteReceiver(request, receiver);
});

async function setUp(page: Page, request: APIRequestContext, behaviour: object) {
  const endpoint = await registerEndpointInUi(page, receiver, `${SIMULATOR_FROM_APP}/receivers/${receiver}`);
  endpointId = endpoint.id;
  await configureReceiver(request, receiver, { ...behaviour, secret: endpoint.secret });
  return endpoint;
}

async function delivery(request: APIRequestContext, id: string) {
  const response = await request.get(`${BASE_URL}/api/v1/deliveries/${id}`, { headers: { Authorization: `Bearer ${API_KEY}` } });
  expect(response.ok()).toBeTruthy();
  return response.json();
}

test('unsafe or invalid endpoint URLs are refused in the dashboard', async ({ page }) => {
  await page.goto('/endpoints');
  for (const url of ['http://169.254.169.254/latest/meta-data', 'http://localhost:8080/api', 'http://10.0.0.5/hooks', 'not a url']) {
    await page.getByRole('button', { name: 'Register endpoint' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Name').fill(uniqueName('bad'));
    await dialog.getByLabel('URL').fill(url);
    await dialog.getByRole('button', { name: 'Register', exact: true }).click();
    await expect(dialog.getByRole('alert')).toBeVisible();
    await expect(dialog.getByText('Copy the signing secret now')).toHaveCount(0);
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
  }
  const list = await (await page.request.get('/api/v1/endpoints')).json();
  expect(list.items.filter((e: { name: string }) => e.name.startsWith('e2e-bad-'))).toHaveLength(0);
});

test('a transient failure is retried after the jittered backoff (1-2 s for the first retry locally)', async ({ page, request }) => {
  await setUp(page, request, { status: 200, script: [{ status: 503 }] });
  const id = await submitEvent(request, endpointId!);
  await expect.poll(() => deliveryStatus(request, id), { timeout: 30_000 }).toBe('DELIVERED');
  const detail = await delivery(request, id);
  expect(detail.attempts).toHaveLength(2);
  const [first, second] = detail.attempts;
  expect(first.responseStatus).toBe(503);
  expect(first.retryable).toBe(true);
  expect(second.responseStatus).toBe(200);
  // Equal jitter on a 2 s base: the retry is scheduled 1-2 s after the first attempt finished. The
  // worker polls every 500 ms, so it starts within that window plus one poll.
  const gap = Date.parse(second.startedAt) - Date.parse(first.finishedAt);
  expect(gap).toBeGreaterThanOrEqual(950);
  expect(gap).toBeLessThanOrEqual(2_000 + 1_000);

  await page.goto(`/deliveries/${id}`);
  await expect(page.getByText('Attempt 2')).toBeVisible();
  await expect(page.getByText('retryable', { exact: true })).toBeVisible();
});

test('a permanent failure is dead-lettered after one attempt and never retried', async ({ page, request }) => {
  await setUp(page, request, { status: 400 });
  const id = await submitEvent(request, endpointId!);
  await expect.poll(() => deliveryStatus(request, id), { timeout: 30_000 }).toBe('DEAD_LETTERED');
  const detail = await delivery(request, id);
  expect(detail.delivery.deadLetterReason).toBe('NON_RETRYABLE_RESPONSE');
  expect(detail.attempts).toHaveLength(1);
  expect(detail.attempts[0].retryable).toBe(false);
  // Still exactly one request after a further worker cycle or two.
  await page.waitForTimeout(3_000);
  expect(await receivedRequests(request, receiver)).toHaveLength(1);

  await page.goto(`/deliveries/${id}`);
  await expect(page.getByText('Non-retryable response')).toBeVisible();
  await expect(page.getByText('not retryable')).toBeVisible();
});

test('exhausted retries show every attempt in the history', async ({ page, request }) => {
  await setUp(page, request, { status: 500 });
  const id = await submitEvent(request, endpointId!);
  await expect.poll(() => deliveryStatus(request, id), { timeout: 60_000 }).toBe('DEAD_LETTERED');
  const detail = await delivery(request, id);
  expect(detail.delivery.deadLetterReason).toBe('RETRIES_EXHAUSTED');
  expect(detail.attempts.map((a: { attemptNumber: number }) => a.attemptNumber)).toEqual([1, 2, 3, 4]);
  expect(await receivedRequests(request, receiver)).toHaveLength(4);
  await page.goto(`/deliveries/${id}`);
  for (const n of [1, 2, 3, 4]) await expect(page.getByText(`Attempt ${n}`, { exact: true })).toBeVisible();
  await expect(page.getByText('4 of 4')).toBeVisible();
  await expect(page.getByText('Retries exhausted')).toBeVisible();
});

test('the circuit breaker pauses a failing endpoint, probes, and drains the backlog when it recovers', async ({ page, request }) => {
  await setUp(page, request, { status: 500 });
  // Local profile: the circuit opens after 10 consecutive failures, cool-down 5 s.
  const first = [];
  for (let i = 0; i < 10; i++) first.push(await submitEvent(request, endpointId!, { n: i }));
  await expect
    .poll(async () => (await (await page.request.get(`/api/v1/endpoints/${endpointId}`)).json()).circuit.state, { timeout: 30_000 })
    .toBe('OPEN');
  await page.goto(`/endpoints/${endpointId}`);
  await expect(page.getByText(/paused after \d+ consecutive failures/)).toBeVisible();

  // While open, new work waits without being sent.
  const before = (await receivedRequests(request, receiver)).length;
  const queued = await submitEvent(request, endpointId!, { n: 'queued' });
  await page.waitForTimeout(1_500);
  const whileOpen = await delivery(request, queued);
  expect(whileOpen.attempts).toHaveLength(0);

  await configureReceiver(request, receiver, { status: 200 });
  await expect
    .poll(async () => (await (await page.request.get(`/api/v1/endpoints/${endpointId}`)).json()).circuit.state, { timeout: 60_000 })
    .toBe('CLOSED');
  await expect.poll(() => deliveryStatus(request, queued), { timeout: 60_000 }).toBe('DELIVERED');
  expect((await receivedRequests(request, receiver)).length).toBeGreaterThan(before);
  await page.reload();
  await expect(page.getByText(/Circuit closed: deliveries to this endpoint are not paused/)).toBeVisible();
  // Deliveries that still had budget left drain as well (some wait out their backoff first); none is lost.
  for (const id of first) {
    await expect.poll(() => deliveryStatus(request, id), { timeout: 60_000 }).toMatch(/^(DELIVERED|DEAD_LETTERED)$/);
  }
});

test('work queued while the application restarts is delivered afterwards', async ({ page, request }) => {
  const restart = process.env.E2E_RESTART_CMD;
  test.skip(!restart, 'set E2E_RESTART_CMD (e.g. "docker compose restart app") to run');
  await setUp(page, request, { status: 503 });
  const id = await submitEvent(request, endpointId!);
  await expect.poll(async () => (await delivery(request, id)).attempts.length, { timeout: 30_000 }).toBeGreaterThanOrEqual(1);
  await configureReceiver(request, receiver, { status: 200 });
  execSync(restart!, { stdio: 'inherit', timeout: 120_000 });
  await expect
    .poll(async () => (await request.get(`${BASE_URL}/actuator/health`).catch(() => null))?.ok() ?? false, { timeout: 120_000 })
    .toBe(true);
  await expect.poll(() => deliveryStatus(request, id), { timeout: 90_000 }).toBe('DELIVERED');
});

test('protected routes refuse anonymous callers and API keys cannot manage the account', async ({ request, playwright }) => {
  // An explicit empty state: request contexts otherwise pick up the suite's signed-in storage state.
  const anonymous = await playwright.request.newContext({ baseURL: BASE_URL, storageState: { cookies: [], origins: [] } });
  for (const path of ['/api/v1/endpoints', '/api/v1/deliveries', '/api/v1/events', '/api/v1/api-keys', '/api/v1/metrics/overview']) {
    expect((await anonymous.get(path)).status(), path).toBe(401);
  }
  expect((await anonymous.get('/actuator/prometheus')).status()).toBe(401);
  await anonymous.dispose();

  const asKey = { Authorization: `Bearer ${API_KEY}`, 'Content-Type': 'application/json' };
  expect((await request.get(`${BASE_URL}/api/v1/deliveries`, { headers: asKey })).status()).toBe(200);
  const create = await request.post(`${BASE_URL}/api/v1/endpoints`, { headers: asKey, data: { name: 'x', url: 'https://example.com/h' } });
  expect(create.status()).toBe(403);
  expect((await request.get(`${BASE_URL}/api/v1/api-keys`, { headers: asKey })).status()).toBe(403);
});

test('the overview shows delivery metrics and endpoint health', async ({ page, request }) => {
  await setUp(page, request, { status: 200 });
  const id = await submitEvent(request, endpointId!);
  await expect.poll(() => deliveryStatus(request, id), { timeout: 30_000 }).toBe('DELIVERED');
  await page.goto('/overview');
  for (const label of ['Events submitted', 'Delivered', 'Dead-lettered', 'Delivery success rate', 'Attempt latency']) {
    await expect(page.getByText(label, { exact: true }).first()).toBeVisible();
  }
  await expect(page.getByRole('heading', { name: 'Endpoint health' })).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: receiver }).first()).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Recent attempts' })).toBeVisible();
});

test('every dashboard route survives a full reload', async ({ page }) => {
  for (const [path, heading] of [
    ['/overview', 'Overview'],
    ['/endpoints', 'Endpoints'],
    ['/events', 'Events'],
    ['/deliveries', 'Deliveries'],
    ['/dead-letters', 'Dead letters'],
    ['/api-keys', 'API keys'],
  ]) {
    await page.goto(path);
    await page.reload();
    await expect(page.getByRole('heading', { name: heading, level: 2 })).toBeVisible();
  }
});

test.describe('sign-out', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('the old session cookie is useless after sign-out', async ({ page, playwright }) => {
    await page.goto('/');
    await page.getByLabel('Email').fill(process.env.E2E_EMAIL ?? 'dev@localhost');
    await page.getByLabel('Password').fill(process.env.E2E_PASSWORD ?? 'local-dev-password');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible();
    const session = (await page.context().cookies()).find((c) => c.name === 'WEBHOOKS_SESSION');
    expect(session).toBeTruthy();

    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();

    // Replaying the captured cookie from a fresh client gets nothing.
    const replay = await playwright.request.newContext({
      baseURL: BASE_URL,
      storageState: { cookies: [], origins: [] },
      extraHTTPHeaders: { Cookie: `WEBHOOKS_SESSION=${session!.value}` },
    });
    expect((await replay.get('/api/v1/endpoints')).status()).toBe(401);
    expect((await replay.get('/api/v1/auth/me')).status()).toBe(401);
    await replay.dispose();
    await page.goto('/deliveries');
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  });
});
