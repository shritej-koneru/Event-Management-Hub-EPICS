import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Bookmark, MapPin, QrCode, Sparkles, Ticket } from 'lucide-react'

import { useAuth } from '@/auth/useAuth'
import { ErrorNote, Loading } from '@/components/Feedback'
import { api, errorMessage } from '@/lib/api'
import type { EventItem } from '@/lib/types'
import { useSocket } from '@/realtime/context'

const HELP_CATEGORIES = [
  {
    id: 'registration',
    label: 'REGISTRATION',
    icon: Ticket,
    iconBg: 'bg-[#e4ddff]',
    iconColor: 'text-[#6145ef]',
  },
  {
    id: 'navigation',
    label: 'NAVIGATION',
    icon: MapPin,
    iconBg: 'bg-[#d8fff1]',
    iconColor: 'text-[#00a878]',
  },
  {
    id: 'qr-checkin',
    label: 'QR CHECK-IN',
    icon: QrCode,
    iconBg: 'bg-[#fff0d2]',
    iconColor: 'text-[#f29b21]',
  },
]

export default function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>()
  const navigate = useNavigate()
  const { user, bookmarks, toggleBookmark } = useAuth()
  const { socket } = useSocket()
  const [loaded, setLoaded] = useState<{
    id: string
    event: EventItem | null
    error: string | null
  } | null>(null)
  const [tick, setTick] = useState(0)
  const [bookmarkError, setBookmarkError] = useState<string | null>(null)

  useEffect(() => {
    if (!socket) return
    const onEvent = () => setTick((t) => t + 1)
    socket.on('event', onEvent)
    return () => {
      socket.off('event', onEvent)
    }
  }, [socket])

  useEffect(() => {
    if (!eventId) return
    const ctrl = new AbortController()
    api<{ event: EventItem }>(`/events/${encodeURIComponent(eventId)}`, { signal: ctrl.signal })
      .then((r) => setLoaded({ id: eventId, event: r.event, error: null }))
      .catch((err: unknown) => {
        if (!ctrl.signal.aborted) setLoaded({ id: eventId, event: null, error: errorMessage(err) })
      })
    return () => ctrl.abort()
  }, [eventId, tick])

  const current = loaded && loaded.id === eventId ? loaded : null
  const event = current?.event ?? null
  const bookmarked = eventId ? bookmarks.includes(eventId) : false

  async function onBookmark() {
    if (!eventId) return
    setBookmarkError(null)
    try {
      await toggleBookmark(eventId)
    } catch (err) {
      setBookmarkError(errorMessage(err))
    }
  }

  return (
    <div className="px-[18px] pb-[150px] pt-5">
      <button
        type="button"
        aria-label="Back to events"
        onClick={() => navigate('/events')}
        className="mb-[25px] flex h-[45px] w-[45px] cursor-pointer items-center justify-center rounded-[15px] border-none bg-white/16 text-white backdrop-blur-[10px] transition-all hover:bg-white/28 hover:-translate-x-[3px]"
      >
        <ArrowLeft className="h-6 w-6" aria-hidden />
      </button>

      {!current && <Loading label="Loading event..." />}
      {current?.error && <ErrorNote>{current.error}</ErrorNote>}

      {event && (
        <>
          <div className="mb-2 flex items-start justify-between gap-3">
            <h1 className="text-[30px] font-bold text-white">{event.title}</h1>
            {user && (
              <button
                type="button"
                aria-pressed={bookmarked}
                aria-label={bookmarked ? 'Remove bookmark' : 'Bookmark event'}
                onClick={onBookmark}
                className="flex h-[45px] w-[45px] min-w-[45px] cursor-pointer items-center justify-center rounded-[15px] border-none bg-white/16 text-white hover:bg-white/28"
              >
                <Bookmark className={`h-6 w-6 ${bookmarked ? 'fill-white' : ''}`} aria-hidden />
              </button>
            )}
          </div>
          {bookmarkError && <ErrorNote>{bookmarkError}</ErrorNote>}
          <p className="mb-1 text-sm text-white/80">
            {event.date} {event.time} · {event.venue} · {event.category}
          </p>
          <p className="mb-1 text-sm text-white/80">
            {event.registered}/{event.capacity} registered
            {event.host ? ` · Host: ${event.host}` : ''}
          </p>
          <p className="mb-[30px] mt-3 text-sm leading-[1.5] text-white/70">
            {event.description || 'Quick access to everything you need for this event.'}
          </p>
        </>
      )}

      <h2 className="mb-4 text-[21px] font-bold text-white">
        Quick Help Categories
      </h2>

      <div className="grid grid-cols-3 gap-2.5">
        {HELP_CATEGORIES.map((cat) => (
          <button
            key={cat.id}
            type="button"
            onClick={() =>
              navigate(`/events/${eventId}/category/${cat.id}`)
            }
            className="flex min-h-[135px] cursor-pointer flex-col items-center justify-center gap-3 rounded-[21px] bg-[rgba(255,255,255,0.97)] p-2.5 text-[#25204c] shadow-[0_12px_25px_rgba(20,0,80,0.2)] transition-all hover:-translate-y-[6px] hover:shadow-[0_18px_30px_rgba(20,0,80,0.3)] active:scale-[0.95]"
          >
            <div
              className={`flex h-[58px] w-[58px] items-center justify-center rounded-[18px] ${cat.iconBg}`}
            >
              <cat.icon className={`h-[28px] w-[28px] ${cat.iconColor}`} aria-hidden />
            </div>
            <span className="text-[10px] font-bold leading-tight text-center">
              {cat.label}
            </span>
          </button>
        ))}
      </div>

      {/* Info box */}
      <div className="mt-6 rounded-[18px] bg-gradient-to-br from-[#6847f5] to-[#9b4dff] p-[18px] text-white shadow-[0_10px_20px_rgba(90,50,220,0.3)]">
        <div className="flex items-center gap-2 text-sm font-bold">
          <Sparkles className="h-4 w-4" aria-hidden />
          Event Ready
        </div>
        <p className="mt-[7px] text-[13px] leading-[1.5] text-white/90">
          Everything you need for a smooth event experience is available here.
        </p>
      </div>

      {/* Register button - bottom left */}
      <div className="fixed bottom-[100px] left-4 z-[90] max-w-[444px] lg:left-auto lg:left-[calc(50%-240px+18px)]">
        <button
          type="button"
          onClick={() => navigate(`/events/${eventId}/register`)}
          className="flex h-[52px] w-[180px] cursor-pointer items-center justify-center gap-2 rounded-[17px] border-none bg-gradient-to-r from-[#ff5f6d] to-[#ff9966] text-[15px] font-bold text-white shadow-[0_10px_25px_rgba(255,75,90,0.4)] transition-all hover:-translate-y-[4px] hover:shadow-[0_15px_30px_rgba(255,75,90,0.5)] active:scale-[0.96]"
        >
          <Ticket className="h-4 w-4" aria-hidden />
          REGISTER NOW
        </button>
      </div>
    </div>
  )
}
