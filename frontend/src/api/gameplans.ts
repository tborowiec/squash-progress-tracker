export interface GamePlanMeta {
  disclaimer: string
  matchCount: number
  lowData: boolean
}

interface Handlers {
  onMeta: (meta: GamePlanMeta) => void
  onToken: (text: string) => void
  onDone: () => void
  onError: () => void
}

export function streamGamePlan(opponentName: string, handlers: Handlers): () => void {
  const es = new EventSource(`/api/game-plans/stream?opponent=${encodeURIComponent(opponentName)}`)

  es.addEventListener('meta', e => {
    handlers.onMeta(JSON.parse(e.data))
  })

  es.addEventListener('token', e => {
    handlers.onToken(JSON.parse(e.data).t)
  })

  es.addEventListener('done', () => {
    es.close()
    handlers.onDone()
  })

  es.addEventListener('error', _e => {
    es.close()
    handlers.onError()
  })

  // Transport-level drop (no named event) — close to prevent auto-reconnect
  es.onerror = () => {
    es.close()
    handlers.onError()
  }

  return () => es.close()
}
