import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'
import { Loading, PageHeading } from '@/components/Feedback'
import { inputClass, primaryButtonClass } from '@/components/styles'
import { errorMessage } from '@/lib/api'
import type { User } from '@/lib/types'

function InterestsEditor({ user }: { user: User }) {
  const { saveInterests } = useAuth()
  const [text, setText] = useState(user.interests.join(', '))
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    setSaved(false)
    try {
      const list = text
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean)
      await saveInterests(list)
      setSaved(true)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      onSubmit={submit}
      className="mt-5 rounded-[22px] bg-[rgba(255,255,255,0.97)] p-5 text-[#282348]"
    >
      <label className="text-xs font-semibold">
        Interests (comma separated)
        <input
          className={inputClass}
          value={text}
          onChange={(e) => {
            setText(e.target.value)
            setSaved(false)
          }}
          placeholder="Tech, Music, Sports"
        />
      </label>
      {error && (
        <p role="alert" className="mt-3 text-sm text-red-600">
          {error}
        </p>
      )}
      {saved && (
        <p role="status" className="mt-3 text-sm text-green-700">
          Interests saved.
        </p>
      )}
      <button type="submit" disabled={busy} className={`${primaryButtonClass} mt-4`}>
        {busy ? 'SAVING...' : 'SAVE INTERESTS'}
      </button>
    </form>
  )
}

export default function ProfilePage() {
  const { user, loading, logout } = useAuth()

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      <PageHeading title="Profile" />
      {loading ? (
        <Loading />
      ) : !user ? (
        <p className="text-sm text-white/80">
          <Link to="/login" className="font-bold underline">
            Sign in
          </Link>{' '}
          to view your profile.
        </p>
      ) : (
        <>
          <div className="rounded-[22px] bg-[rgba(255,255,255,0.97)] p-5 text-[#282348]">
            <p className="text-lg font-bold">{user.name}</p>
            <p className="text-sm text-[#66627a]">{user.email}</p>
            <p className="mt-1 text-xs font-semibold uppercase text-[#5c3ce8]">{user.role}</p>
          </div>
          <InterestsEditor key={user.id} user={user} />
          <button
            type="button"
            onClick={logout}
            className="mt-5 h-11 cursor-pointer rounded-[14px] border border-white/40 bg-white/16 text-sm font-bold text-white hover:bg-white/25"
          >
            Sign out
          </button>
        </>
      )}
    </div>
  )
}
