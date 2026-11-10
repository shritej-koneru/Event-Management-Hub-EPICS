import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'

import { NotManagerError } from '@/auth/context'
import { useAuth } from '@/auth/useAuth'
import { buttonPrimary, errorTextClass, inputClass, labelClass, panelClass } from '@/components/styles'
import { api } from '@/lib/api'
import { apiErrorMessage } from '@/lib/manage'

/**
 * Organizer / admin sign-in. Student accounts are rejected (no session kept).
 * When the server allows it (GET /auth/manager-signup), the same card can create a manager (organizer) account.
 * Sign-up in the mobile app creates students; only this form creates managers.
 */
export default function LoginPage() {
  const { user, isManager, login, registerManager } = useAuth()
  const navigate = useNavigate()
  const [mode, setMode] = useState<'signin' | 'signup'>('signin')
  const [signupAllowed, setSignupAllowed] = useState(false)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Offer account creation only if the server says it is switched on (it is off by default in production).
  useEffect(() => {
    let cancelled = false
    api<{ enabled: boolean }>('/auth/manager-signup')
      .then((res) => {
        if (!cancelled) setSignupAllowed(res.enabled === true)
      })
      .catch(() => {
        if (!cancelled) setSignupAllowed(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  if (user && isManager) return <Navigate to="/" replace />

  const signingUp = mode === 'signup' && signupAllowed

  function switchMode(next: 'signin' | 'signup') {
    setMode(next)
    setError(null)
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (busy) return // never send the same sign-up twice
    setBusy(true)
    setError(null)
    try {
      if (signingUp) await registerManager(name.trim(), email.trim(), password)
      else await login(email.trim(), password)
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
        <form onSubmit={submit} aria-label={signingUp ? 'Create manager account' : 'Sign in'} className={`${panelClass} p-6`}>
          <h1 className="text-title font-semibold text-ink">{signingUp ? 'Create manager account' : 'Sign in'}</h1>
          <p className="mb-5 mt-1 text-small text-ink-2">
            {signingUp
              ? 'Manager accounts can publish announcements and answer Help desk chats. Student accounts are created in the mobile app.'
              : signupAllowed
                ? 'Use your organizer or admin account, or create a manager account.'
                : 'Use your organizer or admin account. Accounts are created by an administrator.'}
          </p>
          <div className="flex flex-col gap-4">
            {signingUp && (
              <label className={labelClass}>
                Name
                <input
                  required
                  type="text"
                  autoComplete="name"
                  maxLength={120}
                  className={inputClass}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                />
              </label>
            )}
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
                minLength={signingUp ? 6 : undefined}
                autoComplete={signingUp ? 'new-password' : 'current-password'}
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
            {signingUp ? (busy ? 'Creating account...' : 'Create account') : busy ? 'Signing in...' : 'Sign in'}
          </button>
          {signupAllowed && (
            <button
              type="button"
              onClick={() => switchMode(signingUp ? 'signin' : 'signup')}
              className="mt-4 w-full text-center text-small font-medium text-accent hover:underline"
            >
              {signingUp ? 'Back to sign in' : 'Create a manager account'}
            </button>
          )}
        </form>
      </div>
    </div>
  )
}
