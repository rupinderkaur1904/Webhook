import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Endpoint, EndpointHealth } from '../api/types';
import { EndpointDetailPage } from '../pages/EndpointDetailPage';
import { EndpointsPage } from '../pages/EndpointsPage';
import { endpoint, healthy } from './fixtures';
import { fetchedUrls, jsonResponse, mockApi, renderAt } from './render';

const failing: EndpointHealth = {
  ...healthy,
  status: 'FAILING',
  attempts: 12,
  failedAttempts: 10,
  successRate: 2 / 12,
  lastFailure: { at: '2026-10-15T04:58:00Z', outcome: 'TIMEOUT', responseStatus: null, error: 'Timed out after 10000 ms' },
};

const open: Endpoint = {
  ...endpoint,
  circuit: { state: 'OPEN', consecutiveFailures: 10, openedAt: '2026-10-15T04:58:00Z', retryAt: '2026-10-15T04:58:30Z' },
  health: failing,
};

const empty = { items: [], nextCursor: null };

describe('endpoint health', () => {
  it('shows health and circuit state in the endpoint list', async () => {
    mockApi({ '/api/v1/endpoints': { items: [open, { ...endpoint, id: 'other', name: 'crm' }] } });
    renderAt('/endpoints', '/endpoints', <EndpointsPage />);
    expect(await screen.findByText('Failing')).toBeInTheDocument();
    expect(screen.getByText('Circuit open')).toBeInTheDocument();
    expect(screen.getByText('Healthy')).toBeInTheDocument();
    expect(screen.getByText('16.7% of 12 attempts')).toBeInTheDocument();
  });

  it('explains an open circuit and closes it by hand with the CSRF header', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    let current = open;
    const fetchMock = mockApi({
      [`/api/v1/endpoints/${endpoint.id}/circuit/reset`]: () => {
        current = { ...endpoint, health: failing };
        return jsonResponse(current);
      },
      [`/api/v1/endpoints/${endpoint.id}/health`]: failing,
      [`/api/v1/endpoints/${endpoint.id}`]: () => jsonResponse(current),
      '/api/v1/deliveries': empty,
    });
    renderAt(`/endpoints/${endpoint.id}`, '/endpoints/:id', <EndpointDetailPage />);

    expect(await screen.findByText(/paused after 10 consecutive failures/)).toBeInTheDocument();
    expect(await screen.findByText(/Timed out after 10000 ms/)).toBeInTheDocument();
    expect(screen.getByText('16.7% (2/12)')).toBeInTheDocument();
    expect(screen.getByText('42 ms / 180 ms')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Close circuit now' }));
    expect(await screen.findByText('Deliveries flow normally.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Close circuit now' })).not.toBeInTheDocument();
    const reset = fetchMock.mock.calls.find((c) => String(c[0]).endsWith('/circuit/reset'))!;
    expect(reset[1]?.method).toBe('POST');
    expect(new Headers(reset[1]?.headers).get('X-XSRF-TOKEN')).toBe('tok');
  });

  it('switches the health window', async () => {
    const fetchMock = mockApi({
      [`/api/v1/endpoints/${endpoint.id}/health`]: healthy,
      [`/api/v1/endpoints/${endpoint.id}`]: endpoint,
      '/api/v1/deliveries': empty,
    });
    renderAt(`/endpoints/${endpoint.id}`, '/endpoints/:id', <EndpointDetailPage />);
    expect(await screen.findByText('Deliveries flow normally.')).toBeInTheDocument();
    await userEvent.click(screen.getByText('7d'));
    await screen.findByText('95.0% (38/40)');
    expect(fetchedUrls(fetchMock)).toEqual(
      expect.arrayContaining([
        `/api/v1/endpoints/${endpoint.id}/health?window=24h`,
        `/api/v1/endpoints/${endpoint.id}/health?window=7d`,
      ]),
    );
  });
});
