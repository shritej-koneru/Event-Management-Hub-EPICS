import { useAuth } from '@/auth/useAuth'
import { PageHeading } from '@/components/Feedback'
import Recommendations from '@/components/Recommendations'
import { useEvents } from '@/lib/useEvents'

export default function DashboardPage() {
  const { user, bookmarks } = useAuth()
  const { events, loading } = useEvents('')

  const stats = [
    { label: 'Events', value: loading ? '-' : String(events.length) },
    { label: 'Saved', value: user ? String(bookmarks.length) : '-' },
    { label: 'Interests', value: user ? String(user.interests.length) : '-' },
  ]

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      <PageHeading title="Dashboard" subtitle="Your activity at a glance" />
      <dl className="mb-6 grid grid-cols-3 gap-2.5">
        {stats.map((s) => (
          <div
            key={s.label}
            className="rounded-[18px] bg-[rgba(255,255,255,0.97)] p-3 text-center text-[#25204c]"
          >
            <dd className="text-2xl font-bold">{s.value}</dd>
            <dt className="text-[11px] font-semibold text-[#77758b]">{s.label}</dt>
          </div>
        ))}
      </dl>
      <Recommendations />
    </div>
  )
}
