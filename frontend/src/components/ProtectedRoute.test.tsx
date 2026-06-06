import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import * as authApi from '../api/auth'
import { AuthProvider, useAuth } from '../contexts/AuthContext'
import ProtectedRoute from './ProtectedRoute'

// Spy on useAuth so pure-state tests can set any auth state synchronously,
// while keeping AuthProvider real (spread of actual) for the in-flight test.
vi.mock('../contexts/AuthContext', async () => {
  const actual =
    await vi.importActual<typeof import('../contexts/AuthContext')>('../contexts/AuthContext')
  return { ...actual, useAuth: vi.fn() }
})

// Prevent any real network call from escaping jsdom.
vi.mock('../api/auth', () => ({ me: vi.fn() }))

const mockUseAuth = vi.mocked(useAuth)
const mockMe = vi.mocked(authApi.me)

const Protected = () => <div>protected-content</div>
const Login = () => <div>login-page</div>

function mountGuard(initialPath = '/protected') {
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route element={<ProtectedRoute />}>
          <Route path="/protected" element={<Protected />} />
        </Route>
        <Route path="/login" element={<Login />} />
      </Routes>
    </MemoryRouter>,
  )
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('ProtectedRoute', () => {
  describe('pure logic states (mocked useAuth)', () => {
    it('renders nothing while loading — loading check fires before user check', () => {
      mockUseAuth.mockReturnValue({ user: null, loading: true, setUser: vi.fn() })
      mountGuard()
      expect(screen.queryByText('protected-content')).not.toBeInTheDocument()
      expect(screen.queryByText('login-page')).not.toBeInTheDocument()
    })

    it('redirects to /login when unauthenticated', () => {
      mockUseAuth.mockReturnValue({ user: null, loading: false, setUser: vi.fn() })
      mountGuard()
      expect(screen.getByText('login-page')).toBeInTheDocument()
      expect(screen.queryByText('protected-content')).not.toBeInTheDocument()
    })

    it('renders the outlet when authenticated', () => {
      mockUseAuth.mockReturnValue({
        user: { id: 1, email: 'player@example.com' },
        loading: false,
        setUser: vi.fn(),
      })
      mountGuard()
      expect(screen.getByText('protected-content')).toBeInTheDocument()
      expect(screen.queryByText('login-page')).not.toBeInTheDocument()
    })
  })

  describe('in-flight session (real AuthProvider + never-resolving me())', () => {
    it('does not redirect while the session check is still pending', async () => {
      // me() hangs forever — loading stays true, user stays null
      mockMe.mockReturnValue(new Promise<never>(() => {}))

      // Restore the real useAuth so ProtectedRoute reads from the real context
      // provided by the real AuthProvider below (not from the spy).
      const { useAuth: realUseAuth } =
        await vi.importActual<typeof import('../contexts/AuthContext')>('../contexts/AuthContext')
      mockUseAuth.mockImplementation(realUseAuth)

      render(
        <MemoryRouter initialEntries={['/protected']}>
          <AuthProvider>
            <Routes>
              <Route element={<ProtectedRoute />}>
                <Route path="/protected" element={<Protected />} />
              </Route>
              <Route path="/login" element={<Login />} />
            </Routes>
          </AuthProvider>
        </MemoryRouter>,
      )

      // loading:true → neither protected content nor /login redirect should appear
      expect(screen.queryByText('login-page')).not.toBeInTheDocument()
      expect(screen.queryByText('protected-content')).not.toBeInTheDocument()
    })
  })

  describe('router-level redirect', () => {
    it('funnels an anonymous user hitting /history through to /login', () => {
      mockUseAuth.mockReturnValue({ user: null, loading: false, setUser: vi.fn() })
      render(
        <MemoryRouter initialEntries={['/history']}>
          <Routes>
            <Route element={<ProtectedRoute />}>
              <Route path="/history" element={<div>history-page</div>} />
            </Route>
            <Route path="/login" element={<Login />} />
          </Routes>
        </MemoryRouter>,
      )
      expect(screen.getByText('login-page')).toBeInTheDocument()
      expect(screen.queryByText('history-page')).not.toBeInTheDocument()
    })
  })
})
