import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Send, Trash2 } from 'lucide-react'

import { useAuth } from '@/auth/useAuth'
import ConfirmDialog from '@/components/Dialog'
import { Empty, ErrorNote, Loading } from '@/components/Feedback'
import PageContainer from '@/components/layout/PageContainer'
import { draftAnnouncementWithAi, useAiEnabled } from '@/lib/ai'
import {
  buttonIconDanger,
  buttonPrimary,
  buttonSecondary,
  chipClass,
  errorTextClass,
  inputClass,
  labelClass,
  panelClass,
  panelHeader,
  panelTitle,
  textareaClass,
} from '@/components/styles'
import { api } from '@/lib/api'
import { cleanAnnouncement, formatDate, formatDateTime, roomLabel } from '@/lib/format'
import { apiErrorMessage } from '@/lib/manage'
import type { CampusMap, EventBroadcast, ManagedAnnouncement, ManagedEvent } from '@/lib/types'
import { useFetch } from '@/lib/useFetch'
import { useVenueNames } from '@/lib/useVenueNames'
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
  const [filterEvent, setFilterEvent] = useState('')
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

  const venueNames = useVenueNames()
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

  const aiEnabled = useAiEnabled()
  const [notes, setNotes] = useState('')
  const [aiBusy, setAiBusy] = useState(false)

  /** Puts a draft in the message box. It is never sent: the manager reviews and presses Send. */
  async function draftWithAi() {
    setAiBusy(true)
    setError(null)
    setNotice(null)
    try {
      setMessage(await draftAnnouncementWithAi(notes.trim(), target === 'event' && eventId ? eventId : undefined))
      setNotice('Draft added. Check it, edit it, then send it yourself.')
    } catch (err) {
      setError(apiErrorMessage(err))
    } finally {
      setAiBusy(false)
    }
  }

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

  const all = sent.data?.announcements ?? []
  const knownTitles = new Map(events.map((e) => [e.id, e.title]))
  const filterOptions = [
    ...events.map((e) => ({ id: e.id, label: e.title })),
    ...[...new Set(all.map((a) => a.eventId).filter((id): id is string => !!id && !knownTitles.has(id)))].map(
      (id) => ({ id, label: `Event ${id}` }),
    ),
  ]
  const list = filterEvent ? all.filter((a) => a.eventId === filterEvent) : all

  return (
    <PageContainer title="Announcements" description="Broadcast to attendees' phones in real time">
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <form onSubmit={submit} aria-label="Send announcement" className={`${panelClass} min-w-0`}>
          <div className={panelHeader}>
            <h2 className={panelTitle}>Send announcement</h2>
          </div>
          <div className="flex flex-col gap-4 p-4">
            <div>
              <label className={labelClass}>
                Message
                <textarea
                  required
                  maxLength={MAX}
                  rows={5}
                  className={textareaClass}
                  value={message}
                  onChange={(e) => setMessage(e.target.value)}
                />
              </label>
              <p
                className={`mt-1 text-right text-caption font-medium ${left < 20 ? 'text-danger' : 'text-ink-3'}`}
                aria-live="polite"
              >
                {message.length}/{MAX} characters
              </p>
            </div>
            <label className={labelClass}>
              Send to
              <select className={inputClass} value={target} onChange={(e) => setTarget(e.target.value as Target)}>
                <option value="all">Everyone</option>
                <option value="venue">A venue</option>
                <option value="event">One of my events</option>
              </select>
            </label>
            {target === 'venue' && (
              <label className={labelClass}>
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
              <label className={labelClass}>
                Event
                <select className={inputClass} value={eventId} onChange={(e) => setEventId(e.target.value)}>
                  <option value="">Select an event</option>
                  {events.map((ev) => (
                    <option key={ev.id} value={ev.id}>
                      {ev.title} ({formatDate(ev.date)})
                    </option>
                  ))}
                </select>
              </label>
            )}
            {error && (
              <p role="alert" className={errorTextClass}>
                {error}
              </p>
            )}
            {notice && (
              <p role="status" className="text-small text-success">
                {notice}
              </p>
            )}
            {aiEnabled && (
              <div className="rounded-md border border-line p-3">
                <label className={labelClass}>
                  Draft with AI from your notes (optional)
                  <input
                    maxLength={800}
                    className={inputClass}
                    value={notes}
                    placeholder="e.g. remind people the doors open at 10:00"
                    onChange={(e) => setNotes(e.target.value)}
                  />
                </label>
                <div className="mt-2 flex items-center gap-3">
                  <button type="button" className={buttonSecondary} disabled={aiBusy || !notes.trim()} onClick={draftWithAi}>
                    {aiBusy ? 'Drafting...' : 'Draft with AI'}
                  </button>
                  <p className="text-caption text-ink-3">Fills the message only. Review and edit, then press Send yourself.</p>
                </div>
              </div>
            )}
            <div>
              <button type="submit" disabled={busy} className={buttonPrimary}>
                <Send className="h-4 w-4" strokeWidth={1.75} aria-hidden />
                {busy ? 'Sending...' : 'Send announcement'}
              </button>
            </div>
          </div>
        </form>

        <section aria-labelledby="live-heading" className={`${panelClass} min-w-0`}>
          <div className={panelHeader}>
            <h2 id="live-heading" className={panelTitle}>
              Live feed
            </h2>
            <span className={chipClass(status === 'connected' ? 'success' : 'neutral')}>Socket: {status}</span>
          </div>
          {live.length === 0 && <Empty>Waiting for announcements...</Empty>}
          <ul aria-live="polite">
            {live.map((l) => (
              <li key={l.key} className="border-t border-line px-4 py-3 text-body first:border-t-0">
                {l.text}
                <span className="block text-caption font-medium text-ink-3">{formatDateTime(l.at)}</span>
              </li>
            ))}
          </ul>
        </section>
      </div>

      <section aria-labelledby="sent-heading" className={`${panelClass} mt-6`}>
        <div className={`${panelHeader} flex-wrap`}>
          <h2 id="sent-heading" className={panelTitle}>
            Sent announcements
          </h2>
          <div className="flex flex-wrap items-end gap-3">
            <label className={labelClass}>
              Event
              <select
                className={`${inputClass} w-auto`}
                value={filterEvent}
                onChange={(e) => setFilterEvent(e.target.value)}
              >
                <option value="">All events</option>
                {filterOptions.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            {isAdmin && (
              <label className={labelClass}>
                Show
                <select
                  className={`${inputClass} w-auto`}
                  value={scope}
                  onChange={(e) => setScope(e.target.value as Scope)}
                >
                  <option value="mine">Mine</option>
                  <option value="all">All</option>
                </select>
              </label>
            )}
          </div>
        </div>
        {sent.error && (
          <div className="p-4 pb-0">
            <ErrorNote>{sent.error}</ErrorNote>
          </div>
        )}
        {sent.loading && <Loading />}
        {!sent.loading && !sent.error && list.length === 0 && (
          <Empty>{filterEvent ? 'No announcements for this event.' : 'Nothing sent yet.'}</Empty>
        )}
        <ul>
          {list.map((a) => (
            <li
              key={a.id}
              className="flex items-start justify-between gap-3 border-t border-line px-4 py-3 first:border-t-0"
            >
              <div className="min-w-0">
                <p className="whitespace-pre-wrap break-words text-body">{cleanAnnouncement(a.message)}</p>
                <p className="mt-1 flex flex-wrap items-center gap-2 text-caption font-medium text-ink-3">
                  {formatDateTime(a.at)}
                  <span className={chipClass(a.global ? 'info' : 'neutral')}>
                    {roomLabel(a.room, venueNames, a.global)}
                  </span>
                </p>
              </div>
              <button
                type="button"
                className={buttonIconDanger}
                aria-label={`Delete announcement: ${a.message.slice(0, 40)}`}
                onClick={() => {
                  setDeleteError(null)
                  setDeleting(a)
                }}
              >
                <Trash2 className="h-4 w-4" strokeWidth={1.75} aria-hidden />
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
    </PageContainer>
  )
}
