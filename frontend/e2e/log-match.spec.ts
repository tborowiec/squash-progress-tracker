/**
 * log-match.spec.ts — Phase 5 critical-flow e2e (test-plan §3 Phase 5; risk #6,
 * exercising #4/#5 at the integrated layer).
 *
 * RISK: a signed-in player logs a match through the AI-parse → confirm → save UI
 * and it shows up in their history. This fails if any boundary in the real
 * journey breaks — client routing, the save contract (#5), the confirm gate
 * (AGENTS hard rule: "always show the AI parse result for confirmation before
 * saving"), DB persistence, or the history render — on the deployed artifact (#6).
 *
 * Distinct from seed.spec.ts: the seed API-seeds the match and skips the whole
 * logging UI; this drives the real user journey (describe → parse → confirm → save).
 *
 * REAL vs MOCKED (the test's core value):
 *   - REAL: auth (replayed storageState), routing, POST /api/matches (the save),
 *     Postgres persistence, the history render, and the MatchForm confirm gate.
 *   - MOCKED: POST /api/matches/parse — fulfilled at the browser edge with a
 *     deterministic preview. The parse path calls live Gemini server-side and is
 *     non-deterministic; test-plan §4/§7 forbids gating CI on the live LLM. Mocking
 *     only this endpoint keeps the full parse→confirm→save→render UI journey under
 *     test while making it deterministic. Nothing internal is mocked.
 *
 * Seed: e2e/seed.spec.ts (the four rules — role/label locators, wait-for-state,
 * unique data, afterEach cleanup — are inherited from it).
 */

import {
  type APIRequestContext,
  expect,
  request as playwrightRequest,
  test,
} from '@playwright/test'
import { BASE_URL, csrfHeaders, STORAGE_STATE } from './helpers/auth'

let api: APIRequestContext

// RULE 3 — unique opponent per run+test so an orphan from a crashed run never
// collides, and so cleanup can find exactly this test's match by name.
const STAMP = Date.now()
let opponent: string
let seq = 0

// Build the API context from the already-authenticated session (same identity the
// browser context replays) — used only for post-test cleanup, never to seed.
test.beforeAll(async () => {
  api = await playwrightRequest.newContext({ storageState: STORAGE_STATE })
})

test.afterAll(async () => {
  await api?.dispose()
})

// RULE 4 — cleanup runs even if the test threw. The match is created through the UI
// (no id handed back to the test), so reclaim it by its unique opponent name.
test.afterEach(async () => {
  const res = await api.get(`${BASE_URL}/api/matches`, { params: { opponent } })
  if (res.ok()) {
    const matches: { id: number }[] = await res.json()
    const headers = await csrfHeaders(api)
    for (const m of matches) {
      await api.delete(`${BASE_URL}/api/matches/${m.id}`, { headers })
    }
  }
})

test('player logs a match via AI parse, confirms it, and sees it in history (risk #6: critical-flow)', async ({
  page,
}) => {
  opponent = `LogFlow-Opponent-${STAMP}-${++seq}` // RULE 3 — unique per test

  // Mock ONLY the parse endpoint at the browser edge: a deterministic preview in
  // place of the non-deterministic live-Gemini parse. Everything downstream (save,
  // persist, render) stays real. The returned opponent is this test's unique name,
  // so the saved record is traceable and cleanable.
  await page.route('**/api/matches/parse', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        opponentName: opponent,
        matchDate: '2026-01-15',
        notes: 'Tough second set.',
        sets: [
          { playerScore: 11, opponentScore: 5 },
          { playerScore: 8, opponentScore: 11 },
          { playerScore: 11, opponentScore: 7 },
        ],
      }),
    })
  })

  // Land on the log-match route. Reaching the AI-describe view (not /login) proves
  // the route guard admitted our replayed session — #4 at the integrated layer.
  // RULE 2 — wait for STATE (the heading), not a fixed delay.
  await page.goto('/matches/new')
  await expect(page.getByRole('heading', { name: 'Describe your match' })).toBeVisible()

  // Describe the match in natural language and ask the AI to parse it. The describe
  // box is the only textbox in AI mode. RULE 1 — locate by role, never CSS/DOM.
  await page.getByRole('textbox').fill(`beat ${opponent} 2-1, lost the second set`)
  await page.getByRole('button', { name: 'Parse with AI' }).click()

  // CONFIRM GATE — the parsed result is shown in the editable form for review before
  // any save (the AGENTS hard rule / risk #3 boundary). Asserting the parsed opponent
  // landed in the form, and that a Save button is now present, proves the preview is
  // what the player confirms — not a silent save.
  await expect(page.getByLabel('Opponent')).toHaveValue('opponent')
  const saveButton = page.getByRole('button', { name: 'Save match' })
  await expect(saveButton).toBeVisible()

  // Confirm and save — this hits the REAL POST /api/matches and persists to Postgres,
  // then the app routes to history. RULE 2 — wait for the URL to settle, not time.
  await saveButton.click()
  await page.waitForURL(url => new URL(url).pathname === '/history')

  // Business outcome (would this fail if risk #6 came true? yes): the just-logged
  // match is visible in this player's history. The opponent also renders as a hidden
  // <option> in the filter dropdown, so scope to the visible card occurrence.
  // toBeVisible() auto-waits on the render — no waitForTimeout().
  await expect(page.getByText(opponent).filter({ visible: true })).toBeVisible()
})
