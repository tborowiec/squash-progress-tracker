import { defineConfig, devices } from '@playwright/test'
import { BASE_URL, STORAGE_STATE } from './e2e/helpers/auth'

/**
 * Playwright config for the E2E suite.
 *
 * Auth is handled by a dedicated `setup` project (e2e/auth.setup.ts) that the test
 * projects depend on. It runs to completion first, writes the authenticated session
 * to STORAGE_STATE, and the test projects replay that via `storageState` — so no test
 * logs in through the UI. See AGENTS.md / the /10x-e2e skill for the project rules.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: 'html',
  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
  },
  projects: [
    // Logs in via the API and writes STORAGE_STATE. Matches *.setup.ts only.
    { name: 'setup', testMatch: /.*\.setup\.ts/ },
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], storageState: STORAGE_STATE },
      dependencies: ['setup'],
    },
  ],
})
