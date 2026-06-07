/**
 * seed.spec.ts — the canonical E2E example for this project.
 *
 * RISK (test-plan §2, #6 — critical-flow happy path): a signed-in player's logged
 * match shows up in their history. The assertion below fails if that flow breaks
 * across the auth → routing → API → DB boundaries.
 *
 * The four rules this seed demonstrates:
 *   1. Role/label locators by default — getByRole / getByText, never CSS/XPath.
 *   2. Wait for STATE, not time — waitForURL() / toBeVisible(), never waitForTimeout().
 *   3. Unique identifiers for test data — timestamp suffix, so parallel runs and
 *      re-runs never collide.
 *   4. Cleanup in afterEach — reclaims each test's data even if its assertion threw.
 *
 * Auth uses the shared setup project (e2e/auth.setup.ts): it logs in once and writes
 * STORAGE_STATE, which the chromium project replays into every test's browser context
 * (playwright.config.ts). No test logs in through the UI. This file just consumes the
 * already-authenticated session — both for the browser (via the project's storageState)
 * and for its API seeding context (loaded from the same STORAGE_STATE below).
 */

import {
  type APIRequestContext,
  expect,
  request as playwrightRequest,
  test,
} from '@playwright/test'
import { BASE_URL, csrfHeaders, STORAGE_STATE, TEST_USER } from './helpers/auth'

let api: APIRequestContext
// Per-test data (created in beforeEach, deleted in afterEach) — each test is fully
// independent with its own match.
let seq = 0
let opponent: string
let matchId: number | undefined

// RULE 3 — unique suffix so an orphaned match from a crashed run never collides with
// this run's seeded data (the suite user is now stable across runs).
const STAMP = Date.now()

// Build the API seeding context from the session the setup project already
// established — STORAGE_STATE carries the authenticated JSESSIONID + XSRF-TOKEN, so
// no register/login here. Matches are created as TEST_USER, the same identity the
// browser context is signed in as.
test.beforeAll(async () => {
  api = await playwrightRequest.newContext({ storageState: STORAGE_STATE })
})

test.afterAll(async () => {
  await api?.dispose()
})

// Seed a fresh, uniquely-named match per test via the API — deterministic and it
// keeps the non-deterministic AI-parse path (live Gemini) out of the seed.
test.beforeEach(async () => {
  opponent = `Seed-Opponent-${STAMP}-${++seq}` // RULE 3 — unique per test
  const created = await api.post(`${BASE_URL}/api/matches`, {
    headers: await csrfHeaders(api),
    data: {
      opponentName: opponent,
      matchDate: '2026-01-15',
      sets: [
        { playerScore: 11, opponentScore: 5 },
        { playerScore: 11, opponentScore: 7 },
      ],
    },
  })
  expect(created.ok()).toBeTruthy()
  matchId = (await created.json()).id
})

// RULE 4 — cleanup in afterEach. Runs even if the test threw, so a failed run never
// leaves an orphan match behind.
test.afterEach(async () => {
  if (matchId !== undefined) {
    await api.delete(`${BASE_URL}/api/matches/${matchId}`, { headers: await csrfHeaders(api) })
    matchId = undefined
  }
})

test('player sees their logged match in history (risk #6: critical-flow happy path)', async ({
  page,
}) => {
  // No login step — the chromium project's storageState already authenticated us.
  // Landing on the dashboard and seeing our own welcome heading proves the replayed
  // session is live. RULE 1 — locate by role/accessible name, never by CSS or DOM position.
  await page.goto('/')
  await expect(page.getByRole('heading', { name: `Welcome, ${TEST_USER.email}` })).toBeVisible()

  // Navigate the way a user would — click the role-named nav link, then wait for the
  // route to settle. RULE 2 — wait for STATE (the URL), not a fixed delay.
  await page.getByRole('link', { name: 'History' }).click()
  await page.waitForURL(url => new URL(url).pathname === '/history')

  // Assert the business outcome (would this fail if risk #6 came true? yes): the
  // seeded match is visible in this player's history. The opponent name also renders
  // as a hidden <option> in the filter dropdown, so scope to the visible occurrence —
  // the rendered match card — keeping the locator resolved to one element.
  // toBeVisible() auto-waits on the rendered state — no waitForTimeout().
  await expect(page.getByText(opponent).filter({ visible: true })).toBeVisible()
})
