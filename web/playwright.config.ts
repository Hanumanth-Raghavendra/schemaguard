import { defineConfig, devices } from '@playwright/test';

/**
 * One end-to-end test of the built app against a real API. The API is started outside Playwright
 * (see "Run the web app" in the README and the "End to end" CI job): it needs Java and, for
 * sharing, PostgreSQL. Playwright starts the built web app with `vite preview`.
 */
export default defineConfig({
  testDir: 'e2e',
  timeout: 60_000,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:4173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run preview -- --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: !process.env.CI,
  },
});
