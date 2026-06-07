/**
 * auth.setup.ts — the suite's authentication setup project.
 *
 * Runs ONCE before every test project (playwright.config.ts wires the test projects
 * with `dependencies: ['setup']`). It establishes the session via the API — never
 * the UI — and persists it to STORAGE_STATE, which the test projects load via
 * `storageState`. The result: no individual test ever logs in, and there is no
 * fragile "first test does the setup" ordering across parallel workers.
 *
 * Named *.setup.ts (not *.spec.ts) on purpose: the default testMatch only globs
 * *.spec.ts / *.test.ts, so the chromium project won't re-run this file.
 */

import { expect, test as setup } from '@playwright/test'
import { BASE_URL, csrfHeaders, STORAGE_STATE, TEST_USER } from './helpers/auth'

setup('authenticate', async ({ request }) => {
  // Register the stable suite user. On a re-run the email already exists, which is
  // expected (no user-delete endpoint), so the register result is best-effort — the
  // session that matters comes from the login below. A genuinely broken register
  // surfaces as a failed login, with a clear error.
  await request.post(`${BASE_URL}/api/auth/register`, {
    headers: await csrfHeaders(request),
    data: TEST_USER,
  })

  const login = await request.post(`${BASE_URL}/api/auth/login`, {
    headers: await csrfHeaders(request),
    data: TEST_USER,
  })
  expect(login.ok(), `login failed: ${login.status()} ${await login.text()}`).toBeTruthy()

  // Persist JSESSIONID + XSRF-TOKEN for the test projects to replay.
  await request.storageState({ path: STORAGE_STATE })
})
