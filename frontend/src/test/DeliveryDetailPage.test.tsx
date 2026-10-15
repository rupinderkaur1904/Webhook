import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { DeliveryDetail } from '../api/types';
import { DeliveryDetailPage } from '../pages/DeliveryDetailPage';
import { jsonResponse, renderAt } from './render';

const detail: DeliveryDetail = {
  delivery: {
    id: 'd1d1d1d1-0000-0000-0000-000000000000',
    eventId: 'e1e1e1e1-0000-0000-0000-000000000000',
    endpointId: 'p1p1p1p1-0000-0000-0000-000000000000',
    endpointName: 'billing',
    eventType: 'payment.succeeded',
    status: 'DELIVERED',
    attemptCount: 1,
    maxAttempts: 1,
    lastResponseStatus: 200,
    lastError: null,
    lastAttemptAt: '2026-10-15T04:00:00Z',
    nextAttemptAt: null,
    deadLetterReason: null,
    deliveredAt: '2026-10-15T04:00:01Z',
    deadLetteredAt: null,
    replayOfDeliveryId: null,
    createdAt: '2026-10-15T04:00:00Z',
  },
  endpointUrl: 'https://billing.example.com/hooks',
  eventBody: { id: 'e1e1e1e1', type: 'payment.succeeded', data: { amount: 100 } },
  attempts: [
    {
      id: 'a1',
      attemptNumber: 1,
      status: 'SUCCEEDED',
      outcome: 'SUCCESS',
      retryable: null,
      requestUrl: 'https://billing.example.com/hooks',
      responseStatus: 200,
      responseHeaders: {},
      responseBodyExcerpt: '{"ok":true}',
      errorMessage: null,
      retryAfterSeconds: null,
      startedAt: '2026-10-15T04:00:00Z',
      finishedAt: '2026-10-15T04:00:01Z',
      durationMs: 42,
    },
  ],
};

describe('DeliveryDetailPage', () => {
  it('shows the attempt history with status code and latency', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse(detail));
    renderAt('/deliveries/d1', '/deliveries/:id', <DeliveryDetailPage />);

    expect(await screen.findByText('Attempt 1')).toBeInTheDocument();
    expect(screen.getByText('HTTP 200')).toBeInTheDocument();
    expect(screen.getByText('42 ms')).toBeInTheDocument();
    expect(screen.getByText('{"ok":true}')).toBeInTheDocument();
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/deliveries/d1');
  });

  it('shows the retry schedule and why each failed attempt is retryable', async () => {
    const retrying: DeliveryDetail = {
      ...detail,
      delivery: { ...detail.delivery, status: 'RETRYING', attemptCount: 1, maxAttempts: 8, nextAttemptAt: '2026-10-15T04:01:00Z' },
      attempts: [
        {
          ...detail.attempts[0],
          status: 'FAILED',
          outcome: 'RATE_LIMITED',
          retryable: true,
          responseStatus: 429,
          retryAfterSeconds: 60,
          responseBodyExcerpt: null,
        },
      ],
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse(retrying));
    renderAt('/deliveries/d1', '/deliveries/:id', <DeliveryDetailPage />);

    expect(await screen.findByText('Next attempt')).toBeInTheDocument();
    expect(screen.getByText('RATE_LIMITED')).toBeInTheDocument();
    expect(screen.getByText('retryable')).toBeInTheDocument();
    expect(screen.getByText('Receiver asked to retry after 60s (Retry-After)')).toBeInTheDocument();
    expect(screen.getByText('1 of 8')).toBeInTheDocument();
  });

  it('shows not-found errors from the API', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ status: 404, code: 'NOT_FOUND', detail: 'Delivery not found' }, 404));
    renderAt('/deliveries/missing', '/deliveries/:id', <DeliveryDetailPage />);
    expect(await screen.findByText('Delivery not found')).toBeInTheDocument();
  });
});
