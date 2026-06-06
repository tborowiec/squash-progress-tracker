// Fixture oracle: MatchApiIntegrationTests.java:64-95 (create/list response shape)
// Error body oracle: context/changes/ai-failure-path-tests/ (Phase 2 503 body)
import type { ApiError } from '../auth'
import type { MatchResponse } from '../matches'

export const matchFixture: MatchResponse = {
  id: 1,
  opponentName: 'Kowalski',
  matchDate: '2026-05-01',
  notes: 'Good game',
  sets: [
    { setNumber: 1, playerScore: 11, opponentScore: 5 },
    { setNumber: 2, playerScore: 11, opponentScore: 7 },
    { setNumber: 3, playerScore: 8, opponentScore: 11 },
    { setNumber: 4, playerScore: 11, opponentScore: 9 },
  ],
  setsWon: 3,
  setsLost: 1,
  result: 'WON',
}

export const matchListFixture: MatchResponse[] = [matchFixture]

export const apiErrorFixture: ApiError = {
  status: 503,
  message: 'AI service is temporarily unavailable',
  fieldErrors: null,
}
