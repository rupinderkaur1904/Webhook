import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { setApiKey } from '../api/client';
import type { DeliverySummary } from '../api/types';
import { DeliveriesPage } from '../pages/DeliveriesPage';
import { jsonResponse, renderAt } from './render';

const delivery: DeliverySummary = {
  id: '11111111-2222-3333-4444-555555555555',
  eventId: 'aaaaaaaa-2222-3333-4444-555555555555',
  endpointId: 'eeeeeeee-2222-3333-4444-555555555555',
  endpointName: 'orders-service',
  eventType: 'order.created',
  status: 'DEAD_LETTERED',
  attemptCount: 1,
  maxAttempts: 1,
  lastResponseStatus: 503,
  lastError: 'HTTP 503',
  lastAttemptAt: '2026-10-15T04:00:00Z',
  nextAttemptAt: null,
  deadLetterReason: 'RETRIES_EXHAUSTED',
  deliveredAt: null,
  deadLetteredAt: '2026-10-15T04:00:01Z',
  replayOfDeliveryId: null,
  createdAt: '2026-10-15T04:00:00Z',
};

describe('DeliveriesPage', () => {
  it('shows a loading state, then the deliveries from the API', async () => {
    setApiKey('whk_test');
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ items: [delivery], nextCursor: null }));
    renderAt('/deliveries', '/deliveries', <DeliveriesPage />);

    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(await screen.findByText('orders-service')).toBeInTheDocument();
    expect(screen.getByText('503')).toBeInTheDocument();
    expect(screen.getByText('DEAD LETTERED')).toBeInTheDocument();

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/v1/deliveries?limit=50');
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer whk_test');
  });

  it('passes status filters from the URL to the API', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ items: [], nextCursor: null }));
    renderAt('/deliveries?status=DEAD_LETTERED', '/deliveries', <DeliveriesPage />);

    expect(await screen.findByText('Nothing matches these filters.')).toBeInTheDocument();
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/deliveries?limit=50&status=DEAD_LETTERED');
  });

  it('shows an empty state when there are no deliveries', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ items: [], nextCursor: null }));
    renderAt('/deliveries', '/deliveries', <DeliveriesPage />);
    expect(await screen.findByText('No deliveries')).toBeInTheDocument();
  });

  it('shows the problem detail when the API fails', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ status: 401, code: 'UNAUTHENTICATED', detail: 'A valid API key is required' }, 401),
    );
    renderAt('/deliveries', '/deliveries', <DeliveriesPage />);
    expect(await screen.findByText('A valid API key is required')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('offers to load more when the API returns a next cursor', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ items: [delivery], nextCursor: 'abc' }));
    renderAt('/deliveries', '/deliveries', <DeliveriesPage />);
    expect(await screen.findByRole('button', { name: 'Load more' })).toBeInTheDocument();
  });
});
