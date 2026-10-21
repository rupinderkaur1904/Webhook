import type { Problem } from './types';

/**
 * Dashboard requests ride on the HttpOnly session cookie, which scripts cannot read. State-changing
 * requests also echo the CSRF token: the server sets it in the readable XSRF-TOKEN cookie and checks
 * that the X-XSRF-TOKEN header matches.
 */
const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

export class ApiError extends Error {
  constructor(public readonly problem: Problem) {
    super(problem.detail || problem.code);
  }
}

export function readCookie(name: string): string | null {
  const prefix = `${name}=`;
  for (const part of document.cookie.split(';')) {
    const cookie = part.trim();
    if (cookie.startsWith(prefix)) return decodeURIComponent(cookie.slice(prefix.length));
  }
  return null;
}

/** Makes sure a CSRF token cookie exists (the server issues one on GET /auth/csrf). */
export async function ensureCsrfToken(force = false): Promise<string | null> {
  const existing = readCookie(CSRF_COOKIE);
  if (existing && !force) return existing;
  const response = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' });
  if (!response.ok) return readCookie(CSRF_COOKIE);
  const body = (await response.json()) as { token: string };
  return readCookie(CSRF_COOKIE) ?? body.token;
}

type UnauthorizedListener = () => void;
const unauthorizedListeners = new Set<UnauthorizedListener>();

/** Called whenever the server answers 401, i.e. the session has expired or was revoked. */
export function onUnauthorized(listener: UnauthorizedListener): () => void {
  unauthorizedListeners.add(listener);
  return () => unauthorizedListeners.delete(listener);
}

async function send(path: string, init: RequestInit, method: string): Promise<Response> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  if (!SAFE_METHODS.has(method)) {
    const token = await ensureCsrfToken();
    if (token) headers.set(CSRF_HEADER, token);
  }
  return fetch(path, { ...init, method, headers, credentials: 'same-origin' });
}

async function toProblem(response: Response): Promise<Problem> {
  let problem: Problem = { status: response.status, code: 'HTTP_' + response.status, detail: response.statusText };
  try {
    problem = { ...problem, ...(await response.json()) };
  } catch {
    // non-JSON error body
  }
  return problem;
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  let response: Response;
  try {
    response = await send(path, init, method);
    if (response.status === 403 && !SAFE_METHODS.has(method)) {
      const problem = await toProblem(response.clone());
      if (problem.code === 'CSRF_TOKEN_INVALID') {
        // The token rotates at login; fetch the current one and try once more.
        await ensureCsrfToken(true);
        response = await send(path, init, method);
      }
    }
  } catch {
    throw new ApiError({ status: 0, code: 'NETWORK_ERROR', detail: 'Could not reach the server' });
  }
  if (!response.ok) {
    const problem = await toProblem(response);
    if (response.status === 401 && !path.startsWith('/api/v1/auth/login')) {
      unauthorizedListeners.forEach((listener) => listener());
    }
    throw new ApiError(problem);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

/** The message to show for a failed request: the server's problem detail when there is one. */
export function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.problem.detail : 'Request failed';
}
