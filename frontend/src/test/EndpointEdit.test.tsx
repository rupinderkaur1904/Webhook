import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Endpoint } from '../api/types';
import { EndpointDetailPage } from '../pages/EndpointDetailPage';
import { endpoint, healthy } from './fixtures';
import { jsonResponse, mockApi, renderAt } from './render';

const empty = { items: [], nextCursor: null };

describe('endpoint editing', () => {
  it('edits name, URL, description and event types with one PATCH', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    let current: Endpoint = endpoint;
    const fetchMock = mockApi({
      [`/api/v1/endpoints/${endpoint.id}/health`]: healthy,
      [`/api/v1/endpoints/${endpoint.id}`]: (_url: string, init?: RequestInit) => {
        if (init?.method === 'PATCH') current = { ...current, ...JSON.parse(String(init.body)) };
        return jsonResponse(current);
      },
      '/api/v1/deliveries': empty,
    });
    renderAt(`/endpoints/${endpoint.id}`, '/endpoints/:id', <EndpointDetailPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));

    const dialog = await screen.findByRole('dialog');
    const url = screen.getByLabelText(/^URL/);
    expect(url).toHaveValue('https://orders.example.com/hooks');
    expect(screen.getByLabelText(/^Event types/)).toHaveValue('order.*');
    await userEvent.clear(url);
    await userEvent.type(url, 'https://orders.example.com/v2/hooks');
    await userEvent.type(screen.getByLabelText(/^Description/), 'Production orders');
    await userEvent.clear(screen.getByLabelText(/^Event types/));
    await userEvent.type(screen.getByLabelText(/^Event types/), 'order.created, payment.*');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(dialog).not.toBeInTheDocument());
    const patch = fetchMock.mock.calls.find((c) => c[1]?.method === 'PATCH')!;
    expect(String(patch[0])).toBe(`/api/v1/endpoints/${endpoint.id}`);
    expect(JSON.parse(String(patch[1]?.body))).toEqual({
      name: 'orders-service',
      url: 'https://orders.example.com/v2/hooks',
      description: 'Production orders',
      eventTypes: ['order.created', 'payment.*'],
    });
    expect(new Headers(patch[1]?.headers).get('X-XSRF-TOKEN')).toBe('tok');
    expect(await screen.findByText('https://orders.example.com/v2/hooks')).toBeInTheDocument();
  });

  it('keeps the form open and shows why a change was refused', async () => {
    mockApi({
      [`/api/v1/endpoints/${endpoint.id}/health`]: healthy,
      [`/api/v1/endpoints/${endpoint.id}`]: (_url: string, init?: RequestInit) =>
        init?.method === 'PATCH'
          ? jsonResponse({ status: 422, code: 'URL_NOT_ALLOWED', detail: 'The URL resolves to a private address' }, 422)
          : jsonResponse(endpoint),
      '/api/v1/deliveries': empty,
    });
    renderAt(`/endpoints/${endpoint.id}`, '/endpoints/:id', <EndpointDetailPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    const url = await screen.findByLabelText(/^URL/);
    await userEvent.clear(url);
    await userEvent.type(url, 'http://10.0.0.5/hooks');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('The URL resolves to a private address')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
