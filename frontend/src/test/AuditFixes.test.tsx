import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { ApiError, errorMessage } from '../api/client';
import { PublishEventModal } from '../components/PublishEventModal';
import { ApiKeysPage } from '../pages/ApiKeysPage';
import { EndpointDetailPage } from '../pages/EndpointDetailPage';
import { EndpointsPage } from '../pages/EndpointsPage';
import { inlineAsset } from '../build/inlineAsset';
import { endpoint, healthy } from './fixtures';
import { jsonResponse, mockApi, renderAt } from './render';

const empty = { items: [], nextCursor: null };

function calls(fetchMock: ReturnType<typeof mockApi>, method: string, suffix: string) {
  return fetchMock.mock.calls.filter((c) => c[1]?.method === method && String(c[0]).endsWith(suffix));
}

describe('validation errors name the field', () => {
  it('appends field errors to the generic detail', () => {
    const error = new ApiError({
      status: 422,
      code: 'VALIDATION_FAILED',
      detail: 'Request validation failed',
      errors: [
        { field: 'name', message: 'must not be blank' },
        { field: 'eventTypes[1]', message: 'must be an event type such as order.created, or a prefix such as order.*' },
      ],
    });
    expect(errorMessage(error)).toBe(
      'Request validation failed. Name: must not be blank; Event types (entry 2): must be an event type such as order.created, or a prefix such as order.*',
    );
  });

  it('does not repeat a field error that is already the detail', () => {
    const error = new ApiError({
      status: 422,
      code: 'URL_NOT_ALLOWED',
      detail: 'URL scheme must be https',
      errors: [{ field: 'url', message: 'URL scheme must be https' }],
    });
    expect(errorMessage(error)).toBe('URL scheme must be https');
  });

  it('shows what is wrong when registering an endpoint fails validation', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    mockApi({
      '/api/v1/endpoints': (_url: string, init?: RequestInit) =>
        init?.method === 'POST'
          ? jsonResponse(
              {
                status: 422,
                code: 'VALIDATION_FAILED',
                detail: 'Request validation failed',
                errors: [{ field: 'name', message: 'must not be blank' }],
              },
              422,
            )
          : jsonResponse({ items: [] }),
    });
    renderAt('/endpoints', '/endpoints', <EndpointsPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Register endpoint' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText(/^Name/), ' ');
    await userEvent.type(within(dialog).getByLabelText(/^URL/), 'https://example.com/hooks');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Register' }));
    expect(await within(dialog).findByText('Request validation failed. Name: must not be blank')).toBeInTheDocument();
  });
});

describe('one-shot actions send one request on a double click', () => {
  it('rotates the signing secret once, so the old secret keeps signing during the grace period', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    const fetchMock = mockApi({
      [`/api/v1/endpoints/${endpoint.id}/health`]: healthy,
      [`/api/v1/endpoints/${endpoint.id}/rotate-secret`]: () =>
        jsonResponse({ secret: 'whsec_new', secretPrefix: 'whsec_new', previousSecretExpiresAt: '2026-10-16T05:00:00Z' }),
      [`/api/v1/endpoints/${endpoint.id}`]: endpoint,
      '/api/v1/deliveries': empty,
    });
    renderAt(`/endpoints/${endpoint.id}`, '/endpoints/:id', <EndpointDetailPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Rotate' }));
    const rotate = await screen.findByRole('button', { name: 'Rotate secret' });
    fireEvent.click(rotate);
    fireEvent.click(rotate);

    expect(await screen.findByText('Copy the new secret now')).toBeInTheDocument();
    expect(calls(fetchMock, 'POST', '/rotate-secret')).toHaveLength(1);
  });

  it('creates one API key', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    const fetchMock = mockApi({
      '/api/v1/api-keys': (_url: string, init?: RequestInit) =>
        init?.method === 'POST'
          ? jsonResponse({ id: 'k1', name: 'orders', key: 'whk_secret_value', prefix: 'whk_secr' }, 201)
          : jsonResponse({ items: [] }),
    });
    renderAt('/api-keys', '/api-keys', <ApiKeysPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Create API key' }));
    await userEvent.type(await screen.findByLabelText(/^Name/), 'orders');
    const create = screen.getByRole('button', { name: 'Create key' });
    fireEvent.click(create);
    fireEvent.click(create);

    expect(await screen.findByText('whk_secret_value')).toBeInTheDocument();
    expect(calls(fetchMock, 'POST', '/api/v1/api-keys')).toHaveLength(1);
  });
});

