import { useState } from 'react'
import type { FormEvent } from 'react'
import { LogIn } from 'lucide-react'
import { Navigate, useNavigate } from 'react-router-dom'

import { NotManagerError } from '@/auth/context'
import { useAuth } from '@/auth/useAuth'
import { inputClass, primaryButtonClass } from '@/components/styles'
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
    <div className="min-h-screen bg-gradient-to-br from-[#24106f] via-[#5420d8] to-[#7b35ed] px-[18px] pt-12">
      <div className="mx-auto w-full max-w-md">
        <h1 className="mb-1 text-[25px] font-bold text-white">EventHub Manager</h1>
        <p className="mb-4 text-[13px] text-white/70">
          Sign in with your organizer or admin account. Accounts are created by an administrator.
        </p>
        <form
          onSubmit={submit}
          aria-label="Sign in"
          className="rounded-[24px] bg-[rgba(255,255,255,0.97)] p-6 text-[#282348] shadow-[0_15px_35px_rgba(20,0,80,0.25)]"
        >
          <h2 className="mb-4 flex items-center gap-2 text-xl font-bold text-[#5c3ce8]">
            <LogIn className="h-5 w-5" aria-hidden />
            Sign in
          </h2>
          <div className="flex flex-col gap-3">
            <label className="text-xs font-semibold">
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
            <label className="text-xs font-semibold">
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
            <p role="alert" className="mt-3 text-sm text-red-600">
              {error}
            </p>
          )}
          <button type="submit" disabled={busy} className={`${primaryButtonClass} mt-4`}>
            {busy ? 'SIGNING IN...' : 'SIGN IN'}
          </button>
        </form>
      </div>
    </div>
  )
}
