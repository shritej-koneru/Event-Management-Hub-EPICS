import { Link } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'
import { Empty, ErrorNote, Loading } from '@/components/Feedback'
import PageContainer from '@/components/layout/PageContainer'
import {
  captionClass,
  linkClass,
  panelClass,
  panelHeader,
  panelTitle,
  tableClass,
  tdClass,
  tdRight,
  theadClass,
  thClass,
  thRight,
  trClass,
} from '@/components/styles'
import { api } from '@/lib/api'
import { categoryDotClass, cleanAnnouncement, formatDate, formatDateTime, roomLabel } from '@/lib/format'
import { isUpcoming } from '@/lib/manage'
import { buildSearch, relativeTime, sortTickets, totalOpen } from '@/lib/support'
import type {
  ManagedAnnouncement,
  ManagedEvent,
  Overview,
  SupportEventSummary,
  SupportTicket,
} from '@/lib/types'
import { useFetch } from '@/lib/useFetch'
import { useVenueNames } from '@/lib/useVenueNames'

interface SupportSnapshot {
  open: number
  /** Open chats whose last message came from the attendee, oldest waiting first. */
  needsReply: SupportTicket[]
}

/** Open count from the support events endpoint plus the open chats still waiting on a manager. */
async function loadSupport(signal: AbortSignal): Promise<SupportSnapshot> {
  const { events } = await api<{ events: SupportEventSummary[] }>('/manage/support/events', { signal })
  const busy = events
    .filter((e) => e.openCount > 0)
    .sort((a, b) => Date.parse(b.lastMessageAt ?? '') - Date.parse(a.lastMessageAt ?? ''))
    .slice(0, 6)
  const lists = await Promise.all(
    busy.map((e) =>
      api<{ tickets: SupportTicket[] }>('/manage/support/tickets', {
        query: { eventId: e.id, status: 'open' },
        signal,
      }).then((r) => r.tickets),
    ),
  )
  const needsReply = sortTickets(lists.flat().filter((t) => t.lastMessage?.senderRole === 'user')).reverse()
  return { open: totalOpen(events), needsReply }
}

/** Local clock check for "live now": today's event whose start..start+duration contains the current time. */
function isLive(e: ManagedEvent, now: Date): boolean {
  const start = new Date(`${e.date}T${e.time}`)
  if (Number.isNaN(start.getTime())) return false
  const end = start.getTime() + e.durationMin * 60000
  return now.getTime() >= start.getTime() && now.getTime() < end
}

function Stat({ label, value, delta }: { label: string; value: number | string | undefined; delta?: string }) {
  return (
    <div className="border-b border-r border-line px-4 py-3">
      <dt className={captionClass}>{label}</dt>
      <dd className="mt-0.5 text-stat font-semibold tabular-nums text-ink">{value ?? '-'}</dd>
      {delta && <p className="text-caption font-medium text-success">{delta}</p>}
    </div>
  )
}

