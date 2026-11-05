import { useState } from 'react'
import { Search } from 'lucide-react'

import { useAuth } from '@/auth/useAuth'
import CreateEventForm from '@/components/CreateEventForm'
import EventCard from '@/components/EventCard'
import { Empty, ErrorNote, Loading } from '@/components/Feedback'
import { useEvents } from '@/lib/useEvents'

export default function EventsPage() {
  const [query, setQuery] = useState('')
  const { canManageEvents } = useAuth()
  const { events, loading, error, reload } = useEvents(query)

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      {/* Search bar */}
      <div className="flex h-[54px] items-center rounded-[18px] border border-white/25 bg-white/16 px-4 backdrop-blur-[12px] transition-all focus-within:border-white/50 focus-within:bg-white/23 focus-within:shadow-[0_0_0_3px_rgba(255,255,255,0.08)]">
        <Search className="mr-2.5 h-[21px] w-[21px] text-white" aria-hidden />
        <input
          type="search"
          aria-label="Search events"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search events..."
          className="w-full bg-transparent text-[15px] text-white outline-none placeholder:text-white/70"
        />
      </div>

      <h2 className="mb-1.5 mt-5 text-[25px] font-bold text-white">EVENTS</h2>
      <p className="mb-5 text-[13px] text-white/70">
        Discover and manage your upcoming events
      </p>

      {canManageEvents && <CreateEventForm onCreated={reload} />}

      <div className="flex flex-col gap-3.5">
        {loading && <Loading label="Loading events..." />}
        {error && <ErrorNote>{error}</ErrorNote>}
        {!loading && !error && events.map((event) => <EventCard key={event.id} event={event} />)}
        {!loading && !error && events.length === 0 && (
          <Empty>{query.trim() ? 'No events match your search.' : 'No events found.'}</Empty>
        )}
      </div>
    </div>
  )
}
