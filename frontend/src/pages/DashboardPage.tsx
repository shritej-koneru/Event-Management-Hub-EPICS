import { Link } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'
import { Empty, ErrorNote, Loading, PageHeading } from '@/components/Feedback'
import { cardClass } from '@/components/styles'
import { api } from '@/lib/api'
import { isUpcoming } from '@/lib/manage'
import type { ManagedAnnouncement, ManagedEvent, Overview } from '@/lib/types'
import { useFetch } from '@/lib/useFetch'

export default function DashboardPage() {
  const { user } = useAuth()
  // The server's overview is system-wide for admins and per-organizer otherwise,
  // so the lists below use the same scope and the labels say which one it is.
  const scope = user?.role === 'admin' ? 'all' : 'mine'
  const suffix = scope === 'all' ? ' (all managers)' : ''
  const overview = useFetch((signal) => api<Overview>('/manage/overview', { signal }))
  const events = useFetch((signal) =>
    api<{ events: ManagedEvent[] }>('/manage/events', { query: { scope }, signal }),
    scope,
  )
  const announcements = useFetch((signal) =>
    api<{ announcements: ManagedAnnouncement[] }>('/manage/announcements', {
      query: { scope },
      signal,
    }),
    scope,
  )

  const o = overview.data
  const cards = [
    { label: 'Upcoming events' + suffix, value: o?.events.upcoming },
    { label: 'Past events' + suffix, value: o?.events.past },
    { label: 'Total events' + suffix, value: o?.events.total },
    { label: 'Bookmarks' + suffix, value: o?.bookmarks },
    { label: 'Announcements' + suffix, value: o?.announcements },
    { label: 'Venues', value: o?.venues },
    { label: 'Paths', value: o?.edges },
  ]
  const upcoming = (events.data?.events ?? []).filter((e) => isUpcoming(e)).slice(0, 5)
  const recent = (announcements.data?.announcements ?? []).slice(0, 5)

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      <PageHeading title="Dashboard" subtitle={user ? `Welcome back, ${user.name}` : undefined} />

      {overview.error && <ErrorNote>{overview.error}</ErrorNote>}
      <dl className="mb-6 grid grid-cols-2 gap-2.5 sm:grid-cols-4">
        {cards.map((c) => (
          <div key={c.label} className="rounded-[18px] bg-[rgba(255,255,255,0.97)] p-3 text-center text-[#25204c]">
            <dd className="text-2xl font-bold">{c.value ?? '-'}</dd>
            <dt className="text-[11px] font-semibold text-[#77758b]">{c.label}</dt>
          </div>
        ))}
      </dl>

      <div className="grid gap-5 lg:grid-cols-2">
        <section aria-labelledby="dash-upcoming" className={cardClass}>
          <div className="mb-3 flex items-center justify-between">
            <h2 id="dash-upcoming" className="text-lg font-bold text-[#5c3ce8]">
              Next upcoming events
            </h2>
            <Link to="/events" className="text-xs font-bold text-[#5c3ce8] underline">
              All events
            </Link>
          </div>
          {events.error && <ErrorNote>{events.error}</ErrorNote>}
          {events.loading && <Loading />}
          {!events.loading && upcoming.length === 0 && !events.error && (
            <p className="py-4 text-sm text-[#77758b]">No upcoming events.</p>
          )}
          <ul className="divide-y divide-[#eee9fb]">
            {upcoming.map((e) => (
              <li key={e.id} className="py-2.5 text-sm">
                <p className="font-semibold">{e.title}</p>
                <p className="text-xs text-[#77758b]">
                  {e.date} {e.time} - {e.venue} - {e.registered}/{e.capacity} registered
                </p>
              </li>
            ))}
          </ul>
        </section>

        <section aria-labelledby="dash-ann" className={cardClass}>
          <div className="mb-3 flex items-center justify-between">
            <h2 id="dash-ann" className="text-lg font-bold text-[#5c3ce8]">
              Recent announcements
            </h2>
            <Link to="/announcements" className="text-xs font-bold text-[#5c3ce8] underline">
              Announcements
            </Link>
          </div>
          {announcements.error && <ErrorNote>{announcements.error}</ErrorNote>}
          {announcements.loading && <Loading />}
          {!announcements.loading && recent.length === 0 && !announcements.error && (
            <Empty>Nothing sent yet.</Empty>
          )}
          <ul className="divide-y divide-[#eee9fb]">
            {recent.map((a) => (
              <li key={a.id} className="py-2.5 text-sm">
                <p>{a.message}</p>
                <p className="text-xs text-[#77758b]">
                  {new Date(a.at).toLocaleString()} - {a.room}
                </p>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </div>
  )
}
