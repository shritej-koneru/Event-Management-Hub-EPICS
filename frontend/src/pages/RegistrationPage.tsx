import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Ticket } from 'lucide-react'

import { useAuth } from '@/auth/useAuth'
import AuthForm from '@/components/AuthForm'
import { primaryButtonClass } from '@/components/styles'
import { errorMessage } from '@/lib/api'

/**
 * The server has no per-event registration endpoint (registration is an
 * Android-local concept per the API contract). The real server-side action is a
 * bookmark, so this page signs the user in if needed and saves the event to
 * "My Events".
 */
export default function RegistrationPage() {
  const { eventId } = useParams<{ eventId: string }>()
  const navigate = useNavigate()
  const { user, bookmarks, toggleBookmark } = useAuth()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const saved = eventId ? bookmarks.includes(eventId) : false

  async function save() {
    if (!eventId) return
    setBusy(true)
    setError(null)
    try {
      if (!saved) await toggleBookmark(eventId)
      navigate('/my-events')
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="px-[18px] pb-[110px] pt-5">
      <button
        type="button"
        aria-label="Back to event"
        onClick={() => navigate(`/events/${eventId}`)}
        className="mb-[25px] flex h-[45px] w-[45px] cursor-pointer items-center justify-center rounded-[15px] border-none bg-white/16 text-white backdrop-blur-[10px] transition-all hover:bg-white/28 hover:-translate-x-[3px]"
      >
        <ArrowLeft className="h-6 w-6" aria-hidden />
      </button>

      {!user ? (
        <AuthForm />
      ) : (
        <div className="rounded-[24px] bg-[rgba(255,255,255,0.97)] p-6 text-[#282348] shadow-[0_15px_35px_rgba(20,0,80,0.25)]">
          <h2 className="mb-2.5 flex items-center gap-2 text-xl font-bold text-[#5c3ce8]">
            <Ticket className="h-5 w-5" aria-hidden />
            Save this event
          </h2>
          <p className="mb-5 text-[14px] leading-[1.6] text-[#66627a]">
            Signed in as {user.name}. Saving adds the event to My Events and improves your
            recommendations.
          </p>
          {error && (
            <p role="alert" className="mb-3 text-sm text-red-600">
              {error}
            </p>
          )}
          <button type="button" onClick={save} disabled={busy} className={primaryButtonClass}>
            {saved ? 'ALREADY SAVED - VIEW MY EVENTS' : busy ? 'SAVING...' : 'SAVE TO MY EVENTS'}
          </button>
        </div>
      )}
    </div>
  )
}
