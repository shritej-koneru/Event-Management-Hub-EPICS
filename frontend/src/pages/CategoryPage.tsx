import { useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Heart } from 'lucide-react'

const CATEGORY_LABELS: Record<string, string> = {
  registration: 'REGISTRATION',
  navigation: 'NAVIGATION',
  'qr-checkin': 'QR CHECK-IN',
}

export default function CategoryPage() {
  const { eventId, categoryId } = useParams<{
    eventId: string
    categoryId: string
  }>()
  const navigate = useNavigate()

  const eventName = eventId?.replace(/-/g, ' ').toUpperCase() ?? 'EVENT'
  const categoryLabel =
    CATEGORY_LABELS[categoryId ?? ''] ??
    categoryId?.toUpperCase().replace(/-/g, ' ') ??
    'CATEGORY'

  return (
    <div className="px-[18px] pb-[110px] pt-5">
      <button
        type="button"
        onClick={() => navigate(`/events/${eventId}`)}
        className="mb-[25px] flex h-[45px] w-[45px] cursor-pointer items-center justify-center rounded-[15px] border-none bg-white/16 text-white backdrop-blur-[10px] transition-all hover:bg-white/28 hover:-translate-x-[3px]"
      >
        <ArrowLeft className="h-6 w-6" aria-hidden />
      </button>

      <h1 className="mb-5 text-[30px] font-bold text-white">
        {categoryLabel}
      </h1>

      <div className="rounded-[23px] bg-[rgba(255,255,255,0.97)] p-[23px] text-[#44405e] shadow-[0_15px_30px_rgba(20,0,80,0.25)] leading-[1.7] min-h-[200px]">
        <p>
          {categoryLabel} information for {eventName} will be displayed here.
        </p>

        <div className="mt-5 rounded-[18px] bg-gradient-to-br from-[#6847f5] to-[#9b4dff] p-[18px] text-white shadow-[0_10px_20px_rgba(90,50,220,0.3)]">
          <div className="flex items-center gap-2 text-sm font-bold">
            <Heart className="h-4 w-4" aria-hidden />
            Event Information
          </div>
          <p className="mt-2 text-[13px] leading-[1.5] text-white/90">
            Stay updated and enjoy your event experience.
          </p>
        </div>
      </div>
    </div>
  )
}
