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
