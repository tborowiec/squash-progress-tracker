import { createContext, useContext, useEffect, useState } from 'react'
import { me, type UserResponse } from '../api/auth'
import i18n from '../i18n'

interface AuthState {
  user: UserResponse | null
  loading: boolean
  setUser: (u: UserResponse | null) => void
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    me()
      .then(u => {
        setUser(u)
        i18n.changeLanguage(u.locale)
      })
      .catch(() => setUser(null))
      .finally(() => setLoading(false))
  }, [])

  return <AuthContext.Provider value={{ user, loading, setUser }}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
