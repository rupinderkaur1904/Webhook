import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Overview } from '../api/types';
import { OverviewPage } from '../pages/OverviewPage';
import { fetchedUrls, mockApi, renderAt } from './render';

const overview: Overview = {
  window: '24h',
  windowStart: '2026-10-14T05:00:00Z',
  windowEnd: '2026-10-15T05:00:00Z',
  eventsSubmitted: 12,
  deliveriesSucceeded: 9,
  deliveriesDeadLettered: { total: 3, byReason: { RETRIES_EXHAUSTED: 2, NON_RETRYABLE_RESPONSE: 1 } },
  current: { pending: 4, retrying: 2, inFlight: 1 },
  deliverySuccessRate: 0.75,
  attempts: { succeeded: 9, failed: 6, successRate: 0.6, latencyP50Ms: 35, latencyP95Ms: 410 },
  recentActivity: [
    {
      attemptId: 'a1',
      deliveryId: 'd1d1d1d1-0000-0000-0000-000000000000',
      endpointId: 'e1',
      endpointName: 'billing',
      eventType: 'invoice.paid',
      attemptNumber: 2,
      status: 'FAILED',
      outcome: 'RATE_LIMITED',
      responseStatus: 429,
      durationMs: 12,
      startedAt: '2026-10-15T04:59:00Z',
    },
  ],
  endpointHealth: [
    {
      endpointId: 'e1',
      name: 'billing',
      circuit: { state: 'OPEN', consecutiveFailures: 10, openedAt: '2026-10-15T04:58:00Z', retryAt: '2026-10-15T05:01:00Z' },
      health: {
        status: 'FAILING',
        window: '24h',
        attempts: 20,
        failedAttempts: 12,
        successRate: 0.4,
        latencyP50Ms: 900,
        latencyP95Ms: 2000,
        deadLetters: 1,
        lastSuccessAt: '2026-10-15T04:00:00Z',
        lastFailure: { at: '2026-10-15T04:58:00Z', outcome: 'HTTP_SERVER_ERROR', responseStatus: 503, error: 'HTTP 503' },
      },
    },
    {
      endpointId: 'e2',
      name: 'crm',
      circuit: { state: 'CLOSED', consecutiveFailures: 0, openedAt: null, retryAt: null },
      health: {
        status: 'NO_DATA',
        window: '24h',
        attempts: 0,
        failedAttempts: 0,
        successRate: null,
        latencyP50Ms: null,
        latencyP95Ms: null,
        deadLetters: 0,
        lastSuccessAt: null,
        lastFailure: null,
      },
    },
  ],
};

describe('OverviewPage', () => {
  it('shows the metrics from the API with documented rates', async () => {
    mockApi({ '/api/v1/metrics/overview': overview });
    renderAt('/overview', '/overview', <OverviewPage />);

    expect(await screen.findByText('75.0%')).toBeInTheDocument();
    expect(screen.getByText('60.0%')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('Retries exhausted: 2 · Non-retryable response: 1')).toBeInTheDocument();
    expect(screen.getByText('35 ms')).toBeInTheDocument();
    expect(screen.getByText('p50 · p95 410 ms')).toBeInTheDocument();
    expect(screen.getByText('4 / 1')).toBeInTheDocument();
    expect(screen.getByText('RATE_LIMITED')).toBeInTheDocument();
    expect(screen.getByText('429')).toBeInTheDocument();
  });

  it('lists endpoint health worst first, with open circuits flagged', async () => {
    mockApi({ '/api/v1/metrics/overview': overview });
    renderAt('/overview', '/overview', <OverviewPage />);
    expect(await screen.findByText('Failing')).toBeInTheDocument();
    expect(screen.getByText('Circuit open')).toBeInTheDocument();
    expect(screen.getByText('No data')).toBeInTheDocument();
    expect(screen.getByText('40.0%')).toBeInTheDocument();
    expect(screen.getByText('2000 ms')).toBeInTheDocument();
    expect(screen.getByText('HTTP 503')).toBeInTheDocument();
    const badges = screen.getAllByTestId('health-badge').map((b) => b.textContent);
    expect(badges).toEqual(['Failing', 'No data']);
  });

  it('shows a dash instead of a rate when nothing finished in the window', async () => {
    mockApi({
      '/api/v1/metrics/overview': {
        ...overview,
        deliverySuccessRate: null,
        attempts: { ...overview.attempts, successRate: null, latencyP50Ms: null, latencyP95Ms: null },
        recentActivity: [],
      },
    });
    renderAt('/overview', '/overview', <OverviewPage />);
    expect(await screen.findByText('No delivery attempts yet')).toBeInTheDocument();
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(3);
  });

  it('switches the window', async () => {
    const fetchMock = mockApi({ '/api/v1/metrics/overview': overview });
    renderAt('/overview', '/overview', <OverviewPage />);
    await screen.findByText('75.0%');
    await userEvent.click(screen.getByText('Last 7d'));
    await screen.findByText('75.0%');
    expect(fetchedUrls(fetchMock)).toEqual(
      expect.arrayContaining(['/api/v1/metrics/overview?window=24h', '/api/v1/metrics/overview?window=7d']),
    );
  });
});
