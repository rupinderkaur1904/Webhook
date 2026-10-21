import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { App } from '../App';
import { mockApi, renderAt } from './render';

describe('landing page', () => {
  it('is public: it renders at / without asking the API who is signed in', async () => {
    const fetchMock = mockApi({});
    renderAt('/', '*', <App />);

    expect(screen.getByRole('heading', { level: 1, name: /Every webhook,\s*accounted for\./ })).toBeInTheDocument();
    for (const link of screen.getAllByRole('link', { name: 'Open dashboard' })) {
      expect(link).toHaveAttribute('href', '/overview');
    }
    expect(screen.getByRole('heading', { name: 'From one API call to a confirmed delivery.' })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('sends dashboard routes without a session to the sign-in page', async () => {
    mockApi({ '/api/v1/auth/me': () => new Response(JSON.stringify({ status: 401, code: 'UNAUTHENTICATED', detail: 'Sign in' }), { status: 401 }) });
    renderAt('/deliveries', '*', <App />);

    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '← Back to Relay overview' })).toHaveAttribute('href', '/');
  });
});
