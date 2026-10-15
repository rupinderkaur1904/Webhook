import { defineConfig, devices } from '@playwright/test';

/**
 * Browser end-to-end tests against a running stack: `docker compose up --build` from the repository
 * root (app :8080 with the local profile, simulator :9090). See docs/TESTING.md.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    storageState: 'e2e/.auth/session.json',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    ...(process.env.E2E_CHROMIUM ? { launchOptions: { executablePath: process.env.E2E_CHROMIUM } } : {}),
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
