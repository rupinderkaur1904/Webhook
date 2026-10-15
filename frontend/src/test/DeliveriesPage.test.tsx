import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DeliveriesPage } from '../pages/DeliveriesPage';
import { deadLetter, endpoint } from './fixtures';
import { fetchedUrls, jsonResponse, mockApi, renderAt } from './render';

const empty = { items: [], nextCursor: null };

describe('DeliveriesPage', () => {
  it('shows a loading state, then the deliveries from the API', async () => {
    const fetchMock = mockApi({
      '/api/v1/deliveries': { items: [deadLetter], nextCursor: null },
      '/api/v1/endpoints': { items: [endpoint] },
    });
    renderAt('/deliveries', '/deliveries', <DeliveriesPage />);

    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'orders-service' })).toBeInTheDocument();
    expect(screen.getByText('503')).toBeInTheDocument();
    expect(screen.getByText('DEAD LETTERED')).toBeInTheDocument();

    expect(fetchedUrls(fetchMock)).toContain('/api/v1/deliveries?limit=50');
    const call = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/v1/deliveries'))!;
    // Reads ride on the session cookie: no credential in a header, no CSRF token needed.
    expect(call[1]?.credentials).toBe('same-origin');
    expect(new Headers(call[1]?.headers).get('Authorization')).toBeNull();
    expect(new Headers(call[1]?.headers).get('X-XSRF-TOKEN')).toBeNull();
  });

  it('passes filters from the URL to the API', async () => {
    const fetchMock = mockApi({ '/api/v1/deliveries': empty, '/api/v1/endpoints': { items: [endpoint] } });
    renderAt(
      `/deliveries?status=DEAD_LETTERED&status=RETRYING&endpointId=${endpoint.id}&eventType=order.created`,
      '/deliveries',
      <DeliveriesPage />,
    );

    expect(await screen.findByText('Nothing matches these filters.')).toBeInTheDocument();
    expect(fetchedUrls(fetchMock)).toContain(
      `/api/v1/deliveries?limit=50&status=DEAD_LETTERED&status=RETRYING&endpointId=${endpoint.id}&eventType=order.created`,
    );
  });

  it('shows an empty state when there are no deliveries', async () => {
    mockApi({ '/api/v1/deliveries': empty, '/api/v1/endpoints': { items: [] } });
    renderAt('/deliveries', '/deliveries', <DeliveriesPage />);
    expect(await screen.findByText('No deliveries')).toBeInTheDocument();
  });

  it('shows the problem detail when the API fails', async () => {
    mockApi({
      '/api/v1/deliveries': () =>
        jsonResponse({ status: 401, code: 'UNAUTHENTICATED', detail: 'A valid API key is required' }, 401),
      '/api/v1/endpoints': { items: [] },
    });
    renderAt('/deliveries', '/deliveries', <DeliveriesPage />);
    expect(await screen.findByText('A valid API key is required')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('offers to load more when the API returns a next cursor', async () => {
    mockApi({ '/api/v1/deliveries': { items: [deadLetter], nextCursor: 'abc' }, '/api/v1/endpoints': { items: [] } });
    renderAt('/deliveries', '/deliveries', <DeliveriesPage />);
    expect(await screen.findByRole('button', { name: 'Load more' })).toBeInTheDocument();
  });

  it('marks replays in the list', async () => {
    mockApi({
      '/api/v1/deliveries': { items: [{ ...deadLetter, id: 'r1r1r1r1-0', replayOfDeliveryId: deadLetter.id }], nextCursor: null },
      '/api/v1/endpoints': { items: [] },
    });
    renderAt('/deliveries', '/deliveries', <DeliveriesPage />);
    expect(await screen.findByText('replay')).toBeInTheDocument();
  });
});
