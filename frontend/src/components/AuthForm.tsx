import { useState } from 'react'
import type { FormEvent } from 'react'
import { LogIn } from 'lucide-react'

import { useAuth } from '@/auth/useAuth'
import { errorMessage } from '@/lib/api'

import { inputClass, primaryButtonClass } from './styles'

/** Login / register form with inline server errors. Calls `onDone` after success. */
export default function AuthForm({ onDone }: { onDone?: () => void }) {
  const { login, register } = useAuth()
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (mode === 'login') await login(email.trim(), password)
      else await register(name.trim(), email.trim(), password)
      onDone?.()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const isLogin = mode === 'login'

  return (
    <form
      onSubmit={submit}
      aria-label={isLogin ? 'Sign in' : 'Create account'}
      className="rounded-[24px] bg-[rgba(255,255,255,0.97)] p-6 text-[#282348] shadow-[0_15px_35px_rgba(20,0,80,0.25)]"
    >
      <h2 className="mb-4 flex items-center gap-2 text-xl font-bold text-[#5c3ce8]">
        <LogIn className="h-5 w-5" aria-hidden />
        {isLogin ? 'Sign in' : 'Create account'}
      </h2>

      <div className="flex flex-col gap-3">
        {!isLogin && (
          <label className="text-xs font-semibold">
            Name
            <input
              required
              autoComplete="name"
              className={inputClass}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </label>
        )}
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
            minLength={isLogin ? undefined : 6}
            autoComplete={isLogin ? 'current-password' : 'new-password'}
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

      <button type="submit" disabled={busy} className={`${primaryButtonClass} mt-5`}>
        {busy ? 'PLEASE WAIT...' : isLogin ? 'SIGN IN' : 'CREATE ACCOUNT'}
      </button>

      <button
        type="button"
        onClick={() => {
          setMode(isLogin ? 'register' : 'login')
          setError(null)
        }}
        className="mt-3 w-full cursor-pointer bg-transparent text-sm font-semibold text-[#5c3ce8] underline"
      >
        {isLogin ? 'No account? Register' : 'Have an account? Sign in'}
      </button>
    </form>
  )
}
