import { Navigate, Route, Routes } from 'react-router-dom'
import ProtectedRoute from './components/ProtectedRoute'
import { AuthProvider } from './contexts/AuthContext'
import EditMatchPage from './pages/EditMatchPage'
import GamePlanPage from './pages/GamePlanPage'
import HistoryPage from './pages/HistoryPage'
import HomePage from './pages/HomePage'
import LoginPage from './pages/LoginPage'
import LogMatchPage from './pages/LogMatchPage'
import RegisterPage from './pages/RegisterPage'

export default function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route element={<ProtectedRoute />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/matches/new" element={<LogMatchPage />} />
          <Route path="/matches/:id/edit" element={<EditMatchPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/game-plan" element={<GamePlanPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  )
}
