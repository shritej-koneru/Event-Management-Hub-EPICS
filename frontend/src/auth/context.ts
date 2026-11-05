import { createContext } from 'react'

import type { User } from '@/lib/types'

export interface AuthState {
  token: string | null
  user: User | null
  /** True while the stored token is being validated with GET /auth/me. */
  loading: boolean
  bookmarks: string[]
  canManageEvents: boolean
  login: (email: string, password: string) => Promise<void>
  register: (name: string, email: string, password: string) => Promise<void>
  logout: () => void
  toggleBookmark: (eventId: string) => Promise<void>
  saveInterests: (interests: string[]) => Promise<void>
}

export const AuthContext = createContext<AuthState | null>(null)
