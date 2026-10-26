import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../App';
import { mockApi, renderAt } from './render';

describe('landing page', () => {
  beforeEach(() => window.sessionStorage.clear());

  it('opens with a full-screen intro that a click anywhere dismisses, once per tab', async () => {
    mockApi({});
    const { unmount } = renderAt('/', '*', <App />);

    const intro = screen.getByRole('button', { name: 'Enter Relay' });
    expect(intro).toHaveTextContent('Relay');
    fireEvent.click(intro);
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Enter Relay' })).not.toBeInTheDocument());
    unmount();

    renderAt('/', '*', <App />);
    expect(screen.queryByRole('button', { name: 'Enter Relay' })).not.toBeInTheDocument();
  });

  it('can be dismissed from the keyboard', async () => {
    mockApi({});
    renderAt('/', '*', <App />);
    fireEvent.keyDown(screen.getByRole('button', { name: 'Enter Relay' }), { key: 'Enter' });
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Enter Relay' })).not.toBeInTheDocument());
  });

  it('is public: it renders at / without asking the API who is signed in', async () => {
    const fetchMock = mockApi({});
    renderAt('/', '*', <App />);

    expect(screen.getByRole('heading', { level: 1, name: /Every webhook,\s*accounted for\./ })).toBeInTheDocument();
    for (const link of screen.getAllByRole('link', { name: 'Open dashboard' })) {
      expect(link).toHaveAttribute('href', '/overview');
    }
    expect(screen.getByRole('heading', { name: 'From one API call to a confirmed delivery.' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /github/i })).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('sends dashboard routes without a session to the sign-in page', async () => {
    mockApi({ '/api/v1/auth/me': () => new Response(JSON.stringify({ status: 401, code: 'UNAUTHENTICATED', detail: 'Sign in' }), { status: 401 }) });
    renderAt('/deliveries', '*', <App />);

    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '← Back to home' })).toHaveAttribute('href', '/');
    expect(screen.queryByText(/application-local\.yml/)).not.toBeInTheDocument();
  });
});
