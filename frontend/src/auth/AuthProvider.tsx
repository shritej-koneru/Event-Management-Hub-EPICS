import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'

import { api, setAuthToken, setUnauthorizedHandler } from '@/lib/api'
import type { User } from '@/lib/types'

import { AuthContext } from './context'
import type { AuthState } from './context'

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

  const accept = useCallback(
    (res: AuthResponse) => {
      applyToken(res.token)
      setUser(res.user)
      setLoading(false)
      // /auth/me carries the bookmark list; the login response does not.
      api<{ user: User }>('/auth/me')
        .then((me) => setUser(me.user))
        .catch(() => undefined)
    },
    [applyToken],
  )

  const login = useCallback(
    async (email: string, password: string) => {
      accept(await api<AuthResponse>('/auth/login', { method: 'POST', body: { email, password } }))
    },
    [accept],
  )

  const register = useCallback(
    async (name: string, email: string, password: string) => {
      accept(
        await api<AuthResponse>('/auth/register', {
          method: 'POST',
          body: { name, email, password },
        }),
      )
    },
    [accept],
  )

  const toggleBookmark = useCallback(async (eventId: string) => {
    const current = (await api<{ bookmarks: string[] }>('/auth/me/bookmarks')).bookmarks
    const res = await api<{ bookmarks: string[] }>(`/auth/me/bookmarks/${encodeURIComponent(eventId)}`, {
      method: current.includes(eventId) ? 'DELETE' : 'POST',
    })
    setUser((u) => (u ? { ...u, bookmarks: res.bookmarks } : u))
  }, [])

  const saveInterests = useCallback(async (interests: string[]) => {
    const res = await api<{ user: User }>('/auth/me/interests', {
      method: 'PUT',
      body: { interests },
    })
    setUser((u) => (u ? { ...u, interests: res.user.interests } : u))
  }, [])

  const value = useMemo<AuthState>(
    () => ({
      token,
      user,
      loading,
      bookmarks: user?.bookmarks ?? [],
      canManageEvents: user?.role === 'organizer' || user?.role === 'admin',
      login,
      register,
      logout,
      toggleBookmark,
      saveInterests,
    }),
    [token, user, loading, login, register, logout, toggleBookmark, saveInterests],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
