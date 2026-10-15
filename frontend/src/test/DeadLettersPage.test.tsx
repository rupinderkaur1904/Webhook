import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { DeadLettersPage } from '../pages/DeadLettersPage';
import { deadLetter, endpoint } from './fixtures';
import { fetchedUrls, jsonResponse, mockApi, renderAt } from './render';

describe('DeadLettersPage', () => {
  it('lists dead letters with their reason and filters from the URL', async () => {
    const fetchMock = mockApi({
      '/api/v1/deliveries': { items: [deadLetter], nextCursor: null },
      '/api/v1/endpoints': { items: [endpoint] },
    });
    renderAt('/dead-letters?reason=RETRIES_EXHAUSTED', '/dead-letters', <DeadLettersPage />);

    const row = (await screen.findByRole('link', { name: '11111111' })).closest('tr')!;
    expect(within(row).getByText('Retries exhausted')).toBeInTheDocument();
    expect(fetchedUrls(fetchMock)).toContain(
      '/api/v1/deliveries?limit=50&status=DEAD_LETTERED&deadLetterReason=RETRIES_EXHAUSTED',
    );
  });

  it('replays a row', async () => {
    const fetchMock = mockApi({
      [`/api/v1/deliveries/${deadLetter.id}/replay`]: () =>
        jsonResponse({ ...deadLetter, id: 'new', status: 'PENDING', replayOfDeliveryId: deadLetter.id }, 202),
      '/api/v1/deliveries': { items: [deadLetter], nextCursor: null },
      '/api/v1/endpoints': { items: [endpoint] },
    });
    renderAt('/dead-letters', '/dead-letters', <DeadLettersPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Replay' }));
    expect(await screen.findByText('Replay queued as a new delivery')).toBeInTheDocument();
    expect(fetchedUrls(fetchMock)).toContain(`/api/v1/deliveries/${deadLetter.id}/replay`);
  });

  it('shows an empty state', async () => {
    mockApi({ '/api/v1/deliveries': { items: [], nextCursor: null }, '/api/v1/endpoints': { items: [] } });
    renderAt('/dead-letters', '/dead-letters', <DeadLettersPage />);
    expect(await screen.findByText('No dead letters')).toBeInTheDocument();
  });
});
