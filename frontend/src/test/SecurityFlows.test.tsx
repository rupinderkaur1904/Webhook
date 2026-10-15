import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { api } from '../api/client';
import type { ApiKey } from '../api/types';
import { App } from '../App';
import { ApiKeysPage } from '../pages/ApiKeysPage';
import { EndpointDetailPage } from '../pages/EndpointDetailPage';
import { endpoint } from './fixtures';
import { jsonResponse, mockApi, renderAt } from './render';

const unauthenticated = () => jsonResponse({ status: 401, code: 'UNAUTHENTICATED', detail: 'Sign in' }, 401);
const user = { id: 'u1', email: 'dev@localhost' };
const empty = { items: [], nextCursor: null };

function callsTo(mock: ReturnType<typeof mockApi>, path: string, method?: string) {
  return mock.mock.calls.filter(
    (c) => String(c[0]).startsWith(path) && (method === undefined || (c[1]?.method ?? 'GET') === method),
  );
}

describe('dashboard sign-in', () => {
  it('shows the login page without a session, then signs in with the CSRF token', async () => {
    let signedIn = false;
    const mock = mockApi({
      '/api/v1/auth/me': () => (signedIn ? jsonResponse(user) : unauthenticated()),
      '/api/v1/auth/csrf': () => {
        document.cookie = 'XSRF-TOKEN=token-123; path=/';
        return jsonResponse({ headerName: 'X-XSRF-TOKEN', token: 'token-123' });
      },
      '/api/v1/auth/login': () => {
        signedIn = true;
        return jsonResponse(user);
      },
      '/api/v1/metrics/overview': () => jsonResponse({ status: 503, code: 'UNAVAILABLE', detail: 'not in this test' }, 503),
    });
    renderAt('/overview', '*', <App />);

    await userEvent.type(await screen.findByLabelText(/Email/), 'dev@localhost');
    await userEvent.type(screen.getByLabelText(/Password/), 'local-dev-password');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    expect(screen.getByText('dev@localhost')).toBeInTheDocument();
    const [login] = callsTo(mock, '/api/v1/auth/login', 'POST');
    expect(new Headers(login[1]?.headers).get('X-XSRF-TOKEN')).toBe('token-123');
    expect(login[1]?.credentials).toBe('same-origin');
    expect(JSON.parse(String(login[1]?.body))).toEqual({ email: 'dev@localhost', password: 'local-dev-password' });
  });

  it('shows the server message when the credentials are wrong', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    mockApi({
      '/api/v1/auth/me': unauthenticated,
      '/api/v1/auth/login': () =>
        jsonResponse({ status: 401, code: 'INVALID_CREDENTIALS', detail: 'Email or password is incorrect' }, 401),
    });
    renderAt('/overview', '*', <App />);
    await userEvent.type(await screen.findByLabelText(/Email/), 'dev@localhost');
    await userEvent.type(screen.getByLabelText(/Password/), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is incorrect');
  });

  it('returns to the login page when the session expires mid-use', async () => {
    let expired = false;
    mockApi({
      '/api/v1/auth/me': () => (expired ? unauthenticated() : jsonResponse(user)),
      '/api/v1/api-keys': () => (expired ? unauthenticated() : jsonResponse({ items: [] })),
    });
    renderAt('/api-keys', '*', <App />);
    expect(await screen.findByText('No API keys')).toBeInTheDocument();

    expired = true;
    await userEvent.click(screen.getByRole('button', { name: 'Create API key' }));
    await userEvent.type(await screen.findByLabelText(/Name/), 'x');
    document.cookie = 'XSRF-TOKEN=t; path=/';
    await userEvent.click(screen.getByRole('button', { name: 'Create key' }));
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('signs out through the server', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    const mock = mockApi({
      '/api/v1/auth/me': user,
      '/api/v1/auth/logout': () => new Response(null, { status: 204 }),
      '/api/v1/api-keys': { items: [] },
    });
    renderAt('/api-keys', '*', <App />);
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(callsTo(mock, '/api/v1/auth/logout', 'POST')).toHaveLength(1);
  });
});

describe('CSRF handling', () => {
  it('fetches a fresh token and retries once when the server rejects a stale one', async () => {
    document.cookie = 'XSRF-TOKEN=stale; path=/';
    let attempts = 0;
    const mock = mockApi({
      '/api/v1/auth/csrf': () => {
        document.cookie = 'XSRF-TOKEN=fresh; path=/';
        return jsonResponse({ headerName: 'X-XSRF-TOKEN', token: 'fresh' });
      },
      '/api/v1/api-keys': (_url: string, init?: RequestInit) => {
        attempts++;
        return new Headers(init?.headers).get('X-XSRF-TOKEN') === 'fresh'
          ? jsonResponse({ id: 'k', name: 'n', key: 'whk_x', prefix: 'whk_x' }, 201)
          : jsonResponse({ status: 403, code: 'CSRF_TOKEN_INVALID', detail: 'bad token' }, 403);
      },
    });
    await expect(api('/api/v1/api-keys', { method: 'POST', body: '{}' })).resolves.toMatchObject({ id: 'k' });
    expect(attempts).toBe(2);
    expect(callsTo(mock, '/api/v1/auth/csrf')).toHaveLength(1);
  });
});

describe('ApiKeysPage', () => {
  it('shows a new key exactly once and revokes keys with the CSRF header', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    let keys: ApiKey[] = [
      { id: 'k1', name: 'billing', prefix: 'whk_AbCdEfGh', createdAt: '2026-10-15T03:00:00Z', lastUsedAt: null, revokedAt: null },
    ];
    const mock = mockApi({
      '/api/v1/api-keys/k1': () => {
        keys = keys.map((k) => ({ ...k, revokedAt: '2026-10-15T04:00:00Z' }));
        return new Response(null, { status: 204 });
      },
      '/api/v1/api-keys': (_url: string, init?: RequestInit) =>
        init?.method === 'POST'
          ? jsonResponse({ id: 'k2', name: 'orders', key: 'whk_SECRET_shown_once', prefix: 'whk_SECRET_s' }, 201)
          : jsonResponse({ items: keys }),
    });
    renderAt('/api-keys', '/api-keys', <ApiKeysPage />);
    expect(await screen.findByText('billing')).toBeInTheDocument();
    expect(screen.getByText('active')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Create API key' }));
    await userEvent.type(await screen.findByLabelText(/Name/), 'orders');
    await userEvent.click(screen.getByRole('button', { name: 'Create key' }));
    expect(await screen.findByText('whk_SECRET_shown_once')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByText('whk_SECRET_shown_once')).not.toBeInTheDocument());

    await userEvent.click(screen.getByRole('button', { name: 'Revoke' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Revoke key' }));
    expect(await screen.findByText('revoked')).toBeInTheDocument();
    const [revoke] = callsTo(mock, '/api/v1/api-keys/k1', 'DELETE');
    expect(new Headers(revoke[1]?.headers).get('X-XSRF-TOKEN')).toBe('tok');
  });
});

describe('secret rotation', () => {
  it('rotates with the chosen grace period and shows the new secret once', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    const mock = mockApi({
      [`/api/v1/endpoints/${endpoint.id}/rotate-secret`]: {
        secret: 'whsec_NEWSECRETVALUE',
        secretPrefix: 'whsec_NEWSEC',
        previousSecretExpiresAt: '2026-10-16T03:00:00Z',
      },
      [`/api/v1/endpoints/${endpoint.id}`]: endpoint,
      '/api/v1/deliveries': empty,
    });
    renderAt(`/endpoints/${endpoint.id}`, '/endpoints/:id', <EndpointDetailPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Rotate' }));
    const grace = await screen.findByLabelText(/Grace period/);
    await userEvent.clear(grace);
    await userEvent.type(grace, '6');
    await userEvent.click(screen.getByRole('button', { name: 'Rotate secret' }));

    expect(await screen.findByText('whsec_NEWSECRETVALUE')).toBeInTheDocument();
    expect(screen.getByText(/signed with both the new and the old secret/)).toBeInTheDocument();
    const [call] = callsTo(mock, `/api/v1/endpoints/${endpoint.id}/rotate-secret`, 'POST');
    expect(JSON.parse(String(call[1]?.body))).toEqual({ gracePeriodHours: 6 });
  });
});
