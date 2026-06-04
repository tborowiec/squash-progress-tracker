import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import NavHeader from '../components/NavHeader'
import { listOpponents } from '../api/matches'
import { streamGamePlan, GamePlanMeta } from '../api/gameplans'

const CURSOR_STYLE = `
@keyframes blink {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0; }
}
.__cursor { animation: blink 0.9s step-start infinite; color: var(--teal); }
`

const s: Record<string, React.CSSProperties> = {
  page:       { minHeight: '100vh', display: 'flex', flexDirection: 'column' },
  main:       { flex: 1, display: 'flex', justifyContent: 'center', padding: '2.5rem 1.5rem' },
  inner:      { width: '100%', maxWidth: '680px' },
  toolbar:    { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1.5rem', gap: '1rem', flexWrap: 'wrap' as const },
  select:     { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '2px', color: 'var(--text)', fontFamily: 'var(--font-body)', fontSize: '14px', padding: '0.5rem 0.75rem', outline: 'none', minWidth: '200px' },
  genBtn:     { background: 'var(--teal)', color: '#080d18', fontFamily: 'var(--font-display)', fontWeight: 700, fontSize: '0.95rem', letterSpacing: '0.05em', padding: '0.55rem 1.25rem', borderRadius: '2px', border: 'none', cursor: 'pointer' },
  genBtnOff:  { background: 'var(--teal)', color: '#080d18', fontFamily: 'var(--font-display)', fontWeight: 700, fontSize: '0.95rem', letterSpacing: '0.05em', padding: '0.55rem 1.25rem', borderRadius: '2px', border: 'none', cursor: 'not-allowed', opacity: 0.5 },
  banner:     { borderLeft: '3px solid var(--teal)', background: 'var(--surface)', padding: '0.6rem 1rem', borderRadius: '2px', marginBottom: '0.75rem', fontFamily: 'var(--font-mono)', fontSize: '12px', letterSpacing: '0.08em', textTransform: 'uppercase' as const, color: 'var(--teal)' },
  caveat:     { borderLeft: '3px solid #f59e0b', background: 'rgba(245,158,11,0.06)', padding: '0.6rem 1rem', borderRadius: '2px', marginBottom: '1.5rem', fontFamily: 'var(--font-mono)', fontSize: '12px', letterSpacing: '0.08em', textTransform: 'uppercase' as const, color: '#f59e0b' },
  bannerWrap: { marginBottom: '1.5rem' },
  planCard:   { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '2px', padding: '1.5rem' },
  planProse:  { fontFamily: 'var(--font-body)', fontSize: '15px', color: 'var(--text)', lineHeight: 1.8 },
  placeholder:{ textAlign: 'center' as const, padding: '4rem 0', color: 'var(--muted)', fontFamily: 'var(--font-mono)', fontSize: '13px' },
  errorCard:  { borderLeft: '3px solid var(--error)', background: 'var(--surface)', padding: '1rem', borderRadius: '2px', fontFamily: 'var(--font-body)', fontSize: '14px', color: 'var(--error)' },
}

export default function GamePlanPage() {
  const [searchParams] = useSearchParams()
  const [opponents, setOpponents] = useState<string[]>([])
  const [opponent, setOpponent] = useState(searchParams.get('opponent') ?? '')
  const [status, setStatus] = useState<'idle' | 'streaming' | 'done' | 'error'>('idle')
  const [plan, setPlan] = useState('')
  const [meta, setMeta] = useState<GamePlanMeta | null>(null)
  const closeStreamRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    listOpponents().then(setOpponents).catch(() => setOpponents([]))
    return () => { closeStreamRef.current?.() }
  }, [])

  function handleGenerate() {
    closeStreamRef.current?.()
    setPlan('')
    setMeta(null)
    setStatus('streaming')
    closeStreamRef.current = streamGamePlan(opponent, {
      onMeta:  m => setMeta(m),
      onToken: t => setPlan(prev => prev + t),
      onDone:  () => setStatus('done'),
      onError: () => setStatus('error'),
    })
  }

  const isDisabled = opponent === '' || status === 'streaming'
  const showBanner = meta !== null || plan !== ''

  return (
    <div style={s.page}>
      <style>{CURSOR_STYLE}</style>
      <NavHeader links={[{ label: 'Dashboard', to: '/' }, { label: 'Log match', to: '/matches/new' }, { label: 'History', to: '/history' }]} />

      <main style={s.main}>
        <div style={s.inner}>

          <div style={s.toolbar}>
            <select
              style={s.select}
              value={opponent}
              onChange={e => setOpponent(e.target.value)}
            >
              <option value="">Select opponent…</option>
              {opponents.map(o => <option key={o} value={o}>{o}</option>)}
            </select>
            <button
              style={isDisabled ? s.genBtnOff : s.genBtn}
              disabled={isDisabled}
              onClick={handleGenerate}
            >
              {status === 'streaming' ? 'Generating…' : 'Generate game plan'}
            </button>
          </div>

          {showBanner && (
            <div style={s.bannerWrap}>
              <div style={s.banner}>
                {meta?.disclaimer ?? 'AI-generated advice — not factual analysis. Verify before relying on it.'}
              </div>
              {meta?.lowData && (
                <div style={s.caveat}>
                  Based on limited history ({meta.matchCount} match{meta.matchCount === 1 ? '' : 'es'}) — treat with extra caution.
                </div>
              )}
            </div>
          )}

          {status === 'error' && (
            <div style={s.errorCard}>
              AI service is temporarily unavailable. Please try again.
            </div>
          )}

          {plan !== '' && (
            <div style={s.planCard}>
              <div style={s.planProse}>
                <ReactMarkdown components={{
                  p:      ({children}) => <p style={{margin: '0 0 0.75rem', lineHeight: 1.8}}>{children}</p>,
                  strong: ({children}) => <strong style={{color: 'var(--text)', fontWeight: 700}}>{children}</strong>,
                  em:     ({children}) => <em style={{color: 'var(--muted)'}}>{children}</em>,
                  ul:     ({children}) => <ul style={{margin: '0 0 0.75rem', paddingLeft: '1.5rem'}}>{children}</ul>,
                  ol:     ({children}) => <ol style={{margin: '0 0 0.75rem', paddingLeft: '1.5rem'}}>{children}</ol>,
                  li:     ({children}) => <li style={{marginBottom: '0.25rem', lineHeight: 1.7}}>{children}</li>,
                  h1:     ({children}) => <h1 style={{fontFamily: 'var(--font-display)', fontSize: '1.5rem', fontWeight: 700, color: 'var(--text)', margin: '0 0 0.75rem', letterSpacing: '0.02em'}}>{children}</h1>,
                  h2:     ({children}) => <h2 style={{fontFamily: 'var(--font-display)', fontSize: '1.25rem', fontWeight: 700, color: 'var(--text)', margin: '0 0 0.75rem'}}>{children}</h2>,
                  h3:     ({children}) => <h3 style={{fontFamily: 'var(--font-display)', fontSize: '1.1rem', fontWeight: 700, color: 'var(--teal)', margin: '0 0 0.5rem', letterSpacing: '0.03em'}}>{children}</h3>,
                  hr:     () => <hr style={{border: 'none', borderTop: '1px solid var(--border)', margin: '1rem 0'}} />,
                }}>
                  {plan}
                </ReactMarkdown>
                {status === 'streaming' && <span className="__cursor">▊</span>}
              </div>
            </div>
          )}

          {status !== 'error' && plan === '' && status !== 'streaming' && (
            <p style={s.placeholder}>Select an opponent and generate a game plan.</p>
          )}

          {status === 'streaming' && plan === '' && (
            <p style={{ ...s.placeholder, paddingTop: '2rem' }}>
              Generating<span className="__cursor">▊</span>
            </p>
          )}

        </div>
      </main>
    </div>
  )
}
