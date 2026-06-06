import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { getMatch, updateMatch } from '../api/matches'
import MatchForm, { type MatchFormInitial } from '../components/MatchForm'
import NavHeader from '../components/NavHeader'

const s: Record<string, React.CSSProperties> = {
  page: { minHeight: '100vh', display: 'flex', flexDirection: 'column' },
  main: { flex: 1, display: 'flex', justifyContent: 'center', padding: '2.5rem 1.5rem' },
  card: { width: '100%', maxWidth: '520px' },
  loading: {
    color: 'var(--muted)',
    fontFamily: 'var(--font-mono)',
    fontSize: '13px',
    textAlign: 'center' as const,
    padding: '3rem 0',
  },
  notFound: { textAlign: 'center' as const, padding: '4rem 0', color: 'var(--muted)' },
  notFoundMsg: {
    fontFamily: 'var(--font-display)',
    fontSize: '1.5rem',
    color: 'var(--muted)',
    marginBottom: '1rem',
  },
  backLink: { color: 'var(--teal)', fontFamily: 'var(--font-mono)', fontSize: '13px' },
}

export default function EditMatchPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [initial, setInitial] = useState<MatchFormInitial | null>(null)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)

  useEffect(() => {
    const matchId = Number(id)
    if (!Number.isInteger(matchId) || matchId <= 0) {
      setNotFound(true)
      setLoading(false)
      return
    }
    getMatch(matchId)
      .then(m =>
        setInitial({
          opponentName: m.opponentName,
          matchDate: m.matchDate,
          notes: m.notes ?? '',
          sets: m.sets.map(st => ({
            playerScore: String(st.playerScore),
            opponentScore: String(st.opponentScore),
          })),
        }),
      )
      .catch(() => setNotFound(true))
      .finally(() => setLoading(false))
  }, [id])

  return (
    <div style={s.page}>
      <NavHeader
        links={[
          { label: 'Dashboard', to: '/' },
          { label: 'History', to: '/history' },
          { label: 'Game plan', to: '/game-plan' },
        ]}
      />

      <main style={s.main}>
        <div style={s.card}>
          {loading ? (
            <p style={s.loading}>Loading…</p>
          ) : notFound ? (
            <div style={s.notFound}>
              <p style={s.notFoundMsg}>Match not found.</p>
              <Link to="/history" style={s.backLink}>
                ← Back to history
              </Link>
            </div>
          ) : (
            <MatchForm
              initial={initial!}
              submitLabel="Update match"
              onSubmit={async payload => {
                await updateMatch(Number(id), payload)
                navigate('/history')
              }}
            />
          )}
        </div>
      </main>
    </div>
  )
}
