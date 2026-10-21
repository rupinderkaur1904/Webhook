import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { DeadLettersPage } from '../pages/DeadLettersPage';
import { deadLetter, deliveredDetail, endpoint } from './fixtures';
import { fetchedUrls, jsonResponse, mockApi, renderAt } from './render';

describe('DeadLettersPage', () => {
  it('lists dead letters with their reason and filters from the URL', async () => {
    const fetchMock = mockApi({
      '/api/v1/deliveries': { items: [deadLetter], nextCursor: null },
      '/api/v1/endpoints': { items: [endpoint] },
    });
    renderAt('/dead-letters?reason=RETRIES_EXHAUSTED', '/dead-letters', <DeadLettersPage />);

    const card = await screen.findByRole('article', { name: 'Dead letter 11111111' });
    expect(within(card).getByText('Retries exhausted')).toBeInTheDocument();
    expect(within(card).getByText('HTTP 503')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Retries exhausted/ })).toHaveAttribute('aria-pressed', 'true');
    expect(fetchedUrls(fetchMock)).toContain(
      '/api/v1/deliveries?limit=50&status=DEAD_LETTERED&deadLetterReason=RETRIES_EXHAUSTED',
    );
  });

  it('confirms a replay in the side panel, then replays', async () => {
    const fetchMock = mockApi({
      [`/api/v1/deliveries/${deadLetter.id}/replay`]: () =>
        jsonResponse({ ...deadLetter, id: 'new', status: 'PENDING', replayOfDeliveryId: deadLetter.id }, 202),
      [`/api/v1/deliveries/${deadLetter.id}`]: {
        ...deliveredDetail,
        delivery: deadLetter,
        endpointUrl: 'https://orders.example.com/hooks',
        chain: [deadLetter],
        replayEligibility: { eligible: true, reason: null, replayCount: 2, maxReplays: 10 },
      },
      '/api/v1/deliveries': { items: [deadLetter], nextCursor: null },
      '/api/v1/endpoints': { items: [endpoint] },
    });
    renderAt('/dead-letters', '/dead-letters', <DeadLettersPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Replay' }));
    const panel = await screen.findByRole('dialog', { name: 'Replay 11111111?' });
    expect(await within(panel).findByText('https://orders.example.com/hooks')).toBeInTheDocument();
    expect(within(panel).getByText(deadLetter.eventId)).toBeInTheDocument();
    expect(within(panel).getByText('2 of 10')).toBeInTheDocument();
    expect(fetchedUrls(fetchMock)).not.toContain(`/api/v1/deliveries/${deadLetter.id}/replay`);

    await userEvent.click(within(panel).getByRole('button', { name: 'Replay delivery' }));
    expect(await screen.findByText('Replay queued as a new delivery')).toBeInTheDocument();
    expect(fetchedUrls(fetchMock)).toContain(`/api/v1/deliveries/${deadLetter.id}/replay`);
  });

  it('sends one replay when the confirm button is double-clicked', async () => {
    const fetchMock = mockApi({
      [`/api/v1/deliveries/${deadLetter.id}/replay`]: () =>
        jsonResponse({ ...deadLetter, id: 'new', status: 'PENDING', replayOfDeliveryId: deadLetter.id }, 202),
      [`/api/v1/deliveries/${deadLetter.id}`]: {
        ...deliveredDetail,
        delivery: deadLetter,
        chain: [deadLetter],
        replayEligibility: { eligible: true, reason: null, replayCount: 0, maxReplays: 10 },
      },
      '/api/v1/deliveries': { items: [deadLetter], nextCursor: null },
      '/api/v1/endpoints': { items: [endpoint] },
    });
    renderAt('/dead-letters', '/dead-letters', <DeadLettersPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Replay' }));
    const panel = await screen.findByRole('dialog', { name: 'Replay 11111111?' });
    // Two clicks before React re-renders the button as loading, as a fast double click does in a browser.
    const confirm = await within(panel).findByRole('button', { name: 'Replay delivery' });
    fireEvent.click(confirm);
    fireEvent.click(confirm);

    await waitFor(() => expect(panel).not.toBeInTheDocument());
    expect(fetchedUrls(fetchMock).filter((u) => u.endsWith('/replay'))).toHaveLength(1);
    expect(screen.queryByText('Replay rejected')).not.toBeInTheDocument();
  });

  it('warns before replaying a dead letter that a replay already delivered', async () => {
    const deliveredReplay = {
      ...deadLetter,
      id: '99999999-0000-0000-0000-000000000000',
      status: 'DELIVERED' as const,
      deadLetterReason: null,
      deliveredAt: '2026-10-15T10:00:00Z',
      replayOfDeliveryId: deadLetter.id,
    };
    mockApi({
      [`/api/v1/deliveries/${deadLetter.id}`]: {
        ...deliveredDetail,
        delivery: deadLetter,
        chain: [deadLetter, deliveredReplay],
        replayEligibility: { eligible: true, reason: null, replayCount: 1, maxReplays: 10 },
      },
      '/api/v1/deliveries': { items: [deadLetter], nextCursor: null },
      '/api/v1/endpoints': { items: [endpoint] },
    });
    renderAt('/dead-letters', '/dead-letters', <DeadLettersPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Replay' }));
    const panel = await screen.findByRole('dialog', { name: 'Replay 11111111?' });
    expect(await within(panel).findByText('Already delivered by a replay')).toBeInTheDocument();
    expect(within(panel).getByText(/Replay 99999999 delivered this event/)).toBeInTheDocument();
  });

  it('shows an empty state', async () => {
    mockApi({ '/api/v1/deliveries': { items: [], nextCursor: null }, '/api/v1/endpoints': { items: [] } });
    renderAt('/dead-letters', '/dead-letters', <DeadLettersPage />);
    expect(await screen.findByText('No dead letters')).toBeInTheDocument();
  });
});
