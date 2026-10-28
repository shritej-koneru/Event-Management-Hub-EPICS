import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ChevronRight, Mic, PartyPopper, Search, Ticket } from 'lucide-react'

const EVENTS = [
  {
    id: 1,
    name: 'EVENT-1',
    slug: 'event-1',
    subtitle: 'View event details',
    icon: Ticket,
    iconGradient: 'from-[#6045f5] to-[#9a51ff]',
    iconShadow: 'shadow-[0_8px_18px_rgba(95,60,240,0.35)]',
  },
  {
    id: 2,
    name: 'EVENT-2',
    slug: 'event-2',
    subtitle: 'View event details',
    icon: Mic,
    iconGradient: 'from-[#00b894] to-[#00cec9]',
    iconShadow: 'shadow-[0_8px_18px_rgba(0,190,160,0.35)]',
  },
  {
    id: 3,
    name: 'EVENT-3',
    slug: 'event-3',
    subtitle: 'View event details',
    icon: PartyPopper,
    iconGradient: 'from-[#ff6363] to-[#ff9a55]',
    iconShadow: 'shadow-[0_8px_18px_rgba(255,90,80,0.35)]',
  },
]

export default function EventsPage() {
  const [query, setQuery] = useState('')
  const navigate = useNavigate()

  const filtered = useMemo(() => {
    const q = query.toLowerCase()
    return EVENTS.filter((e) => e.name.toLowerCase().includes(q))
  }, [query])

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      {/* Search bar */}
      <div className="flex h-[54px] items-center rounded-[18px] border border-white/25 bg-white/16 px-4 backdrop-blur-[12px] transition-all focus-within:border-white/50 focus-within:bg-white/23 focus-within:shadow-[0_0_0_3px_rgba(255,255,255,0.08)]">
        <Search className="mr-2.5 h-[21px] w-[21px] text-white" aria-hidden />
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search events..."
          className="w-full bg-transparent text-[15px] text-white outline-none placeholder:text-white/70"
        />
      </div>

      {/* Events heading */}
      <h2 className="mb-1.5 mt-5 text-[25px] font-bold text-white">
        EVENTS
      </h2>
      <p className="mb-5 text-[13px] text-white/70">
        Discover and manage your upcoming events
      </p>

      {/* Events list */}
      <div className="flex flex-col gap-3.5">
        {filtered.map((event) => (
          <button
            key={event.id}
            type="button"
            onClick={() => navigate(`/events/${event.slug}`)}
            className="group relative flex min-h-[105px] w-full cursor-pointer items-center overflow-hidden rounded-[22px] bg-gradient-to-br from-white to-[#f4f0ff] p-[17px] shadow-[0_12px_30px_rgba(25,0,90,0.25)] transition-all hover:-translate-y-[5px] hover:shadow-[0_18px_35px_rgba(20,0,80,0.35)] active:scale-[0.97]"
          >
            {/* Decorative circle */}
            <div className="pointer-events-none absolute -right-10 -top-10 h-[100px] w-[100px] rounded-full bg-[rgba(100,70,240,0.08)]" />

            <div
              className={`relative z-[2] mr-[15px] flex h-[58px] w-[58px] min-w-[58px] items-center justify-center rounded-[18px] bg-gradient-to-br ${event.iconGradient} ${event.iconShadow} text-white`}
            >
              <event.icon className="h-[27px] w-[27px]" aria-hidden />
            </div>

            <div className="relative z-[2] flex-1 text-left">
              <div className="mb-[5px] text-[17px] font-bold text-[#20204a]">
                {event.name}
              </div>
              <div className="text-[12px] text-[#77758b]">
                {event.subtitle}
              </div>
            </div>

            <ChevronRight
              className="relative z-[2] h-[30px] w-[30px] flex-shrink-0 text-[#6849ec]"
              aria-hidden
            />
          </button>
        ))}

        {filtered.length === 0 && (
          <p className="py-10 text-center text-sm text-white/50">
            No events found.
          </p>
        )}
      </div>
    </div>
  )
}
