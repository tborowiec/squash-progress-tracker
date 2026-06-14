import type { AxiosError } from 'axios'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { type ApiError, register } from '../api/auth'
import LanguageSwitcher from '../components/LanguageSwitcher'

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
  cardHeader: {
    display: 'flex',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    marginBottom: '0.5rem',
  },
  eyebrow: {
    fontFamily: 'var(--font-mono)',
    fontSize: '11px',
    letterSpacing: '0.15em',
    color: 'var(--teal)',
    textTransform: 'uppercase' as const,
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
  },
  fieldError: {
    color: 'var(--error)',
    fontSize: '12px',
    fontFamily: 'var(--font-mono)',
    marginTop: '0.3rem',
  },
  globalError: {
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

export default function RegisterPage() {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [globalError, setGlobalError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setGlobalError('')
    setFieldErrors({})
    try {
      await register(email, password)
      navigate('/login')
    } catch (err) {
      const ae = (err as AxiosError<ApiError>).response?.data
      if (ae?.fieldErrors) {
        setFieldErrors(ae.fieldErrors)
      } else {
        setGlobalError(ae?.message ?? t('auth.registrationFailed'))
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={s.page}>
      <div style={s.card}>
        <div style={s.cardHeader}>
          <p style={s.eyebrow}>{t('auth.eyebrow')}</p>
          <LanguageSwitcher />
        </div>
        <h1 style={s.heading}>{t('auth.createAccount')}</h1>

        {globalError && <div style={s.globalError}>{globalError}</div>}

        <form onSubmit={handleSubmit}>
          <div style={s.field}>
            <label style={s.label} htmlFor="email">
              {t('auth.email')}
            </label>
            <input
              id="email"
              style={{
                ...s.input,
                borderColor: fieldErrors.email ? 'var(--error)' : undefined,
              }}
              type="email"
              autoComplete="email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              required
            />
            {fieldErrors.email && <p style={s.fieldError}>{fieldErrors.email}</p>}
          </div>
          <div style={s.field}>
            <label style={s.label} htmlFor="password">
              {t('auth.password')}
            </label>
            <input
              id="password"
              style={{
                ...s.input,
                borderColor: fieldErrors.password ? 'var(--error)' : undefined,
              }}
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
            />
            {fieldErrors.password && <p style={s.fieldError}>{fieldErrors.password}</p>}
          </div>
          <button style={{ ...s.btn, opacity: busy ? 0.7 : 1 }} type="submit" disabled={busy}>
            {busy ? t('auth.creatingAccount') : t('auth.createAccount')}
          </button>
        </form>

        <p style={s.footer}>
          {t('auth.alreadyHaveAccount')} <Link to="/login">{t('auth.signInLink')}</Link>
        </p>
      </div>
    </div>
  )
}
