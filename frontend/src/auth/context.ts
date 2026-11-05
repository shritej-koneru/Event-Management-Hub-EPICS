import { createContext } from 'react'

import type { User } from '@/lib/types'

export const NOT_MANAGER_MESSAGE = 'This console is for event managers'

/** Thrown by login() when the account is a student (session is not kept). */
export class NotManagerError extends Error {
  constructor() {
    super(NOT_MANAGER_MESSAGE)
    this.name = 'NotManagerError'
  }
}

export interface AuthState {
  token: string | null
  user: User | null
  /** True while the stored token is being validated with GET /auth/me. */
  loading: boolean
  /** organizer or admin */
  isManager: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

export const AuthContext = createContext<AuthState | null>(null)
