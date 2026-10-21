import { screen, within } from '@testing-library/react';
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

  it('shows an empty state', async () => {
    mockApi({ '/api/v1/deliveries': { items: [], nextCursor: null }, '/api/v1/endpoints': { items: [] } });
    renderAt('/dead-letters', '/dead-letters', <DeadLettersPage />);
    expect(await screen.findByText('No dead letters')).toBeInTheDocument();
  });
});
