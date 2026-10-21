import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { EventDetail } from '../api/types';
import { EndpointDetailPage } from '../pages/EndpointDetailPage';
import { EventDetailPage } from '../pages/EventDetailPage';
import { EventsPage } from '../pages/EventsPage';
import { deadLetter, delivered, endpoint, healthy } from './fixtures';
import { fetchedUrls, jsonResponse, mockApi, renderAt } from './render';

describe('EventsPage', () => {
  it('shows each event with per-status delivery counts', async () => {
    mockApi({
      '/api/v1/events': {
        items: [
          {
            id: 'aaaaaaaa-0',
            eventType: 'order.created',
            idempotencyKey: 'order-1',
            createdAt: '2026-10-15T04:00:00Z',
            deliveryCount: 3,
            deliveredCount: 1,
            deadLetteredCount: 1,
            activeCount: 1,
          },
          {
            id: 'bbbbbbbb-0',
            eventType: 'user.deleted',
            idempotencyKey: 'u-1',
            createdAt: '2026-10-15T04:00:00Z',
            deliveryCount: 0,
            deliveredCount: 0,
            deadLetteredCount: 0,
            activeCount: 0,
          },
        ],
        nextCursor: null,
      },
    });
    renderAt('/events', '/events', <EventsPage />);
    expect(await screen.findByText('1 delivered')).toBeInTheDocument();
    expect(screen.getByText('1 in progress')).toBeInTheDocument();
    expect(screen.getByText('1 dead-lettered')).toBeInTheDocument();
    expect(screen.getByText('no matching endpoint')).toBeInTheDocument();
  });
});

describe('EventDetailPage', () => {
  it('shows the body and each delivery of the event', async () => {
    const event: EventDetail = {
      id: deadLetter.eventId,
      eventType: 'order.created',
      idempotencyKey: 'order-1',
      createdAt: '2026-10-15T04:00:00Z',
      occurredAt: null,
      body: { id: deadLetter.eventId, type: 'order.created', data: { orderId: 'ord_42' } },
      deliveries: [deadLetter, { ...delivered, eventId: deadLetter.eventId }],
    };
    mockApi({ [`/api/v1/events/${event.id}`]: event });
    renderAt(`/events/${event.id}`, '/events/:id', <EventDetailPage />);

    expect(await screen.findByText(/ord_42/)).toBeInTheDocument();
    expect(screen.getByText('Dead letter')).toBeInTheDocument();
    expect(screen.getByText('Delivered')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Replay' })).toBeInTheDocument();
  });
});

describe('EndpointDetailPage', () => {
  it('shows the endpoint and its deliveries, and can disable it', async () => {
    const fetchMock = mockApi({
      [`/api/v1/endpoints/${endpoint.id}`]: (_url: string, init?: RequestInit) =>
        jsonResponse(init?.method === 'PATCH' ? { ...endpoint, enabled: false } : endpoint),
      [`/api/v1/endpoints/${endpoint.id}/health`]: healthy,
      '/api/v1/deliveries': { items: [deadLetter], nextCursor: null },
    });
    renderAt(`/endpoints/${endpoint.id}`, '/endpoints/:id', <EndpointDetailPage />);

    expect(await screen.findByText('https://orders.example.com/hooks')).toBeInTheDocument();
    expect(screen.getByText('order.*')).toBeInTheDocument();
    expect(await screen.findByText('Dead letter')).toBeInTheDocument();
    expect(fetchedUrls(fetchMock)).toContain(`/api/v1/deliveries?limit=50&endpointId=${endpoint.id}`);

    await userEvent.click(screen.getByRole('switch', { name: 'Enabled' }));
    const patch = fetchMock.mock.calls.find((c) => c[1]?.method === 'PATCH')!;
    expect(String(patch[0])).toBe(`/api/v1/endpoints/${endpoint.id}`);
    expect(patch[1]?.body).toBe('{"enabled":false}');
  });
});
