import { useState } from 'react'
import type { FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'

import { NotManagerError } from '@/auth/context'
import { useAuth } from '@/auth/useAuth'
import { buttonPrimary, errorTextClass, inputClass, labelClass, panelClass } from '@/components/styles'
import { apiErrorMessage } from '@/lib/manage'

/** Organizer / admin sign-in. Student accounts are rejected (no session kept). */
export default function LoginPage() {
  const { user, isManager, login } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (user && isManager) return <Navigate to="/" replace />

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(email.trim(), password)
      navigate('/', { replace: true })
    } catch (err) {
      setError(err instanceof NotManagerError ? err.message : apiErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-paper px-4 py-10">
      <div className="w-full max-w-[360px]">
        <div className="mb-4 flex items-center gap-2.5">
          <span aria-hidden className="h-4 w-[3px] bg-accent" />
          <span className="text-heading font-semibold text-ink">EventHub</span>
        </div>
        <form onSubmit={submit} aria-label="Sign in" className={`${panelClass} p-6`}>
          <h1 className="text-title font-semibold text-ink">Sign in</h1>
          <p className="mb-5 mt-1 text-small text-ink-2">
            Use your organizer or admin account. Accounts are created by an administrator.
          </p>
          <div className="flex flex-col gap-4">
            <label className={labelClass}>
              Email
              <input
                required
                type="email"
                autoComplete="email"
                className={inputClass}
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </label>
            <label className={labelClass}>
              Password
              <input
                required
                type="password"
                autoComplete="current-password"
                className={inputClass}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </label>
          </div>
          {error && (
            <p role="alert" className={`${errorTextClass} mt-3`}>
              {error}
            </p>
          )}
          <button type="submit" disabled={busy} className={`${buttonPrimary} mt-5 w-full`}>
            {busy ? 'Signing in...' : 'Sign in'}
          </button>
        </form>
      </div>
    </div>
  )
}
