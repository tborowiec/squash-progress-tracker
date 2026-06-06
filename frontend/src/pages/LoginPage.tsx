import type { AxiosError } from 'axios'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { type ApiError, login } from '../api/auth'
import { useAuth } from '../contexts/AuthContext'

const s: Record<string, React.CSSProperties> = {
  page: {
    minHeight: '100vh',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '2rem',
  },
  card: {
    width: '100%',
    maxWidth: '400px',
    background: 'var(--surface)',
    border: '1px solid var(--border)',
    borderRadius: '2px',
    padding: '2.5rem',
    boxShadow: '0 0 60px rgba(0,201,167,0.04)',
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
    fontSize: '2.4rem',
    fontWeight: 700,
    letterSpacing: '-0.01em',
    lineHeight: 1,
    color: 'var(--text)',
    marginBottom: '2rem',
  },
  field: { marginBottom: '1.25rem' },
  label: {
    display: 'block',
    fontSize: '12px',
    fontFamily: 'var(--font-mono)',
    letterSpacing: '0.1em',
    color: 'var(--muted)',
    textTransform: 'uppercase' as const,
    marginBottom: '0.4rem',
  },
  input: {
    width: '100%',
    background: 'var(--bg)',
    border: '1px solid var(--border)',
    borderRadius: '2px',
    padding: '0.65rem 0.85rem',
    color: 'var(--text)',
    fontSize: '15px',
    outline: 'none',
    transition: 'border-color 0.15s',
  },
  error: {
    background: 'rgba(248,113,113,0.08)',
    border: '1px solid rgba(248,113,113,0.3)',
    borderRadius: '2px',
    color: 'var(--error)',
    fontSize: '13px',
    padding: '0.6rem 0.85rem',
    marginBottom: '1.25rem',
  },
  btn: {
    width: '100%',
    background: 'var(--teal)',
    color: '#080d18',
    fontFamily: 'var(--font-display)',
    fontWeight: 700,
    fontSize: '1rem',
    letterSpacing: '0.05em',
    padding: '0.75rem',
    borderRadius: '2px',
    marginTop: '0.5rem',
    transition: 'background 0.15s',
  },
  footer: {
    marginTop: '1.5rem',
    textAlign: 'center' as const,
    fontSize: '13px',
    color: 'var(--muted)',
  },
}

export default function LoginPage() {
  const navigate = useNavigate()
  const { setUser } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      const user = await login(email, password)
      setUser(user)
      navigate('/')
    } catch (err) {
      const ae = (err as AxiosError<ApiError>).response?.data
      setError(ae?.message ?? 'Login failed. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={s.page}>
      <div style={s.card}>
        <p style={s.eyebrow}>Squash Progress Tracker</p>
        <h1 style={s.heading}>Sign in</h1>

        {error && <div style={s.error}>{error}</div>}

        <form onSubmit={handleSubmit}>
          <div style={s.field}>
            <label style={s.label} htmlFor="email">
              Email
            </label>
            <input
              id="email"
              style={s.input}
              type="email"
              autoComplete="email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              required
            />
          </div>
          <div style={s.field}>
            <label style={s.label} htmlFor="password">
              Password
            </label>
            <input
              id="password"
              style={s.input}
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
            />
          </div>
          <button style={{ ...s.btn, opacity: busy ? 0.7 : 1 }} type="submit" disabled={busy}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <p style={s.footer}>
          No account? <Link to="/register">Register</Link>
        </p>
      </div>
    </div>
  )
}
