import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { listMatches, listOpponents, MatchResponse } from '../api/matches'
import NavHeader from '../components/NavHeader'

const s: Record<string, React.CSSProperties> = {
  page:         { minHeight: '100vh', display: 'flex', flexDirection: 'column' },
  main:         { flex: 1, display: 'flex', justifyContent: 'center', padding: '2.5rem 1.5rem' },
  inner:        { width: '100%', maxWidth: '680px' },
  toolbar:      { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1.5rem', gap: '1rem', flexWrap: 'wrap' as const },
  select:       { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '2px', color: 'var(--text)', fontFamily: 'var(--font-body)', fontSize: '14px', padding: '0.5rem 0.75rem', outline: 'none', minWidth: '200px' },
  logBtn:       { background: 'var(--teal)', color: '#080d18', fontFamily: 'var(--font-display)', fontWeight: 700, fontSize: '0.95rem', letterSpacing: '0.05em', padding: '0.55rem 1.25rem', borderRadius: '2px', border: 'none', cursor: 'pointer' },
  gpBtn:        { background: 'transparent', color: 'var(--teal)', fontFamily: 'var(--font-display)', fontWeight: 700, fontSize: '0.95rem', letterSpacing: '0.05em', padding: '0.55rem 1.25rem', borderRadius: '2px', border: '1px solid var(--teal)', cursor: 'pointer' },
  gpBtnOff:     { background: 'transparent', color: 'var(--teal)', fontFamily: 'var(--font-display)', fontWeight: 700, fontSize: '0.95rem', letterSpacing: '0.05em', padding: '0.55rem 1.25rem', borderRadius: '2px', border: '1px solid var(--teal)', cursor: 'not-allowed', opacity: 0.5 },
  toolbarRight: { display: 'flex', alignItems: 'center', gap: '0.75rem' },
  loading:      { color: 'var(--muted)', fontFamily: 'var(--font-mono)', fontSize: '13px', textAlign: 'center' as const, padding: '3rem 0' },
  empty:        { textAlign: 'center' as const, padding: '4rem 0', color: 'var(--muted)' },
  emptyMsg:     { fontFamily: 'var(--font-display)', fontSize: '1.5rem', color: 'var(--muted)', marginBottom: '1rem' },
  card:         { background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '2px', padding: '1.1rem 1.25rem', marginBottom: '0.75rem', transition: 'border-color 0.15s' },
  cardTop:      { display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: '1rem', marginBottom: '0.5rem' },
  opponent:     { fontFamily: 'var(--font-display)', fontWeight: 700, fontSize: '1.35rem', color: 'var(--text)', letterSpacing: '0.01em' },
  dateBadge:    { fontFamily: 'var(--font-mono)', fontSize: '12px', color: 'var(--muted)' },
  badge:        { fontFamily: 'var(--font-display)', fontWeight: 700, fontSize: '0.85rem', letterSpacing: '0.1em', padding: '0.2rem 0.55rem', borderRadius: '2px' },
  sets:         { fontFamily: 'var(--font-mono)', fontSize: '13px', color: 'var(--muted)', marginBottom: '0.4rem', letterSpacing: '0.02em' },
  cardNotes:    { fontFamily: 'var(--font-body)', fontSize: '13px', color: 'var(--muted)', fontStyle: 'italic' as const },
}

function resultStyle(result: string): React.CSSProperties {
  if (result === 'WON')  return { ...s.badge, background: 'rgba(0,201,167,0.12)', color: 'var(--teal)' }
  if (result === 'LOST') return { ...s.badge, background: 'rgba(248,113,113,0.1)', color: 'var(--error)' }
  return { ...s.badge, background: 'rgba(122,134,160,0.1)', color: 'var(--muted)' }
}

function formatSets(match: MatchResponse): string {
  return match.sets.map(s => `${s.playerScore}–${s.opponentScore}`).join('  ')
}

export default function HistoryPage() {
  const navigate = useNavigate()
  const [matches, setMatches]     = useState<MatchResponse[]>([])
  const [opponents, setOpponents] = useState<string[]>([])
  const [filter, setFilter]       = useState('')
  const [loading, setLoading]     = useState(true)

  useEffect(() => {
    Promise.all([listMatches(), listOpponents()])
      .then(([m, o]) => { setMatches(m); setOpponents(o) })
      .finally(() => setLoading(false))
  }, [])

  async function handleFilterChange(val: string) {
    setFilter(val)
    setLoading(true)
    const m = await listMatches(val || undefined)
    setMatches(m)
    setLoading(false)
  }

  return (
    <div style={s.page}>
      <NavHeader links={[{ label: 'Dashboard', to: '/' }, { label: 'Log match', to: '/matches/new' }, { label: 'Game plan', to: '/game-plan' }]} />

      <main style={s.main}>
        <div style={s.inner}>
          <div style={s.toolbar}>
            <select
              style={s.select}
              value={filter}
              onChange={e => handleFilterChange(e.target.value)}
            >
              <option value="">All opponents</option>
              {opponents.map(o => <option key={o} value={o}>{o}</option>)}
            </select>

            <div style={s.toolbarRight}>
              <button
                style={filter ? s.gpBtn : s.gpBtnOff}
                disabled={!filter}
                onClick={() => navigate(`/game-plan?opponent=${encodeURIComponent(filter)}`)}
              >
                Game plan →
              </button>
              <button style={s.logBtn} onClick={() => navigate('/matches/new')}>
                Log match
              </button>
            </div>
          </div>

          {loading ? (
            <p style={s.loading}>Loading…</p>
          ) : matches.length === 0 ? (
            <div style={s.empty}>
              <p style={s.emptyMsg}>No matches yet.</p>
              <Link to="/matches/new" style={{ color: 'var(--teal)', fontFamily: 'var(--font-mono)', fontSize: '13px' }}>
                Log your first match →
              </Link>
            </div>
          ) : (
            matches.map(match => (
              <div key={match.id} style={s.card}>
                <div style={s.cardTop}>
                  <div style={{ display: 'flex', alignItems: 'baseline', gap: '0.75rem' }}>
                    <span style={s.opponent}>{match.opponentName}</span>
                    <span style={s.dateBadge}>{match.matchDate}</span>
                  </div>
                  <span style={resultStyle(match.result)}>
                    {match.setsWon}–{match.setsLost} {match.result}
                  </span>
                </div>
                <p style={s.sets}>{formatSets(match)}</p>
                {match.notes && <p style={s.cardNotes}>{match.notes}</p>}
              </div>
            ))
          )}
        </div>
      </main>
    </div>
  )
}
