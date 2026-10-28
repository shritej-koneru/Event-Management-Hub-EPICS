import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Ticket } from 'lucide-react'

export default function RegistrationPage() {
  const { eventId } = useParams<{ eventId: string }>()
  const navigate = useNavigate()

  const eventName = eventId?.replace(/-/g, ' ').toUpperCase() ?? 'EVENT'

  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()

    if (!name.trim() || !email.trim() || !phone.trim()) {
      alert('Please fill all the details.')
      return
    }

    alert(
      `Registration successful for ${eventName}!\n\nThank you, ${name}.`
    )

    setName('')
    setEmail('')
    setPhone('')
    navigate(`/events/${eventId}`)
  }

  return (
    <div className="px-[18px] pb-[110px] pt-5">
      <button
        type="button"
        onClick={() => navigate(`/events/${eventId}`)}
        className="mb-[25px] flex h-[45px] w-[45px] cursor-pointer items-center justify-center rounded-[15px] border-none bg-white/16 text-white backdrop-blur-[10px] transition-all hover:bg-white/28 hover:-translate-x-[3px]"
      >
        <ArrowLeft className="h-6 w-6" aria-hidden />
      </button>

      <form
        onSubmit={handleSubmit}
        className="rounded-[24px] bg-[rgba(255,255,255,0.97)] p-6 text-[#282348] shadow-[0_15px_35px_rgba(20,0,80,0.25)]"
      >
        <h2 className="mb-2.5 flex items-center gap-2 text-xl font-bold text-[#5c3ce8]">
          <Ticket className="h-5 w-5" aria-hidden />
          Register for Event
        </h2>

        <p className="mb-5 text-[14px] leading-[1.6] text-[#66627a]">
          You are registering for {eventName}. Please enter your details below.
        </p>

        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Enter your name"
          className="mb-3 h-12 w-full rounded-[13px] border border-[#ddd8ef] px-3.5 text-[14px] text-gray-900 outline-none placeholder:text-gray-400"
        />

        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="Enter your email"
          className="mb-3 h-12 w-full rounded-[13px] border border-[#ddd8ef] px-3.5 text-[14px] text-gray-900 outline-none placeholder:text-gray-400"
        />

        <input
          type="tel"
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
          placeholder="Enter your phone number"
          className="mb-5 h-12 w-full rounded-[13px] border border-[#ddd8ef] px-3.5 text-[14px] text-gray-900 outline-none placeholder:text-gray-400"
        />

        <button
          type="submit"
          className="h-[50px] w-full cursor-pointer rounded-[15px] border-none bg-gradient-to-br from-[#6145ef] to-[#9a51ff] text-[15px] font-bold text-white transition-all hover:opacity-90 active:scale-[0.98]"
        >
          CONFIRM REGISTRATION
        </button>
      </form>
    </div>
  )
}
