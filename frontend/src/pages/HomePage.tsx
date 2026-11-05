import { Link } from 'react-router-dom'

import { useAuth } from '@/auth/useAuth'
import { PageHeading } from '@/components/Feedback'
import Recommendations from '@/components/Recommendations'

export default function HomePage() {
  const { user } = useAuth()

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      <PageHeading
        title={user ? `Hello, ${user.name}` : 'Welcome to EventHub'}
        subtitle="Campus events, live chat and announcements"
      />
      <Recommendations />
      <Link
        to="/events"
        className="mt-6 flex h-11 items-center justify-center rounded-[14px] border border-white/40 bg-white/16 text-sm font-bold text-white hover:bg-white/25"
      >
        Browse all events
      </Link>
    </div>
  )
}
