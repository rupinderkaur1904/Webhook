import { vi } from 'vitest';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

export function renderAt(path: string, route: string, element: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <Notifications />
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path={route} element={element} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

export function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/**
 * Mocks fetch by URL: each key is a path prefix (longest match wins) mapped to a response body or a
 * function returning a Response. Unmatched requests fail the test with a 500.
 */
export function mockApi(routes: Record<string, unknown | ((url: string, init?: RequestInit) => Response)>) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = String(input);
    const key = Object.keys(routes)
      .filter((prefix) => url.startsWith(prefix))
      .sort((a, b) => b.length - a.length)[0];
    if (key === undefined) return jsonResponse({ status: 500, code: 'UNMOCKED', detail: `No mock for ${url}` }, 500);
    const route = routes[key];
    return typeof route === 'function' ? (route as (u: string, i?: RequestInit) => Response)(url, init) : jsonResponse(route);
  });
}

/** URLs fetched so far, in order. */
export function fetchedUrls(mock: ReturnType<typeof mockApi>) {
  return mock.mock.calls.map((c) => String(c[0]));
}
