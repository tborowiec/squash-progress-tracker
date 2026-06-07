/**
 * auth.ts — shared E2E auth primitives used by the setup project (auth.setup.ts)
 * and by any test that needs to know the suite user (e.g. asserting the
 * "Welcome, <email>" heading). Keeping the credentials here means setup and tests
 * agree on a single identity without one re-logging-in through the UI.
 */

import type { APIRequestContext } from '@playwright/test'

// The integrated artifact serves the SPA and the API from one origin. Mirrors the
// seed; override per environment with E2E_BASE_URL.
export const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:8080'

// Where the setup project persists the authenticated session (JSESSIONID +
// XSRF-TOKEN). Every test project replays it via storageState. Gitignored — live
// cookies. Path is relative to the playwright cwd (frontend/), matching the seed.
export const STORAGE_STATE = 'e2e/.auth/user.json'

// One stable suite user, so its email is known to both setup and the tests that
// assert on it. Re-runs are tolerated: setup registers, ignores an already-existing
// account, then logs in (this backend has no user-delete endpoint). Override per
// environment via env vars.
export const TEST_USER = {
  email: process.env.E2E_USER_EMAIL ?? 'e2e-player@example.test',
  password: process.env.E2E_USER_PASSWORD ?? 'E2e-Pass-123!',
}

/**
 * This backend uses session cookies + CSRF (CookieCsrfTokenRepository, non-HttpOnly):
 * every mutating request must echo the XSRF-TOKEN cookie back in an X-XSRF-TOKEN
 * header. The cookie is materialized by a prior GET — we mirror the SPA's own
 * bootstrap (frontend/src/api/client.ts), which is the authoritative oracle here.
 */
export async function csrfHeaders(ctx: APIRequestContext): Promise<Record<string, string>> {
  await ctx.get(`${BASE_URL}/api/auth/me`) // 401 when anonymous; that's fine — it only sets the cookie
  const { cookies } = await ctx.storageState()
  const token = cookies.find(c => c.name === 'XSRF-TOKEN')?.value
  return token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {}
}
