/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The same browser hardening the backend sends (SecurityConfig.CONTENT_SECURITY_POLICY); `vite preview`
// serves the production build with it, so a CSP violation shows up before deployment.
const securityHeaders = {
  'Content-Security-Policy':
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; " +
    "frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
  'X-Frame-Options': 'DENY',
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
};

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Same-origin in development: the dashboard calls /api/... and Vite forwards to the backend. The
    // trailing slash matters: the dashboard's own /api-keys page must not be proxied.
    proxy: { '/api/': 'http://localhost:8080' },
  },
  preview: {
    port: 4173,
    proxy: { '/api/': 'http://localhost:8080' },
    headers: securityHeaders,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // Playwright specs in e2e/ run against a live stack with `npm run e2e`, not under vitest.
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
