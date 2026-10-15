import { expect, type APIRequestContext, type Page } from '@playwright/test';

export const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:8080';
export const SIMULATOR_URL = process.env.E2E_SIMULATOR_URL ?? 'http://localhost:9090';
/** How the platform reaches the simulator (inside compose: the service name). */
export const SIMULATOR_FROM_APP = process.env.E2E_SIMULATOR_FROM_APP ?? 'http://simulator:9090';
export const ADMIN_EMAIL = process.env.E2E_EMAIL ?? 'dev@localhost';
export const ADMIN_PASSWORD = process.env.E2E_PASSWORD ?? 'local-dev-password';
/** The producer API key (local profile bootstrap key). */
export const API_KEY = process.env.E2E_API_KEY ?? 'whk_local_dev_key_0000000000000000000000';
const SIMULATOR_TOKEN = process.env.E2E_SIMULATOR_TOKEN;

const simHeaders = SIMULATOR_TOKEN ? { Authorization: `Bearer ${SIMULATOR_TOKEN}` } : undefined;

export function uniqueName(prefix: string): string {
  return `e2e-${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
}

/** Sets how a simulator receiver answers. */
export async function configureReceiver(request: APIRequestContext, receiver: string, behaviour: object) {
  const response = await request.put(`${SIMULATOR_URL}/receivers/${receiver}/config`, { data: behaviour, headers: simHeaders });
  expect(response.ok()).toBeTruthy();
}

export interface ReceivedRequest {
  webhookId: string;
  signatureResult: string;
  duplicate: boolean;
  respondedWith: number;
}

export async function receivedRequests(request: APIRequestContext, receiver: string): Promise<ReceivedRequest[]> {
  const response = await request.get(`${SIMULATOR_URL}/receivers/${receiver}/requests`, { headers: simHeaders });
  expect(response.ok()).toBeTruthy();
  return response.json();
}

export async function deleteReceiver(request: APIRequestContext, receiver: string) {
  await request.delete(`${SIMULATOR_URL}/receivers/${receiver}`, { headers: simHeaders });
}

/** Submits an event as a producer would: API key and Idempotency-Key. Returns the first delivery id. */
export async function submitEvent(request: APIRequestContext, endpointId: string, payload: object = { orderId: 'ord_1' }) {
  const response = await request.post(`${BASE_URL}/api/v1/events`, {
    headers: { Authorization: `Bearer ${API_KEY}`, 'Idempotency-Key': uniqueName('key') },
    data: { eventType: 'order.created', payload, endpointIds: [endpointId] },
  });
  expect(response.status()).toBe(202);
  const body = await response.json();
  return body.deliveries[0].id as string;
}

/**
 * Registers an endpoint through the dashboard, as a person would, and returns its id and the signing
 * secret the dashboard showed once.
 */
export async function registerEndpointInUi(page: Page, name: string, url: string) {
  await page.goto('/endpoints');
  await page.getByRole('button', { name: 'Register endpoint' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Name').fill(name);
  await dialog.getByLabel('URL').fill(url);
  await dialog.getByRole('button', { name: 'Register', exact: true }).click();
  await expect(dialog.getByText('Copy the signing secret now')).toBeVisible();
  const secret = (await dialog.locator('pre, code').filter({ hasText: 'whsec_' }).first().innerText()).trim();
  await dialog.getByRole('button', { name: 'Done' }).click();
  await expect(dialog).toBeHidden();
  const row = page.getByRole('row').filter({ hasText: name });
  await expect(row).toBeVisible();
  await row.getByRole('link', { name }).click();
  await expect(page).toHaveURL(/\/endpoints\/[0-9a-f-]{36}$/);
  const id = page.url().split('/').pop()!;
  return { id, secret };
}

/** Deletes an endpoint with the page's session (CSRF header from the readable cookie). */
export async function deleteEndpoint(page: Page, id: string) {
  const cookies = await page.context().cookies();
  const csrf = cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '';
  await page.request.delete(`${BASE_URL}/api/v1/endpoints/${id}`, { headers: { 'X-XSRF-TOKEN': csrf } });
}

/** Reads a delivery with the producer key, as the platform reports it. */
export async function deliveryStatus(request: APIRequestContext, id: string): Promise<string> {
  const response = await request.get(`${BASE_URL}/api/v1/deliveries/${id}`, { headers: { Authorization: `Bearer ${API_KEY}` } });
  expect(response.ok()).toBeTruthy();
  return (await response.json()).delivery.status;
}
