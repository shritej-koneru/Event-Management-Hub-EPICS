import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'

import { api, setAuthToken, setUnauthorizedHandler } from '@/lib/api'
import type { Role, User } from '@/lib/types'

import { AuthContext, NotManagerError } from './context'
import type { AuthState } from './context'

const isManager = (role: Role) => role === 'organizer' || role === 'admin'

const STORAGE_KEY = 'eventhub.token'

function readStoredToken(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function storeToken(token: string | null): void {
  try {
    if (token) localStorage.setItem(STORAGE_KEY, token)
    else localStorage.removeItem(STORAGE_KEY)
  } catch {
    // storage unavailable (private mode): session just won't persist
  }
}

const initialToken = readStoredToken()
setAuthToken(initialToken)

interface AuthResponse {
  token: string
  user: User
}

export default function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(initialToken)
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState<boolean>(initialToken !== null)

  const applyToken = useCallback((next: string | null) => {
    setAuthToken(next)
    storeToken(next)
    setToken(next)
  }, [])

  const logout = useCallback(() => {
    applyToken(null)
    setUser(null)
    setLoading(false)
  }, [applyToken])

  // Any 401 on a request that carried a token means the session is dead.
  useEffect(() => {
    setUnauthorizedHandler(logout)
    return () => setUnauthorizedHandler(null)
  }, [logout])

  // Validate a stored token on load.
  useEffect(() => {
    if (!initialToken) return
    let cancelled = false
    api<{ user: User }>('/auth/me')
      .then((res) => {
        if (!cancelled) setUser(res.user)
      })
      .catch(() => {
        // 401 already logged us out via the handler; other errors leave us signed-out in UI only
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const login = useCallback(
    async (email: string, password: string) => {
      const res = await api<AuthResponse>('/auth/login', { method: 'POST', body: { email, password } })
      if (!isManager(res.user.role)) {
        // Never keep a student session in this console.
        throw new NotManagerError()
      }
      applyToken(res.token)
      setUser(res.user)
      setLoading(false)
    },
    [applyToken],
  )

  const registerManager = useCallback(
    async (name: string, email: string, password: string) => {
      const res = await api<AuthResponse>('/auth/register-manager', { method: 'POST', body: { name, email, password } })
      if (!isManager(res.user.role)) throw new NotManagerError()
      applyToken(res.token)
      setUser(res.user)
      setLoading(false)
    },
    [applyToken],
  )

  const value = useMemo<AuthState>(
    () => ({
      token,
      user,
      loading,
      isManager: user ? isManager(user.role) : false,
      login,
      registerManager,
      logout,
    }),
    [token, user, loading, login, registerManager, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
