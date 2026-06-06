import { apiErrorFixture, matchFixture, matchListFixture } from './__fixtures__/match-contract'
import client from './client'
import { createMatch, listMatches } from './matches'

// Stub the axios singleton — no real transport, no CSRF bootstrap, no network.
vi.mock('./client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

const mockPost = vi.mocked(client.post)
const mockGet = vi.mocked(client.get)

const createRequest = {
  opponentName: 'Kowalski',
  matchDate: '2026-05-01',
  notes: 'Good game',
  sets: [
    { playerScore: 11, opponentScore: 5 },
    { playerScore: 11, opponentScore: 7 },
    { playerScore: 8, opponentScore: 11 },
    { playerScore: 11, opponentScore: 9 },
  ],
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('matches api-client contract', () => {
  describe('createMatch', () => {
    it('decodes the backend JSON into the expected MatchResponse shape', async () => {
      mockPost.mockResolvedValue({ data: matchFixture } as any)

      const result = await createMatch(createRequest)

      expect(result.id).toBe(matchFixture.id)
      expect(result.opponentName).toBe('Kowalski')
      expect(result.matchDate).toBe('2026-05-01')
      expect(result.notes).toBe('Good game')
      expect(result.setsWon).toBe(3)
      expect(result.setsLost).toBe(1)
      // result must be the bare enum string, not a wrapper
      expect(result.result).toBe('WON')
      // sets must be per-set integer-pair objects with setNumber, not a string/single int
      expect(result.sets).toHaveLength(4)
      expect(result.sets[0]).toEqual({ setNumber: 1, playerScore: 11, opponentScore: 5 })
      expect(result.sets[2]).toEqual({ setNumber: 3, playerScore: 8, opponentScore: 11 })
    })

    it('propagates the ApiError body on a 503 — drift tripwire on the declared error shape', async () => {
      mockPost.mockRejectedValue({ response: { data: apiErrorFixture } })

      await expect(createMatch(createRequest)).rejects.toMatchObject({
        response: {
          data: {
            status: 503,
            message: 'AI service is temporarily unavailable',
            fieldErrors: null,
          },
        },
      })
    })
  })

  describe('listMatches', () => {
    it('decodes the backend array into MatchResponse[]', async () => {
      mockGet.mockResolvedValue({ data: matchListFixture } as any)

      const results = await listMatches()

      expect(results).toHaveLength(1)
      expect(results[0].id).toBe(matchFixture.id)
      expect(results[0].opponentName).toBe('Kowalski')
      expect(results[0].result).toBe('WON')
      expect(results[0].sets[0]).toEqual({ setNumber: 1, playerScore: 11, opponentScore: 5 })
    })
  })
})