export default function DashboardPage() {
  const { user } = useAuth()
  // The server's overview is system-wide for admins and per-organizer otherwise,
  // so the lists below use the same scope; the page header states it once.
  const isAdmin = user?.role === 'admin'
  const scope = isAdmin ? 'all' : 'mine'
  const overview = useFetch((signal) => api<Overview>('/manage/overview', { signal }))
  const events = useFetch(
    (signal) => api<{ events: ManagedEvent[] }>('/manage/events', { query: { scope }, signal }),
    scope,
  )
  const announcements = useFetch(
    (signal) =>
      api<{ announcements: ManagedAnnouncement[] }>('/manage/announcements', {
        query: { scope },
        signal,
      }),
    scope,
  )
  const support = useFetch(loadSupport)
  const venueNames = useVenueNames()

  const o = overview.data
  const now = new Date()
  const today = now.toISOString().slice(0, 10)
  const all = events.data?.events
  const todays = all?.filter((e) => e.date === today)
  const liveNow = todays?.filter((e) => isLive(e, now)).length ?? 0
  const upcoming = (all ?? []).filter((e) => isUpcoming(e)).slice(0, 5)
  const recent = (announcements.data?.announcements ?? []).slice(0, 5)
  const needsReply = (support.data?.needsReply ?? []).slice(0, 5)

  return (
    <PageContainer title="Dashboard" description={isAdmin ? 'All managers' : 'Your events'}>
      {overview.error && <ErrorNote>{overview.error}</ErrorNote>}
      <div className="mb-6 overflow-hidden rounded-card border border-line bg-surface">
        <dl aria-label="Summary" className="-mb-px -mr-px grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6">
        <Stat label="Upcoming events" value={o?.events.upcoming} />
        <Stat
          label="Today"
          value={todays?.length}
          delta={liveNow > 0 ? `${liveNow} live now` : undefined}
        />
        <Stat label="Open help chats" value={support.data?.open} />
        <Stat label="Announcements" value={o?.announcements} />
        <Stat label="Places" value={o?.venues} />
        <Stat label="Paths" value={o?.edges} />
        </dl>
      </div>

      <div className="mb-6 grid grid-cols-1 gap-6 lg:grid-cols-2">
        <section aria-labelledby="dash-upcoming" className={`${panelClass} min-w-0`}>
          <div className={panelHeader}>
            <h2 id="dash-upcoming" className={panelTitle}>
              Next events
            </h2>
            <Link to="/events" className={linkClass}>
              All events
            </Link>
          </div>
          {events.error && (
            <div className="p-4 pb-0">
              <ErrorNote>{events.error}</ErrorNote>
            </div>
          )}
          {events.loading && <Loading />}
          {!events.loading && upcoming.length === 0 && !events.error && <Empty>No upcoming events.</Empty>}
          {upcoming.length > 0 && (
            <div className="relative overflow-x-auto">
              <table className={tableClass}>
                <caption className="sr-only">Next events</caption>
                <thead className={theadClass}>
                  <tr>
                    <th scope="col" className={thClass}>Event</th>
                    <th scope="col" className={thClass}>When</th>
                    <th scope="col" className={thRight}>Registered</th>
                  </tr>
                </thead>
                <tbody>
                  {upcoming.map((e) => (
                    <tr key={e.id} className={trClass}>
                      <td className={`${tdClass} font-medium`}>
                        <span className="flex items-center gap-2">
                          <span aria-hidden className={`h-2 w-2 shrink-0 rounded-full ${categoryDotClass(e.category)}`} />
                          {e.title}
                        </span>
                      </td>
                      <td className={`${tdClass} whitespace-nowrap`}>
                        {formatDate(e.date)} <span className="text-ink-2">{e.time}</span>
                      </td>
                      <td className={`${tdRight} whitespace-nowrap`}>
                        {e.registered} / {e.capacity}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section aria-labelledby="dash-reply" className={`${panelClass} min-w-0`}>
          <div className={panelHeader}>
            <h2 id="dash-reply" className={panelTitle}>
              Needs a reply
            </h2>
            <Link to="/help-desk" className={linkClass}>
              Help desk
            </Link>
          </div>
          {support.error && (
            <div className="p-4 pb-0">
              <ErrorNote>{support.error}</ErrorNote>
            </div>
          )}
          {support.loading && <Loading />}
          {!support.loading && !support.error && needsReply.length === 0 && (
            <Empty>No chats are waiting for a reply.</Empty>
          )}
          <ul>
            {needsReply.map((t) => (
              <li key={t.id} className="border-t border-line first:border-t-0">
                <Link
                  to={{
                    pathname: '/help-desk',
                    search: buildSearch({ event: t.eventId, ticket: String(t.id) }).toString(),
                  }}
                  className="block px-4 py-2.5 text-ink hover:bg-paper hover:text-ink"
                >
                  <span className="flex items-baseline justify-between gap-3">
                    <span className="truncate text-body font-medium">{t.userName}</span>
                    <span className="shrink-0 text-caption font-medium text-warning">
                      waiting {relativeTime(t.lastMessageAt)}
                    </span>
                  </span>
                  <span className="block truncate text-small text-ink-2">{t.lastMessage?.body}</span>
                  <span className="block truncate text-caption font-medium text-ink-3">{t.eventTitle}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      </div>

      <section aria-labelledby="dash-ann" className={panelClass}>
        <div className={panelHeader}>
          <h2 id="dash-ann" className={panelTitle}>
            Recent announcements
          </h2>
          <Link to="/announcements" className={linkClass}>
            Announcements
          </Link>
        </div>
        {announcements.error && (
          <div className="p-4 pb-0">
            <ErrorNote>{announcements.error}</ErrorNote>
          </div>
        )}
        {announcements.loading && <Loading />}
        {!announcements.loading && recent.length === 0 && !announcements.error && (
          <Empty
            action={
              <Link to="/announcements" className={linkClass}>
                Send an announcement
              </Link>
            }
          >
            Nothing sent yet.
          </Empty>
        )}
        <ul>
          {recent.map((a) => (
            <li key={a.id} className="border-t border-line px-4 py-3 first:border-t-0">
              <p className="text-body">{cleanAnnouncement(a.message)}</p>
              <p className="text-caption font-medium text-ink-3">
                {formatDateTime(a.at)} · {roomLabel(a.room, venueNames, a.global)}
              </p>
            </li>
          ))}
        </ul>
      </section>
    </PageContainer>
  )
}
