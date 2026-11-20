import { defineConfig, devices } from '@playwright/test';

/**
 * The end-to-end test of the built app against a real API.
 *
 * - In CI ("End to end" job) and locally: the API jar runs on port 8080 (with PostgreSQL for
 *   sharing), and Playwright serves the built app with `vite preview` on port 4173.
 * - Against the live site: set E2E_BASE_URL (the "Live smoke test" workflow does); nothing is
 *   started, and waits are longer because the free API host may be waking up.
 */
const live = process.env.E2E_BASE_URL;

export default defineConfig({
  testDir: 'e2e',
  timeout: live ? 180_000 : 60_000,
  expect: { timeout: live ? 90_000 : 5_000 },
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: live ?? 'http://localhost:4173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  ...(live
    ? {}
    : {
        webServer: {
          command: 'npm run preview -- --port 4173 --strictPort',
          url: 'http://localhost:4173',
          reuseExistingServer: !process.env.CI,
        },
      }),
});
