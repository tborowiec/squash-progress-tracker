import client from './client'

export interface SetScoreRequest {
  playerScore: number
  opponentScore: number
}

export interface MatchParseResult {
  opponentName: string
  matchDate: string
  notes: string
  sets: { playerScore: number; opponentScore: number }[]
}

export interface CreateMatchRequest {
  opponentName: string
  matchDate: string
  notes?: string
  sets: SetScoreRequest[]
}

export interface SetScoreResponse {
  setNumber: number
  playerScore: number
  opponentScore: number
}

export interface MatchResponse {
  id: number
  opponentName: string
  matchDate: string
  notes?: string
  sets: SetScoreResponse[]
  setsWon: number
  setsLost: number
  result: 'WON' | 'LOST' | 'DRAW'
}

export const createMatch = (data: CreateMatchRequest) =>
  client.post<MatchResponse>('/api/matches', data).then(r => r.data)

export const listMatches = (opponent?: string) =>
  client.get<MatchResponse[]>('/api/matches', { params: opponent ? { opponent } : {} }).then(r => r.data)

export const listOpponents = () =>
  client.get<string[]>('/api/matches/opponents').then(r => r.data)

export const parseMatch = (text: string): Promise<MatchParseResult> =>
  client.post<MatchParseResult>('/api/matches/parse', { text }).then(r => r.data)
