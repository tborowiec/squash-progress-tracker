import type { AxiosError } from 'axios'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { ApiError } from '../api/auth'
import { createMatch, parseMatch } from '../api/matches'
import MatchForm, { type MatchFormInitial, today } from '../components/MatchForm'
import NavHeader from '../components/NavHeader'

const s: Record<string, React.CSSProperties> = {
  page: { minHeight: '100vh', display: 'flex', flexDirection: 'column' },
  main: { flex: 1, display: 'flex', justifyContent: 'center', padding: '2.5rem 1.5rem' },
  card: { width: '100%', maxWidth: '520px' },
  section: { marginBottom: '2rem' },
  sectionHeading: {
    fontFamily: 'var(--font-display)',
    fontWeight: 700,
    fontSize: '1.4rem',
    letterSpacing: '0.02em',
    color: 'var(--text)',
    marginBottom: '1rem',
    paddingBottom: '0.5rem',
    borderBottom: '1px solid var(--border)',
  },
  banner: {
    background: 'rgba(248,113,113,0.08)',
    border: '1px solid rgba(248,113,113,0.3)',
    borderRadius: '2px',
    color: 'var(--error)',
    fontSize: '13px',
    padding: '0.6rem 0.85rem',
    marginBottom: '1.5rem',
  },
  toggleRow: {
    display: 'flex',
    marginBottom: '2rem',
    borderRadius: '2px',
    overflow: 'hidden',
    border: '1px solid var(--border)',
    width: 'fit-content' as const,
  },
  toggleBtn: {
    padding: '0.45rem 1.4rem',
    fontFamily: 'var(--font-mono)',
    fontSize: '11px',
    letterSpacing: '0.1em',
    textTransform: 'uppercase' as const,
    border: 'none',
    cursor: 'pointer',
    transition: 'background 0.15s, color 0.15s',
  },
  aiTextarea: {
    width: '100%',
    background: 'var(--surface)',
    border: '1px solid var(--border)',
    borderRadius: '2px',
    padding: '0.7rem 0.9rem',
    color: 'var(--text)',
    fontSize: '15px',
    outline: 'none',
    resize: 'vertical' as const,
    minHeight: '100px',
    fontFamily: 'var(--font-body)',
    lineHeight: 1.6,
  },
  parseBtn: {
    width: '100%',
    background: 'var(--teal)',
    color: '#080d18',
    fontFamily: 'var(--font-display)',
    fontWeight: 700,
    fontSize: '1.05rem',
    letterSpacing: '0.05em',
    padding: '0.8rem',
    borderRadius: '2px',
    transition: 'background 0.15s',
    marginTop: '0.75rem',
  },
  caveat: {
    borderLeft: '3px solid #f59e0b',
    background: 'rgba(245,158,11,0.06)',
    padding: '0.6rem 1rem',
    borderRadius: '2px',
    marginBottom: '1.5rem',
    fontFamily: 'var(--font-mono)',
    fontSize: '12px',
    letterSpacing: '0.06em',
    color: '#f59e0b',
  },
  disclaimer: {
    fontFamily: 'var(--font-mono)',
    fontSize: '11px',
    color: 'var(--muted)',
    letterSpacing: '0.07em',
    marginTop: '0.5rem',
    textTransform: 'uppercase' as const,
  },
}

export default function LogMatchPage() {
  const navigate = useNavigate()
  const [mode, setMode] = useState<'ai' | 'manual'>('ai')
  const [aiText, setAiText] = useState('')
  const [parsing, setParsing] = useState(false)
  const [parseWarning, setParseWarning] = useState(false)
  const [globalError, setGlobalError] = useState('')
  const [initial, setInitial] = useState<MatchFormInitial | undefined>(undefined)
  const [formKey, setFormKey] = useState(0)

  const switchMode = (next: 'ai' | 'manual') => {
    setMode(next)
    if (next === 'ai') setParseWarning(false)
  }

  async function handleParse() {
    setParsing(true)
    setGlobalError('')
    setParseWarning(false)
    try {
      const res = await parseMatch(aiText)
      setInitial({
        opponentName: res.opponentName,
        matchDate: res.matchDate || today(),
        notes: res.notes,
        sets:
          res.sets.length > 0
            ? res.sets.slice(0, 5).map(set => ({
                playerScore: String(set.playerScore),
                opponentScore: String(set.opponentScore),
              }))
            : [{ playerScore: '', opponentScore: '' }],
      })
      setFormKey(k => k + 1)
      setParseWarning(res.opponentName === '' || res.sets.length === 0)
      setMode('manual')
    } catch (err) {
      const ae = (err as AxiosError<ApiError>).response?.data
      setGlobalError(ae?.message ?? 'Could not parse match. Please try again.')
    } finally {
      setParsing(false)
    }
  }

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
          {globalError && <div style={s.banner}>{globalError}</div>}
          {parseWarning && mode === 'manual' && (
            <div style={s.caveat}>
              Some fields could not be parsed — review opponent and sets before saving.
            </div>
          )}

          <div style={s.toggleRow}>
            <button
              type="button"
              style={{
                ...s.toggleBtn,
                background: mode === 'ai' ? 'var(--teal)' : 'var(--surface)',
                color: mode === 'ai' ? '#080d18' : 'var(--muted)',
              }}
              onClick={() => switchMode('ai')}
            >
              AI
            </button>
            <button
              type="button"
              style={{
                ...s.toggleBtn,
                background: mode === 'manual' ? 'var(--teal)' : 'var(--surface)',
                color: mode === 'manual' ? '#080d18' : 'var(--muted)',
              }}
              onClick={() => switchMode('manual')}
            >
              Manual
            </button>
          </div>

          {mode === 'ai' && (
            <div style={s.section}>
              <h2 style={s.sectionHeading}>Describe your match</h2>
              <textarea
                style={s.aiTextarea}
                placeholder="e.g. beat Kowalski 3:1 (11:5, 6:11, 11:2, 11:1) on May 5th, struggled in the second set"
                value={aiText}
                onChange={e => setAiText(e.target.value)}
              />
              <p style={s.disclaimer}>AI-parsed — review all fields before saving</p>
              <button
                type="button"
                style={{ ...s.parseBtn, opacity: parsing || !aiText.trim() ? 0.7 : 1 }}
                onClick={handleParse}
                disabled={parsing || !aiText.trim()}
              >
                {parsing ? 'Parsing…' : 'Parse with AI'}
              </button>
            </div>
          )}

          {mode === 'manual' && (
            <MatchForm
              key={formKey}
              initial={initial}
              submitLabel="Save match"
              onSubmit={async payload => {
                await createMatch(payload)
                navigate('/history')
              }}
            />
          )}
        </div>
      </main>
    </div>
  )
}
