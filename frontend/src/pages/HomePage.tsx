import { useNavigate } from 'react-router-dom'
import { logout } from '../api/auth'
import { useAuth } from '../contexts/AuthContext'

const s: Record<string, React.CSSProperties> = {
  page: {
    minHeight: '100vh',
    display: 'flex',
    flexDirection: 'column',
  },
  header: {
    borderBottom: '1px solid var(--border)',
    padding: '1rem 2rem',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    background: 'var(--surface)',
  },
  logo: {
    fontFamily: 'var(--font-display)',
    fontWeight: 700,
    fontSize: '1.25rem',
    letterSpacing: '0.05em',
    color: 'var(--teal)',
  },
  logoutBtn: {
    background: 'transparent',
    border: '1px solid var(--border)',
    borderRadius: '2px',
    color: 'var(--muted)',
    fontFamily: 'var(--font-mono)',
    fontSize: '11px',
    letterSpacing: '0.1em',
    padding: '0.4rem 0.85rem',
    textTransform: 'uppercase' as const,
    transition: 'border-color 0.15s, color 0.15s',
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
  const { user, setUser } = useAuth()
  const navigate = useNavigate()

  async function handleLogout() {
    await logout()
    setUser(null)
    navigate('/login')
  }

  return (
    <div style={s.page}>
      <header style={s.header}>
        <span style={s.logo}>Squash Progress Tracker</span>
        <button style={s.logoutBtn} onClick={handleLogout}>Sign out</button>
      </header>

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
