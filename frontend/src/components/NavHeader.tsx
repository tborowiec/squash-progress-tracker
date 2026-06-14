import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { logout } from '../api/auth'
import { useAuth } from '../contexts/AuthContext'
import LanguageSwitcher from './LanguageSwitcher'

interface NavLink {
  label: string
  to: string
}

interface Props {
  links?: NavLink[]
}

const s: Record<string, React.CSSProperties> = {
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
  right: { display: 'flex', alignItems: 'center', gap: '1.25rem' },
  navLink: {
    fontFamily: 'var(--font-mono)',
    fontSize: '11px',
    letterSpacing: '0.1em',
    color: 'var(--muted)',
    textTransform: 'uppercase' as const,
  },
  signOut: {
    background: 'transparent',
    border: '1px solid var(--border)',
    borderRadius: '2px',
    color: 'var(--muted)',
    fontFamily: 'var(--font-mono)',
    fontSize: '11px',
    letterSpacing: '0.1em',
    padding: '0.4rem 0.85rem',
    textTransform: 'uppercase' as const,
    cursor: 'pointer',
  },
}

export default function NavHeader({ links = [] }: Props) {
  const navigate = useNavigate()
  const { setUser } = useAuth()
  const { t } = useTranslation()

  async function handleSignOut() {
    await logout()
    setUser(null)
    navigate('/login')
  }

  return (
    <header style={s.header}>
      <Link to="/" style={{ textDecoration: 'none' }}>
        <span style={s.logo}>Squash Progress Tracker</span>
      </Link>
      <div style={s.right}>
        {links.map(l => (
          <Link key={l.to} to={l.to} style={s.navLink}>
            {l.label}
          </Link>
        ))}
        <LanguageSwitcher />
        <button type="button" style={s.signOut} onClick={handleSignOut}>
          {t('nav.signOut')}
        </button>
      </div>
    </header>
  )
}
