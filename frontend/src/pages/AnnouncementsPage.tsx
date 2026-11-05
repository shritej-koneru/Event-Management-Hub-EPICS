import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Send, Trash2 } from 'lucide-react'

import { useAuth } from '@/auth/useAuth'
import ConfirmDialog from '@/components/Dialog'
import { Empty, ErrorNote, Loading, PageHeading } from '@/components/Feedback'
import { cardClass, compactPrimaryClass, dangerButtonClass, inputClass } from '@/components/styles'
import { api } from '@/lib/api'
import { apiErrorMessage } from '@/lib/manage'
import type { CampusMap, EventBroadcast, ManagedAnnouncement, ManagedEvent } from '@/lib/types'
import { useFetch } from '@/lib/useFetch'
import { useSocket } from '@/realtime/context'

const MAX = 280
type Target = 'all' | 'venue' | 'event'
type Scope = 'mine' | 'all'

interface LiveItem {
  key: number
  text: string
  at: string
}

export default function AnnouncementsPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === 'admin'
  const { socket, status } = useSocket()

  const [message, setMessage] = useState('')
  const [target, setTarget] = useState<Target>('all')
  const [venueId, setVenueId] = useState('')
  const [eventId, setEventId] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [scope, setScope] = useState<Scope>('mine')
  const [deleting, setDeleting] = useState<ManagedAnnouncement | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [deleteBusy, setDeleteBusy] = useState(false)
  const [live, setLive] = useState<LiveItem[]>([])

  const sent = useFetch(
    (signal) =>
      api<{ announcements: ManagedAnnouncement[] }>('/manage/announcements', {
        query: { scope: isAdmin ? scope : 'mine' },
        signal,
      }),
    scope,
  )
  const map = useFetch((signal) => api<CampusMap>('/navigation/map', { signal }))
  const myEvents = useFetch((signal) =>
    api<{ events: ManagedEvent[] }>('/manage/events', { query: { scope: 'mine' }, signal }),
  )

  const reloadSent = sent.reload

  // Live feed: manager announcements and event notices share the `event` socket message.
  useEffect(() => {
    if (!socket) return
    const onEvent = (p: EventBroadcast) => {
      const text = p.announcement?.message ?? p.announcement?.title ?? p.title
      if (!text) return
      setLive((prev) =>
        [{ key: Date.now() + Math.random(), text, at: new Date().toISOString() }, ...prev].slice(0, 20),
      )
      reloadSent()
    }
    socket.on('event', onEvent)
    return () => {
      socket.off('event', onEvent)
    }
  }, [socket, reloadSent])

  const venues = map.data?.venues ?? []
  const events = myEvents.data?.events ?? []
  const left = MAX - message.length

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setNotice(null)
    const body: { message: string; venueId?: string; eventId?: string } = { message: message.trim() }
    if (!body.message) {
      setError('Write a message first.')
      return
    }
    if (target === 'venue') {
      if (!venueId) return setError('Choose a venue.')
      body.venueId = venueId
    }
    if (target === 'event') {
      if (!eventId) return setError('Choose one of your events.')
      body.eventId = eventId
    }
    setBusy(true)
    try {
      await api('/manage/announcements', { method: 'POST', body })
      setNotice('Announcement sent.')
      setMessage('')
      sent.reload()
    } catch (err) {
      setError(apiErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  async function confirmDelete() {
    if (!deleting) return
    setDeleteBusy(true)
    setDeleteError(null)
    try {
      await api(`/manage/announcements/${encodeURIComponent(String(deleting.id))}`, { method: 'DELETE' })
      setDeleting(null)
      sent.reload()
    } catch (err) {
      setDeleteError(apiErrorMessage(err))
    } finally {
      setDeleteBusy(false)
    }
  }

  const list = sent.data?.announcements ?? []

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      <PageHeading title="Announcements" subtitle="Broadcast to attendees' phones in real time" />

      <div className="grid gap-5 lg:grid-cols-2">
        <form onSubmit={submit} aria-label="Send announcement" className={cardClass}>
          <h2 className="mb-3 text-lg font-bold text-[#5c3ce8]">Send announcement</h2>
          <div className="flex flex-col gap-3">
            <label className="text-xs font-semibold">
              Message
              <textarea
                required
                maxLength={MAX}
                className={`${inputClass} h-28 py-2`}
                value={message}
                onChange={(e) => setMessage(e.target.value)}
              />
            </label>
            <p className={`text-right text-xs ${left < 20 ? 'text-red-600' : 'text-[#77758b]'}`} aria-live="polite">
              {message.length}/{MAX} characters
            </p>
            <label className="text-xs font-semibold">
              Send to
              <select className={inputClass} value={target} onChange={(e) => setTarget(e.target.value as Target)}>
                <option value="all">Everyone</option>
                <option value="venue">A venue</option>
                <option value="event">One of my events</option>
              </select>
            </label>
            {target === 'venue' && (
              <label className="text-xs font-semibold">
                Venue
                <select className={inputClass} value={venueId} onChange={(e) => setVenueId(e.target.value)}>
                  <option value="">Select a venue</option>
                  {venues.map((v) => (
                    <option key={v.id} value={v.id}>
                      {v.name}
                    </option>
                  ))}
                </select>
              </label>
            )}
            {target === 'event' && (
              <label className="text-xs font-semibold">
                Event
                <select className={inputClass} value={eventId} onChange={(e) => setEventId(e.target.value)}>
                  <option value="">Select an event</option>
                  {events.map((ev) => (
                    <option key={ev.id} value={ev.id}>
                      {ev.title} ({ev.date})
                    </option>
                  ))}
                </select>
              </label>
            )}
          </div>
          {error && (
            <p role="alert" className="mt-3 text-sm text-red-600">
              {error}
            </p>
          )}
          {notice && (
            <p role="status" className="mt-3 text-sm text-green-700">
              {notice}
            </p>
          )}
          <button type="submit" disabled={busy} className={`${compactPrimaryClass} mt-4`}>
            <Send className="h-4 w-4" aria-hidden />
            {busy ? 'Sending...' : 'Send announcement'}
          </button>
        </form>

        <section aria-labelledby="live-heading" className={cardClass}>
          <h2 id="live-heading" className="mb-1 text-lg font-bold text-[#5c3ce8]">
            Live feed
          </h2>
          <p className="mb-3 text-xs text-[#77758b]">Socket: {status}</p>
          {live.length === 0 && <p className="py-4 text-sm text-[#77758b]">Waiting for announcements...</p>}
          <ul aria-live="polite" className="divide-y divide-[#eee9fb]">
            {live.map((l) => (
              <li key={l.key} className="py-2 text-sm">
                {l.text}
                <span className="block text-xs text-[#77758b]">{new Date(l.at).toLocaleTimeString()}</span>
              </li>
            ))}
          </ul>
        </section>
      </div>

      <section aria-labelledby="sent-heading" className={`${cardClass} mt-5`}>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <h2 id="sent-heading" className="text-lg font-bold text-[#5c3ce8]">
            Sent announcements
          </h2>
          {isAdmin && (
            <label className="text-xs font-semibold">
              Show
              <select className={inputClass} value={scope} onChange={(e) => setScope(e.target.value as Scope)}>
                <option value="mine">Mine</option>
                <option value="all">All</option>
              </select>
            </label>
          )}
        </div>
        {sent.error && <ErrorNote>{sent.error}</ErrorNote>}
        {sent.loading && <Loading />}
        {!sent.loading && !sent.error && list.length === 0 && <Empty>Nothing sent yet.</Empty>}
        <ul className="divide-y divide-[#eee9fb]">
          {list.map((a) => (
            <li key={a.id} className="flex items-start justify-between gap-3 py-3 text-sm">
              <div>
                <p>{a.message}</p>
                <p className="text-xs text-[#77758b]">
                  {new Date(a.at).toLocaleString()} - {a.room}
                </p>
              </div>
              <button
                type="button"
                className={dangerButtonClass}
                aria-label={`Delete announcement: ${a.message.slice(0, 40)}`}
                onClick={() => {
                  setDeleteError(null)
                  setDeleting(a)
                }}
              >
                <Trash2 className="h-3.5 w-3.5" aria-hidden />
                Delete
              </button>
            </li>
          ))}
        </ul>
      </section>

      {deleting && (
        <ConfirmDialog
          title="Delete announcement?"
          confirmLabel="Delete"
          busy={deleteBusy}
          error={deleteError}
          onConfirm={confirmDelete}
          onCancel={() => setDeleting(null)}
        >
          This removes it from the history. Phones that already received it keep their copy.
        </ConfirmDialog>
      )}
    </div>
  )
}
