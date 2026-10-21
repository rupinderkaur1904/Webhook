import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { DeliveryDetail } from '../api/types';
import { DeliveryDetailPage } from '../pages/DeliveryDetailPage';
import userEvent from '@testing-library/user-event';
import { deadLetter, deliveredDetail } from './fixtures';
import { jsonResponse, mockApi, renderAt } from './render';

const detail: DeliveryDetail = deliveredDetail;

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

describe('DeliveryDetailPage replay', () => {
  const dead: DeliveryDetail = {
    ...detail,
    delivery: { ...deadLetter },
    replayEligibility: { eligible: true, reason: null, replayCount: 0, maxReplays: 10 },
    chain: [deadLetter],
  };

  it('replays an eligible dead letter and opens the new delivery', async () => {
    const replay = { ...deadLetter, id: 'r2r2r2r2-0000-0000-0000-000000000000', status: 'PENDING', replayOfDeliveryId: deadLetter.id };
    const fetchMock = mockApi({
      [`/api/v1/deliveries/${deadLetter.id}/replay`]: () => jsonResponse(replay, 202),
      [`/api/v1/deliveries/${deadLetter.id}`]: dead,
      [`/api/v1/deliveries/${replay.id}`]: { ...dead, delivery: replay, chain: [deadLetter, replay] },
    });
    renderAt(`/deliveries/${deadLetter.id}`, '/deliveries/:id', <DeliveryDetailPage />);

    expect(await screen.findByText(/Replays used: 0 of 10/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Replay' }));
    // Nothing is sent until the side panel is confirmed.
    expect(fetchMock.mock.calls.some((c) => String(c[0]).endsWith('/replay'))).toBe(false);
    await userEvent.click(await screen.findByRole('button', { name: 'Replay delivery' }));

    expect(await screen.findByText('Replay queued as a new delivery')).toBeInTheDocument();
    const post = fetchMock.mock.calls.find((c) => String(c[0]).endsWith('/replay'))!;
    expect(post[1]?.method).toBe('POST');
    expect((await screen.findAllByText(/replay of 11111111/)).length).toBe(2);
    expect(await screen.findByText('Replay chain')).toBeInTheDocument();
  });

  it('disables replay and explains why when the delivery is not eligible', async () => {
    mockApi({
      [`/api/v1/deliveries/${deadLetter.id}`]: {
        ...dead,
        replayEligibility: { eligible: false, reason: 'ENDPOINT_DISABLED', replayCount: 1, maxReplays: 10 },
      },
    });
    renderAt(`/deliveries/${deadLetter.id}`, '/deliveries/:id', <DeliveryDetailPage />);

    expect(await screen.findByText(/The endpoint is disabled. Enable it to replay./)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Replay' })).toBeDisabled();
  });

  it('shows the server rejection when a replay races another one', async () => {
    mockApi({
      [`/api/v1/deliveries/${deadLetter.id}/replay`]: () =>
        jsonResponse(
          { status: 409, code: 'REPLAY_NOT_ELIGIBLE', reason: 'REPLAY_IN_PROGRESS', detail: 'A delivery for this event and endpoint is already in progress' },
          409,
        ),
      [`/api/v1/deliveries/${deadLetter.id}`]: dead,
    });
    renderAt(`/deliveries/${deadLetter.id}`, '/deliveries/:id', <DeliveryDetailPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Replay' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Replay delivery' }));
    expect(await screen.findByText('Replay rejected')).toBeInTheDocument();
    expect(screen.getByText('A delivery for this event and endpoint is already in progress')).toBeInTheDocument();
  });
});

describe('DeliveryDetailPage recovery', () => {
  it('shows the worker holding an in-flight delivery', async () => {
    mockApi({
      '/api/v1/deliveries/d1': {
        ...detail,
        delivery: { ...detail.delivery, status: 'IN_FLIGHT', deliveredAt: null },
        lease: { owner: 'host-a/1234abcd', expiresAt: '2026-10-15T04:01:00Z' },
        attempts: [{ ...detail.attempts[0], status: 'STARTED', outcome: null, responseStatus: null, finishedAt: null, durationMs: null, responseBodyExcerpt: null }],
      },
    });
    renderAt('/deliveries/d1', '/deliveries/:id', <DeliveryDetailPage />);
    expect(await screen.findByText(/host-a\/1234abcd/)).toBeInTheDocument();
    expect(screen.getByText('IN PROGRESS')).toBeInTheDocument();
  });

  it('marks abandoned attempts and late results that were not applied', async () => {
    mockApi({
      '/api/v1/deliveries/d1': {
        ...detail,
        attempts: [
          {
            ...detail.attempts[0],
            status: 'ABANDONED',
            outcome: 'SUCCESS',
            reportedAfterLeaseExpiry: true,
          },
          { ...detail.attempts[0], id: 'a2', attemptNumber: 2 },
        ],
      },
    });
    renderAt('/deliveries/d1', '/deliveries/:id', <DeliveryDetailPage />);
    expect(await screen.findByText('abandoned')).toBeInTheDocument();
    expect(screen.getByText('late result, not applied')).toBeInTheDocument();
  });
});
