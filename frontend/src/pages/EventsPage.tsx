import { useState } from 'react'
import { Pencil, Plus, Trash2 } from 'lucide-react'

import { useAuth } from '@/auth/useAuth'
import ConfirmDialog from '@/components/Dialog'
import EventForm from '@/components/EventForm'
import { Empty, ErrorNote, Loading, SuccessNote } from '@/components/Feedback'
import PageContainer from '@/components/layout/PageContainer'
import {
  buttonIcon,
  buttonIconDanger,
  buttonPrimary,
  buttonQuiet,
  chipClass,
  inputClass,
  labelClass,
  panelClass,
  tableClass,
  tdClass,
  tdRight,
  theadClass,
  thClass,
  thRight,
  trClass,
} from '@/components/styles'
import { api } from '@/lib/api'
import { categoryDotClass, formatDate } from '@/lib/format'
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
    <PageContainer
      title="Events"
      description={isAdmin ? 'Manage your events or all events' : 'Manage your events'}
      actions={
        <button type="button" className={buttonPrimary} onClick={() => { setNotice(null); setEditing('new') }}>
          <Plus className="h-4 w-4" strokeWidth={1.75} aria-hidden />
          Create event
        </button>
      }
    >
      {notice && <SuccessNote>{notice}</SuccessNote>}

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
        <label className={`${labelClass} min-w-48 flex-1`}>
          Search events
          <input
            type="search"
            placeholder="Title, venue, category, host"
            className={inputClass}
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
        </label>
        <label className={labelClass}>
          When
          <select className={`${inputClass} w-auto`} value={status} onChange={(e) => setStatus(e.target.value as Status)}>
            <option value="all">All</option>
            <option value="upcoming">Upcoming</option>
            <option value="past">Past</option>
          </select>
        </label>
        {isAdmin && (
          <label className={labelClass}>
            Show
            <select className={`${inputClass} w-auto`} value={scope} onChange={(e) => setScope(e.target.value as Scope)}>
              <option value="mine">Mine</option>
              <option value="all">All events</option>
            </select>
          </label>
        )}
      </div>

      {events.error && <ErrorNote>{events.error}</ErrorNote>}
      {events.loading && <Loading />}
      {!events.loading && !events.error && rows.length === 0 && (
        <div className={panelClass}>
          <Empty
            action={
              !q && status === 'all' ? (
                <button type="button" className={buttonQuiet} onClick={() => setEditing('new')}>
                  Create the first event
                </button>
              ) : undefined
            }
          >
            No events match.
          </Empty>
        </div>
      )}

      {rows.length > 0 && (
        <div className={`${panelClass} relative overflow-x-auto`}>
          <table className={`${tableClass} min-w-[860px]`}>
            <caption className="sr-only">Events</caption>
            <thead className={theadClass}>
              <tr>
                <th scope="col" className={thClass}>Event</th>
                <th scope="col" className={thClass}>Category</th>
                <th scope="col" className={thClass}>Date</th>
                <th scope="col" className={thClass}>Venue</th>
                <th scope="col" className={thRight}>Capacity</th>
                <th scope="col" className={thRight}>Registered</th>
                <th scope="col" className={thRight}>Bookmarks</th>
                <th scope="col" className={thRight}><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.id} className={trClass}>
                  <td className={`${tdClass} font-medium`}>{e.title}</td>
                  <td className={`${tdClass} whitespace-nowrap`}>
                    <span className="flex items-center gap-2 text-ink-2">
                      <span aria-hidden className={`h-2 w-2 shrink-0 rounded-full ${categoryDotClass(e.category)}`} />
                      {e.category}
                    </span>
                  </td>
                  <td className={`${tdClass} whitespace-nowrap`}>
                    {formatDate(e.date)} <span className="text-ink-2">{e.time}</span>
                    {!isUpcoming(e) && <span className={`${chipClass()} ml-2`}>Past</span>}
                  </td>
                  <td className={tdClass}>{e.venue}</td>
                  <td className={tdRight}>{e.capacity}</td>
                  <td className={tdRight}>{e.registered}</td>
                  <td className={tdRight}>{e.bookmarkCount}</td>
                  <td className={tdClass}>
                    <div className="flex justify-end gap-1 opacity-0 transition-opacity duration-150 ease-out focus-within:opacity-100 group-hover:opacity-100 pointer-coarse:opacity-100">
                      <button
                        type="button"
                        className={buttonIcon}
                        aria-label={`Edit ${e.title}`}
                        onClick={() => {
                          setNotice(null)
                          setEditing(e)
                        }}
                      >
                        <Pencil className="h-4 w-4" strokeWidth={1.75} aria-hidden />
                      </button>
                      <button
                        type="button"
                        className={buttonIconDanger}
                        aria-label={`Delete ${e.title}`}
                        onClick={() => {
                          setDeleteError(null)
                          setDeleting(e)
                        }}
                      >
                        <Trash2 className="h-4 w-4" strokeWidth={1.75} aria-hidden />
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
          "{deleting.title}" on {formatDate(deleting.date)} will be removed and attendees are notified it was cancelled.
        </ConfirmDialog>
      )}
    </PageContainer>
  )
}
