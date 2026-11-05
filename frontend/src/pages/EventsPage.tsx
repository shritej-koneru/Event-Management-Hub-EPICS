import { useState } from 'react'
import { Pencil, Plus, Trash2 } from 'lucide-react'

import { useAuth } from '@/auth/useAuth'
import ConfirmDialog from '@/components/Dialog'
import EventForm from '@/components/EventForm'
import { Empty, ErrorNote, Loading, PageHeading } from '@/components/Feedback'
import { cardClass, compactPrimaryClass, dangerButtonClass, inputClass, smallButtonClass } from '@/components/styles'
import { api } from '@/lib/api'
import { apiErrorMessage, isUpcoming } from '@/lib/manage'
import type { CampusMap, ManagedEvent } from '@/lib/types'
import { useFetch } from '@/lib/useFetch'

type Scope = 'mine' | 'all'
type Status = 'all' | 'upcoming' | 'past'

export default function EventsPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === 'admin'
  const [scope, setScope] = useState<Scope>('mine')
  const [text, setText] = useState('')
  const [status, setStatus] = useState<Status>('all')
  const [editing, setEditing] = useState<ManagedEvent | 'new' | null>(null)
  const [deleting, setDeleting] = useState<ManagedEvent | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [deleteBusy, setDeleteBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  const events = useFetch(
    (signal) =>
      api<{ events: ManagedEvent[] }>('/manage/events', {
        query: { scope: isAdmin ? scope : 'mine' },
        signal,
      }),
    scope,
  )
  const map = useFetch((signal) => api<CampusMap>('/navigation/map', { signal }))

  const q = text.trim().toLowerCase()
  const rows = (events.data?.events ?? []).filter((e) => {
    if (status === 'upcoming' && !isUpcoming(e)) return false
    if (status === 'past' && isUpcoming(e)) return false
    if (!q) return true
    return [e.title, e.venue, e.category, e.host].some((s) => s?.toLowerCase().includes(q))
  })

  async function confirmDelete() {
    if (!deleting) return
    setDeleteBusy(true)
    setDeleteError(null)
    try {
      await api(`/events/${encodeURIComponent(deleting.id)}`, { method: 'DELETE' })
      setNotice(`Deleted "${deleting.title}".`)
      setDeleting(null)
      events.reload()
    } catch (err) {
      setDeleteError(apiErrorMessage(err))
    } finally {
      setDeleteBusy(false)
    }
  }

  function saved() {
    setNotice(editing === 'new' ? 'Event created.' : 'Event updated.')
    setEditing(null)
    events.reload()
  }

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <PageHeading title="Events" subtitle={isAdmin ? 'Manage your events or all events' : 'Manage your events'} />
        {editing === null && (
          <button type="button" className={compactPrimaryClass} onClick={() => setEditing('new')}>
            <Plus className="h-4 w-4" aria-hidden />
            Create event
          </button>
        )}
      </div>

      {notice && (
        <p role="status" className="mb-4 rounded-[14px] bg-green-500/25 px-4 py-3 text-sm text-white">
          {notice}
        </p>
      )}

      {editing !== null && (
        <EventForm
          key={editing === 'new' ? 'new' : editing.id}
          event={editing === 'new' ? undefined : editing}
          venues={map.data?.venues ?? []}
          onSaved={saved}
          onCancel={() => setEditing(null)}
        />
      )}

      <div className="mb-4 flex flex-wrap items-end gap-3">
        <label className="min-w-48 flex-1 text-xs font-semibold text-white">
          Search events
          <input
            type="search"
            placeholder="Title, venue, category, host"
            className={inputClass}
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
        </label>
        <label className="text-xs font-semibold text-white">
          When
          <select className={inputClass} value={status} onChange={(e) => setStatus(e.target.value as Status)}>
            <option value="all">All</option>
            <option value="upcoming">Upcoming</option>
            <option value="past">Past</option>
          </select>
        </label>
        {isAdmin && (
          <label className="text-xs font-semibold text-white">
            Show
            <select className={inputClass} value={scope} onChange={(e) => setScope(e.target.value as Scope)}>
              <option value="mine">Mine</option>
              <option value="all">All events</option>
            </select>
          </label>
        )}
      </div>

      {events.error && <ErrorNote>{events.error}</ErrorNote>}
      {events.loading && <Loading />}
      {!events.loading && !events.error && rows.length === 0 && <Empty>No events match.</Empty>}

      {rows.length > 0 && (
        <div className={`${cardClass} overflow-x-auto !p-0`}>
          <table className="w-full min-w-[720px] text-left text-sm">
            <caption className="sr-only">Events</caption>
            <thead className="border-b border-[#eee9fb] text-xs text-[#77758b]">
              <tr>
                <th scope="col" className="px-4 py-3">Event</th>
                <th scope="col" className="px-4 py-3">Date</th>
                <th scope="col" className="px-4 py-3">Venue</th>
                <th scope="col" className="px-4 py-3 text-right">Capacity</th>
                <th scope="col" className="px-4 py-3 text-right">Registered</th>
                <th scope="col" className="px-4 py-3 text-right">Bookmarks</th>
                <th scope="col" className="px-4 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#eee9fb]">
              {rows.map((e) => (
                <tr key={e.id}>
                  <td className="px-4 py-3 font-semibold">
                    {e.title}
                    <span className="block text-xs font-normal text-[#77758b]">{e.category}</span>
                  </td>
                  <td className="whitespace-nowrap px-4 py-3">
                    {e.date} {e.time}
                    {!isUpcoming(e) && <span className="ml-1 text-xs text-[#77758b]">(past)</span>}
                  </td>
                  <td className="px-4 py-3">{e.venue}</td>
                  <td className="px-4 py-3 text-right">{e.capacity}</td>
                  <td className="px-4 py-3 text-right">{e.registered}</td>
                  <td className="px-4 py-3 text-right">{e.bookmarkCount}</td>
                  <td className="px-4 py-3">
                    <div className="flex justify-end gap-2">
                      <button
                        type="button"
                        className={smallButtonClass}
                        aria-label={`Edit ${e.title}`}
                        onClick={() => {
                          setNotice(null)
                          setEditing(e)
                        }}
                      >
                        <Pencil className="h-3.5 w-3.5" aria-hidden />
                        Edit
                      </button>
                      <button
                        type="button"
                        className={dangerButtonClass}
                        aria-label={`Delete ${e.title}`}
                        onClick={() => {
                          setDeleteError(null)
                          setDeleting(e)
                        }}
                      >
                        <Trash2 className="h-3.5 w-3.5" aria-hidden />
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {deleting && (
        <ConfirmDialog
          title="Delete event?"
          confirmLabel="Delete event"
          busy={deleteBusy}
          error={deleteError}
          onConfirm={confirmDelete}
          onCancel={() => setDeleting(null)}
        >
          "{deleting.title}" on {deleting.date} will be removed and attendees are notified it was cancelled.
        </ConfirmDialog>
      )}
    </div>
  )
}
