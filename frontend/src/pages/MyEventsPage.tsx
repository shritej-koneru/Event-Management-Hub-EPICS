import { Link } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'
import EventCard from '@/components/EventCard'
import { Empty, ErrorNote, Loading, PageHeading } from '@/components/Feedback'
import { useEvents } from '@/lib/useEvents'

export default function MyEventsPage() {
  const { user, bookmarks, loading: authLoading } = useAuth()
  const { events, loading, error } = useEvents('')
  const mine = events.filter((e) => bookmarks.includes(e.id))

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      <PageHeading title="My Events" subtitle="Events you have bookmarked" />
      {authLoading || loading ? (
        <Loading label="Loading your events..." />
      ) : !user ? (
        <p className="text-sm text-white/80">
          <Link to="/login" className="font-bold underline">
            Sign in
          </Link>{' '}
          to see your bookmarked events.
        </p>
      ) : error ? (
        <ErrorNote>{error}</ErrorNote>
      ) : mine.length === 0 ? (
        <Empty>You have not bookmarked any events yet.</Empty>
      ) : (
        <div className="flex flex-col gap-3.5">
          {mine.map((event) => (
            <EventCard key={event.id} event={event} />
          ))}
        </div>
      )}
    </div>
  )
}
