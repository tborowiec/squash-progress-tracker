import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import NavHeader from '../components/NavHeader'

const s: Record<string, React.CSSProperties> = {
  page: {
    minHeight: '100vh',
    display: 'flex',
    flexDirection: 'column',
  },
  main: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '3rem 2rem',
    gap: '3rem',
  },
  welcome: {
    textAlign: 'center' as const,
  },
  eyebrow: {
    fontFamily: 'var(--font-mono)',
    fontSize: '11px',
    letterSpacing: '0.15em',
    color: 'var(--teal)',
    textTransform: 'uppercase' as const,
    marginBottom: '0.5rem',
  },
  heading: {
    fontFamily: 'var(--font-display)',
    fontSize: '3rem',
    fontWeight: 700,
    color: 'var(--text)',
    letterSpacing: '-0.01em',
  },
  actions: {
    display: 'flex',
    gap: '1rem',
    flexWrap: 'wrap' as const,
    justifyContent: 'center',
  },
  primaryBtn: {
    background: 'var(--teal)',
    color: '#080d18',
    fontFamily: 'var(--font-display)',
    fontWeight: 700,
    fontSize: '1rem',
    letterSpacing: '0.05em',
    padding: '0.85rem 2rem',
    borderRadius: '2px',
    border: 'none',
    transition: 'background 0.15s',
  },
  secondaryBtn: {
    background: 'transparent',
    color: 'var(--text)',
    fontFamily: 'var(--font-display)',
    fontWeight: 600,
    fontSize: '1rem',
    letterSpacing: '0.05em',
    padding: '0.85rem 2rem',
    borderRadius: '2px',
    border: '1px solid var(--border)',
    transition: 'border-color 0.15s',
  },
}

export default function HomePage() {
  const { user } = useAuth()
  const navigate = useNavigate()

  return (
    <div style={s.page}>
      <NavHeader links={[{ label: 'History', to: '/history' }, { label: 'Log match', to: '/matches/new' }]} />

      <main style={s.main}>
        <div style={s.welcome}>
          <p style={s.eyebrow}>Dashboard</p>
          <h1 style={s.heading}>Welcome, {user?.email}</h1>
        </div>

        <div style={s.actions}>
          <button style={s.primaryBtn} onClick={() => navigate('/matches/new')}>
            Log match
          </button>
          <button style={s.secondaryBtn} onClick={() => navigate('/history')}>
            Match history
          </button>
        </div>
      </main>
    </div>
  )
}
