import type { AxiosError } from 'axios'
import { useRef, useState } from 'react'
import type { ApiError } from '../api/auth'
import type { CreateOrUpdateMatchRequest, SetScoreRequest } from '../api/matches'

export const today = () => new Date().toISOString().split('T')[0]

export interface SetRow {
  playerScore: string
  opponentScore: string
}

// Internal row shape: a stable client-side id so React keys survive add/remove of
// rows. The positional index is still used for labels ("Set 1") and for matching
// server-side field errors (keyed by position, e.g. "sets[0].playerScore").
interface SetRowState extends SetRow {
  id: string
}

export interface MatchFormInitial {
  opponentName: string
  matchDate: string
  notes: string
  sets: SetRow[]
}

interface Props {
  initial?: MatchFormInitial
  onSubmit: (payload: CreateOrUpdateMatchRequest) => Promise<void>
  submitLabel: string
}

const s: Record<string, React.CSSProperties> = {
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
  field: { marginBottom: '1.1rem' },
  label: {
    display: 'block',
    fontSize: '11px',
    fontFamily: 'var(--font-mono)',
    letterSpacing: '0.12em',
    color: 'var(--muted)',
    textTransform: 'uppercase' as const,
    marginBottom: '0.35rem',
  },
  input: {
    width: '100%',
    background: 'var(--surface)',
    border: '1px solid var(--border)',
    borderRadius: '2px',
    padding: '0.6rem 0.8rem',
    color: 'var(--text)',
    fontSize: '15px',
    outline: 'none',
  },
  textarea: {
    width: '100%',
    background: 'var(--surface)',
    border: '1px solid var(--border)',
    borderRadius: '2px',
    padding: '0.6rem 0.8rem',
    color: 'var(--text)',
    fontSize: '15px',
    outline: 'none',
    resize: 'vertical' as const,
    minHeight: '72px',
    fontFamily: 'var(--font-body)',
  },
  fieldError: {
    color: 'var(--error)',
    fontSize: '11px',
    fontFamily: 'var(--font-mono)',
    marginTop: '0.25rem',
  },
  setRow: {
    display: 'grid',
    gridTemplateColumns: '48px 1fr 24px 1fr auto',
    alignItems: 'center',
    gap: '0.5rem',
    marginBottom: '0.6rem',
  },
  setLabel: {
    fontFamily: 'var(--font-mono)',
    fontSize: '11px',
    color: 'var(--muted)',
    letterSpacing: '0.08em',
    textTransform: 'uppercase' as const,
  },
  scoreInput: {
    background: 'var(--surface)',
    border: '1px solid var(--border)',
    borderRadius: '2px',
    padding: '0.55rem 0.5rem',
    color: 'var(--text)',
    fontSize: '16px',
    fontFamily: 'var(--font-mono)',
    textAlign: 'center' as const,
    outline: 'none',
    width: '100%',
  },
  vs: {
    fontFamily: 'var(--font-mono)',
    fontSize: '11px',
    color: 'var(--muted)',
    textAlign: 'center' as const,
  },
  removeBtn: {
    background: 'transparent',
    border: 'none',
    color: 'var(--muted)',
    fontFamily: 'var(--font-mono)',
    fontSize: '14px',
    padding: '0.25rem 0.4rem',
    borderRadius: '2px',
    lineHeight: 1,
  },
  addBtn: {
    background: 'transparent',
    border: '1px dashed var(--border)',
    borderRadius: '2px',
    color: 'var(--muted)',
    fontFamily: 'var(--font-mono)',
    fontSize: '11px',
    letterSpacing: '0.1em',
    padding: '0.5rem 1rem',
    marginTop: '0.25rem',
    textTransform: 'uppercase' as const,
    transition: 'border-color 0.15s, color 0.15s',
    width: '100%',
  },
  liveScore: {
    display: 'flex',
    alignItems: 'baseline',
    gap: '0.75rem',
    marginTop: '1rem',
    padding: '0.75rem 1rem',
    background: 'rgba(0,201,167,0.04)',
    border: '1px solid rgba(0,201,167,0.12)',
    borderRadius: '2px',
  },
  liveScoreNum: {
    fontFamily: 'var(--font-mono)',
    fontSize: '1.75rem',
    fontWeight: 500,
    color: 'var(--text)',
    letterSpacing: '-0.02em',
  },
  liveScoreLabel: {
    fontFamily: 'var(--font-display)',
    fontSize: '1rem',
    fontWeight: 700,
    letterSpacing: '0.08em',
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
  submitBtn: {
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
    marginTop: '0.5rem',
  },
}

export default function MatchForm({ initial, onSubmit, submitLabel }: Props) {
  const nextSetId = useRef(0)
  const makeSet = (row: SetRow): SetRowState => ({ ...row, id: String(nextSetId.current++) })

  const [opponentName, setOpponentName] = useState(initial?.opponentName ?? '')
  const [matchDate, setMatchDate] = useState(initial?.matchDate ?? today())
  const [notes, setNotes] = useState(initial?.notes ?? '')
  const [sets, setSets] = useState<SetRowState[]>(() =>
    (initial?.sets ?? [{ playerScore: '', opponentScore: '' }]).map(makeSet),
  )
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [globalError, setGlobalError] = useState('')
  const [busy, setBusy] = useState(false)

  const addSet = () => {
    if (sets.length < 5) setSets(prev => [...prev, makeSet({ playerScore: '', opponentScore: '' })])
  }
  const removeSet = (i: number) => {
    if (sets.length > 1) setSets(prev => prev.filter((_, idx) => idx !== i))
  }
  const updateSet = (i: number, field: keyof SetRow, val: string) => {
    setSets(prev => prev.map((s, idx) => (idx === i ? { ...s, [field]: val } : s)))
  }

  const setsWon = sets.filter(
    s =>
      s.playerScore !== '' &&
      s.opponentScore !== '' &&
      Number(s.playerScore) > Number(s.opponentScore),
  ).length
  const setsLost = sets.filter(
    s =>
      s.playerScore !== '' &&
      s.opponentScore !== '' &&
      Number(s.playerScore) < Number(s.opponentScore),
  ).length
  const hasScores = sets.some(s => s.playerScore !== '' || s.opponentScore !== '')
  const result = setsWon > setsLost ? 'WON' : setsLost > setsWon ? 'LOST' : 'DRAW'
  const resultColor =
    result === 'WON' ? 'var(--teal)' : result === 'LOST' ? 'var(--error)' : 'var(--muted)'

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setFieldErrors({})
    setGlobalError('')
    try {
      const setsPayload: SetScoreRequest[] = sets.map(s => ({
        playerScore: Number(s.playerScore),
        opponentScore: Number(s.opponentScore),
      }))
      await onSubmit({ opponentName, matchDate, notes: notes || undefined, sets: setsPayload })
    } catch (err) {
      const ae = (err as AxiosError<ApiError>).response?.data
      if (ae?.fieldErrors) {
        setFieldErrors(ae.fieldErrors)
      } else {
        setGlobalError(ae?.message ?? 'Could not save match. Please try again.')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      {globalError && <div style={s.banner}>{globalError}</div>}

      <form onSubmit={handleSubmit}>
        {/* Match details */}
        <div style={s.section}>
          <h2 style={s.sectionHeading}>Match details</h2>

          <div style={s.field}>
            <label style={s.label} htmlFor="opponent">
              Opponent
            </label>
            <input
              id="opponent"
              style={{
                ...s.input,
                borderColor: fieldErrors.opponentName ? 'var(--error)' : undefined,
              }}
              type="text"
              maxLength={255}
              value={opponentName}
              onChange={e => setOpponentName(e.target.value)}
              required
            />
            {fieldErrors.opponentName && <p style={s.fieldError}>{fieldErrors.opponentName}</p>}
          </div>

          <div style={s.field}>
            <label style={s.label} htmlFor="date">
              Date
            </label>
            <input
              id="date"
              style={{
                ...s.input,
                borderColor: fieldErrors.matchDate ? 'var(--error)' : undefined,
              }}
              type="date"
              value={matchDate}
              max={today()}
              onChange={e => setMatchDate(e.target.value)}
              required
            />
            {fieldErrors.matchDate && <p style={s.fieldError}>{fieldErrors.matchDate}</p>}
          </div>

          <div style={s.field}>
            <label style={s.label} htmlFor="notes">
              Notes
            </label>
            <textarea
              id="notes"
              style={{ ...s.textarea, borderColor: fieldErrors.notes ? 'var(--error)' : undefined }}
              placeholder="Optional notes…"
              value={notes}
              onChange={e => setNotes(e.target.value)}
            />
            {fieldErrors.notes && <p style={s.fieldError}>{fieldErrors.notes}</p>}
          </div>
        </div>

        {/* Set scores */}
        <div style={s.section}>
          <h2 style={s.sectionHeading}>Set scores</h2>

          {sets.map((set, i) => (
            <div key={set.id} style={s.setRow}>
              <span style={s.setLabel}>Set {i + 1}</span>
              <input
                style={{
                  ...s.scoreInput,
                  borderColor: fieldErrors[`sets[${i}].playerScore`] ? 'var(--error)' : undefined,
                }}
                type="number"
                min={0}
                max={99}
                placeholder="0"
                value={set.playerScore}
                onChange={e => updateSet(i, 'playerScore', e.target.value)}
                required
              />
              <span style={s.vs}>vs</span>
              <input
                style={{
                  ...s.scoreInput,
                  borderColor: fieldErrors[`sets[${i}].opponentScore`] ? 'var(--error)' : undefined,
                }}
                type="number"
                min={0}
                max={99}
                placeholder="0"
                value={set.opponentScore}
                onChange={e => updateSet(i, 'opponentScore', e.target.value)}
                required
              />
              <button
                type="button"
                style={{ ...s.removeBtn, opacity: sets.length === 1 ? 0.3 : 1 }}
                onClick={() => removeSet(i)}
                disabled={sets.length === 1}
                title="Remove set"
              >
                ✕
              </button>
            </div>
          ))}

          {fieldErrors.sets && <p style={s.fieldError}>{fieldErrors.sets}</p>}

          <button
            type="button"
            style={{ ...s.addBtn, opacity: sets.length >= 5 ? 0.4 : 1 }}
            onClick={addSet}
            disabled={sets.length >= 5}
          >
            + Add set {sets.length < 5 ? `(${sets.length}/5)` : '(max)'}
          </button>

          {hasScores && (
            <div style={s.liveScore}>
              <span style={s.liveScoreNum}>
                {setsWon} – {setsLost}
              </span>
              <span style={{ ...s.liveScoreLabel, color: resultColor }}>{result}</span>
            </div>
          )}
        </div>

        <button style={{ ...s.submitBtn, opacity: busy ? 0.7 : 1 }} type="submit" disabled={busy}>
          {busy ? 'Saving…' : submitLabel}
        </button>
      </form>
    </>
  )
}
