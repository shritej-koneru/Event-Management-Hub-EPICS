import { useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, MapPin, QrCode, Sparkles, Ticket } from 'lucide-react'

const HELP_CATEGORIES = [
  {
    id: 'registration',
    label: 'REGISTRATION',
    icon: Ticket,
    iconBg: 'bg-[#e4ddff]',
    iconColor: 'text-[#6145ef]',
  },
  {
    id: 'navigation',
    label: 'NAVIGATION',
    icon: MapPin,
    iconBg: 'bg-[#d8fff1]',
    iconColor: 'text-[#00a878]',
  },
  {
    id: 'qr-checkin',
    label: 'QR CHECK-IN',
    icon: QrCode,
    iconBg: 'bg-[#fff0d2]',
    iconColor: 'text-[#f29b21]',
  },
]

export default function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>()
  const navigate = useNavigate()

  const eventName = eventId?.replace(/-/g, ' ').toUpperCase() ?? 'EVENT'

  return (
    <div className="px-[18px] pb-[150px] pt-5">
      <button
        type="button"
        onClick={() => navigate('/events')}
        className="mb-[25px] flex h-[45px] w-[45px] cursor-pointer items-center justify-center rounded-[15px] border-none bg-white/16 text-white backdrop-blur-[10px] transition-all hover:bg-white/28 hover:-translate-x-[3px]"
      >
        <ArrowLeft className="h-6 w-6" aria-hidden />
      </button>

      <h1 className="mb-2 text-[30px] font-bold text-white">
        {eventName}
      </h1>

      <p className="mb-[30px] text-sm leading-[1.5] text-white/70">
        Quick access to everything you need for this event.
      </p>

      <h2 className="mb-4 text-[21px] font-bold text-white">
        Quick Help Categories
      </h2>

      <div className="grid grid-cols-3 gap-2.5">
        {HELP_CATEGORIES.map((cat) => (
          <button
            key={cat.id}
            type="button"
            onClick={() =>
              navigate(`/events/${eventId}/category/${cat.id}`)
            }
            className="flex min-h-[135px] cursor-pointer flex-col items-center justify-center gap-3 rounded-[21px] bg-[rgba(255,255,255,0.97)] p-2.5 text-[#25204c] shadow-[0_12px_25px_rgba(20,0,80,0.2)] transition-all hover:-translate-y-[6px] hover:shadow-[0_18px_30px_rgba(20,0,80,0.3)] active:scale-[0.95]"
          >
            <div
              className={`flex h-[58px] w-[58px] items-center justify-center rounded-[18px] ${cat.iconBg}`}
            >
              <cat.icon className={`h-[28px] w-[28px] ${cat.iconColor}`} aria-hidden />
            </div>
            <span className="text-[10px] font-bold leading-tight text-center">
              {cat.label}
            </span>
          </button>
        ))}
      </div>

      {/* Info box */}
      <div className="mt-6 rounded-[18px] bg-gradient-to-br from-[#6847f5] to-[#9b4dff] p-[18px] text-white shadow-[0_10px_20px_rgba(90,50,220,0.3)]">
        <div className="flex items-center gap-2 text-sm font-bold">
          <Sparkles className="h-4 w-4" aria-hidden />
          Event Ready
        </div>
        <p className="mt-[7px] text-[13px] leading-[1.5] text-white/90">
          Everything you need for a smooth event experience is available here.
        </p>
      </div>

      {/* Register button - bottom left */}
      <div className="fixed bottom-[100px] left-4 z-[90] max-w-[444px] lg:left-auto lg:left-[calc(50%-240px+18px)]">
        <button
          type="button"
          onClick={() => navigate(`/events/${eventId}/register`)}
          className="flex h-[52px] w-[180px] cursor-pointer items-center justify-center gap-2 rounded-[17px] border-none bg-gradient-to-r from-[#ff5f6d] to-[#ff9966] text-[15px] font-bold text-white shadow-[0_10px_25px_rgba(255,75,90,0.4)] transition-all hover:-translate-y-[4px] hover:shadow-[0_15px_30px_rgba(255,75,90,0.5)] active:scale-[0.96]"
        >
          <Ticket className="h-4 w-4" aria-hidden />
          REGISTER NOW
        </button>
      </div>
    </div>
  )
}
