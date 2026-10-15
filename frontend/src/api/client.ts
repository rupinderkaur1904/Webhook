import type { Problem } from './types';

const KEY_STORAGE = 'webhooks.apiKey';

/**
 * Stage 1 keeps the API key in sessionStorage (cleared when the tab closes). The security stage
 * replaces this with an HttpOnly session cookie so scripts cannot read the credential.
 */
export function getApiKey(): string | null {
  try {
    return sessionStorage.getItem(KEY_STORAGE);
  } catch {
    return null;
  }
}

export function setApiKey(key: string | null) {
  try {
    if (key) sessionStorage.setItem(KEY_STORAGE, key);
    else sessionStorage.removeItem(KEY_STORAGE);
  } catch {
    // storage unavailable: the key lives only for this page load
  }
}

export class ApiError extends Error {
  constructor(public readonly problem: Problem) {
    super(problem.detail || problem.code);
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const key = getApiKey();
  if (key) headers.set('Authorization', `Bearer ${key}`);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');

  let response: Response;
  try {
    response = await fetch(path, { ...init, headers });
  } catch {
    throw new ApiError({ status: 0, code: 'NETWORK_ERROR', detail: 'Could not reach the server' });
  }
  if (!response.ok) {
    let problem: Problem = { status: response.status, code: 'HTTP_' + response.status, detail: response.statusText };
    try {
      problem = { ...problem, ...(await response.json()) };
    } catch {
      // non-JSON error body
    }
    throw new ApiError(problem);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
