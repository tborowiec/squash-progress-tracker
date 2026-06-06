import client from './client'

export interface UserResponse {
  id: number
  email: string
}

export interface ApiError {
  status: number
  message: string
  fieldErrors: Record<string, string> | null
}

export const me = () => client.get<UserResponse>('/api/auth/me').then(r => r.data)

export const login = (email: string, password: string) =>
  client.post<UserResponse>('/api/auth/login', { email, password }).then(r => r.data)

export const register = (email: string, password: string) =>
  client.post<UserResponse>('/api/auth/register', { email, password }).then(r => r.data)

export const logout = () => client.post('/api/auth/logout')
