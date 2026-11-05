import { ChevronRight } from 'lucide-react'
import { Link } from 'react-router-dom'

import type { EventItem } from '@/lib/types'

export default function EventCard({ event, hint }: { event: EventItem; hint?: string }) {
  return (
    <Link
      to={`/events/${event.id}`}
      className="group relative flex min-h-[105px] w-full items-center overflow-hidden rounded-[22px] bg-gradient-to-br from-white to-[#f4f0ff] p-[17px] shadow-[0_12px_30px_rgba(25,0,90,0.25)] transition-all hover:-translate-y-[5px] hover:shadow-[0_18px_35px_rgba(20,0,80,0.35)] active:scale-[0.97]"
    >
      <div className="pointer-events-none absolute -right-10 -top-10 h-[100px] w-[100px] rounded-full bg-[rgba(100,70,240,0.08)]" />

      <div
        aria-hidden
        className="relative z-[2] mr-[15px] flex h-[58px] w-[58px] min-w-[58px] items-center justify-center rounded-[18px] bg-gradient-to-br from-[#6045f5] to-[#9a51ff] text-[26px] shadow-[0_8px_18px_rgba(95,60,240,0.35)]"
      >
        {event.icon || '🎫'}
      </div>

      <div className="relative z-[2] min-w-0 flex-1 text-left">
        <div className="mb-[5px] truncate text-[17px] font-bold text-[#20204a]">{event.title}</div>
        <div className="text-[12px] text-[#77758b]">
          {event.date} {event.time} · {event.venue}
        </div>
        <div className="text-[12px] text-[#77758b]">{hint ?? event.category}</div>
      </div>

      <ChevronRight
        className="relative z-[2] h-[30px] w-[30px] flex-shrink-0 text-[#6849ec]"
        aria-hidden
      />
    </Link>
  )
}