describe('deleting an endpoint', () => {
  it('asks first, then deletes and returns to the list', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    const fetchMock = mockApi({
      [`/api/v1/endpoints/${endpoint.id}/health`]: healthy,
      [`/api/v1/endpoints/${endpoint.id}`]: (_url: string, init?: RequestInit) =>
        init?.method === 'DELETE' ? new Response(null, { status: 204 }) : jsonResponse(endpoint),
      '/api/v1/deliveries': empty,
    });
    renderAt(`/endpoints/${endpoint.id}`, '/endpoints/:id', <EndpointDetailPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    const dialog = await screen.findByRole('dialog', { name: `Delete ${endpoint.name}?` });
    expect(within(dialog).getByText(/can't be replayed/)).toBeInTheDocument();
    expect(calls(fetchMock, 'DELETE', endpoint.id)).toHaveLength(0);

    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete endpoint' }));
    expect(await screen.findByText(`Deleted ${endpoint.name}`)).toBeInTheDocument();
    expect(calls(fetchMock, 'DELETE', endpoint.id)).toHaveLength(1);
  });
});

describe('publishing an event from the dashboard', () => {
  const accepted = (deliveries: unknown[]) => ({
    id: 'e1e1e1e1-0000-0000-0000-000000000000',
    eventType: 'order.created',
    idempotencyKey: 'k',
    createdAt: '2026-10-15T05:00:00Z',
    deliveries,
  });

  it('refuses a payload that is not a JSON object before sending', async () => {
    const fetchMock = mockApi({ '/api/v1/endpoints': { items: [endpoint] } });
    renderAt('/events', '/events', <PublishEventModal opened onClose={() => {}} />);
    await userEvent.type(await screen.findByLabelText(/^Event type/), 'order.created');
    const payload = screen.getByLabelText(/^Payload/);
    await userEvent.clear(payload);
    fireEvent.change(payload, { target: { value: '[1, 2]' } });
    await userEvent.click(screen.getByRole('button', { name: 'Publish event' }));

    expect(await screen.findByText(/The payload must be a JSON object/)).toBeInTheDocument();
    expect(calls(fetchMock, 'POST', '/api/v1/events')).toHaveLength(0);
  });

  it('sends the event with an Idempotency-Key and lists the queued deliveries', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    const fetchMock = mockApi({
      '/api/v1/endpoints': { items: [endpoint] },
      '/api/v1/events': () =>
        jsonResponse(accepted([{ id: 'd1d1d1d1-0000-0000-0000-000000000000', endpointId: endpoint.id, status: 'PENDING' }]), 202),
    });
    renderAt('/events', '/events', <PublishEventModal opened onClose={() => {}} />);
    await userEvent.type(await screen.findByLabelText(/^Event type/), 'order.created');
    const publish = screen.getByRole('button', { name: 'Publish event' });
    fireEvent.click(publish);
    fireEvent.click(publish);

    expect(await screen.findByText('1 delivery queued')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'd1d1d1d1' })).toHaveAttribute('href', '/deliveries/d1d1d1d1-0000-0000-0000-000000000000');
    expect(screen.getByText(endpoint.name)).toBeInTheDocument();
    expect(screen.getByText(/Accepted means the event is stored and queued/)).toBeInTheDocument();
    const posts = calls(fetchMock, 'POST', '/api/v1/events');
    expect(posts).toHaveLength(1);
    const headers = new Headers(posts[0][1]?.headers);
    expect(headers.get('Idempotency-Key')).toMatch(/^dashboard-/);
    expect(JSON.parse(String(posts[0][1]?.body))).toEqual({ eventType: 'order.created', payload: { orderId: '1001', amount: 4999 } });
  });

  it('explains when no endpoint matched', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    mockApi({ '/api/v1/endpoints': { items: [endpoint] }, '/api/v1/events': () => jsonResponse(accepted([]), 202) });
    renderAt('/events', '/events', <PublishEventModal opened onClose={() => {}} />);
    await userEvent.type(await screen.findByLabelText(/^Event type/), 'order.created');
    await userEvent.click(screen.getByRole('button', { name: 'Publish event' }));
    expect(await screen.findByText('No delivery was created')).toBeInTheDocument();
  });

  it('sends only to the chosen endpoints', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    const fetchMock = mockApi({ '/api/v1/endpoints': { items: [endpoint] }, '/api/v1/events': () => jsonResponse(accepted([]), 202) });
    renderAt('/events', '/events', <PublishEventModal opened onClose={() => {}} />);
    await userEvent.type(await screen.findByLabelText(/^Event type/), 'order.created');
    await userEvent.click(screen.getByText('Chosen endpoints'));
    await userEvent.click(await screen.findByPlaceholderText('Pick endpoints'));
    await userEvent.click(await screen.findByText(endpoint.name));
    await userEvent.click(screen.getByRole('button', { name: 'Publish event' }));
    await waitFor(() => expect(calls(fetchMock, 'POST', '/api/v1/events')).toHaveLength(1));
    expect(JSON.parse(String(calls(fetchMock, 'POST', '/api/v1/events')[0][1]?.body)).endpointIds).toEqual([endpoint.id]);
  });
});

describe('fonts under the Content-Security-Policy', () => {
  it('never inlines fonts as data: URIs, which font-src self would block', () => {
    expect(inlineAsset('/x/geist-cyrillic-ext-400-normal.woff2')).toBe(false);
    expect(inlineAsset('/x/geist-latin-400-normal.woff')).toBe(false);
    expect(inlineAsset('/x/logo.svg')).toBeUndefined();
  });
});
