import { useTranslation } from 'react-i18next'
import { updateLocale } from '../api/auth'
import { useAuth } from '../contexts/AuthContext'

const LANGS = ['en', 'pl'] as const

export default function LanguageSwitcher() {
  const { i18n } = useTranslation()
  const { user } = useAuth()

  const current = i18n.language.startsWith('pl') ? 'pl' : 'en'

  async function switchLang(tag: string) {
    await i18n.changeLanguage(tag)
    if (user) {
      try {
        await updateLocale(tag)
      } catch {
        // local change already applied; silently ignore persist failure
      }
    }
  }

  return (
    <div
      style={{
        display: 'flex',
        border: '1px solid var(--border)',
        borderRadius: '2px',
        overflow: 'hidden',
      }}
    >
      {LANGS.map(tag => (
        <button
          key={tag}
          type="button"
          onClick={() => switchLang(tag)}
          style={{
            padding: '0.3rem 0.65rem',
            background: current === tag ? 'var(--teal)' : 'transparent',
            color: current === tag ? '#080d18' : 'var(--muted)',
            border: 'none',
            fontFamily: 'var(--font-mono)',
            fontSize: '11px',
            letterSpacing: '0.1em',
            textTransform: 'uppercase' as const,
            cursor: 'pointer',
          }}
        >
          {tag}
        </button>
      ))}
    </div>
  )
}
