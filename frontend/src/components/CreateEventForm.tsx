import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Plus } from 'lucide-react'

import { api, errorMessage } from '@/lib/api'
import type { EventItem, Venue } from '@/lib/types'

import { inputClass, primaryButtonClass } from './styles'

const today = () => new Date().toISOString().slice(0, 10)

/** Organizer/admin only: POST /api/events. */
export default function CreateEventForm({ onCreated }: { onCreated: () => void }) {
  const [open, setOpen] = useState(false)
  const [venues, setVenues] = useState<Venue[]>([])
  const [title, setTitle] = useState('')
  const [category, setCategory] = useState('Tech')
  const [venue, setVenue] = useState('')
  const [date, setDate] = useState(today())
  const [time, setTime] = useState('10:00')
  const [capacity, setCapacity] = useState('50')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!open || venues.length) return
    api<{ venues: Venue[] }>('/navigation/venues')
      .then((r) => {
        setVenues(r.venues)
        setVenue((v) => v || r.venues[0]?.name || '')
      })
      .catch((err: unknown) => setError(errorMessage(err)))
  }, [open, venues.length])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    setDone(null)
    try {
      const res = await api<{ event: EventItem }>('/events', {
        method: 'POST',
        body: { title, category, venue, date, time, capacity: Number(capacity), description },
      })
      setDone(`Created "${res.event.title}" (${res.event.id}).`)
      setTitle('')
      setDescription('')
      onCreated()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="mb-5 flex h-11 w-full cursor-pointer items-center justify-center gap-2 rounded-[14px] border border-white/40 bg-white/16 text-sm font-bold text-white hover:bg-white/25"
      >
        <Plus className="h-4 w-4" aria-hidden />
        Create event
      </button>
    )
  }

  return (
    <form
      onSubmit={submit}
      aria-label="Create event"
      className="mb-5 rounded-[22px] bg-[rgba(255,255,255,0.97)] p-5 text-[#282348] shadow-[0_15px_35px_rgba(20,0,80,0.25)]"
    >
      <h2 className="mb-3 text-lg font-bold text-[#5c3ce8]">Create event</h2>
      <div className="flex flex-col gap-3">
        <label className="text-xs font-semibold">
          Title
          <input required className={inputClass} value={title} onChange={(e) => setTitle(e.target.value)} />
        </label>
        <label className="text-xs font-semibold">
          Category
          <input required className={inputClass} value={category} onChange={(e) => setCategory(e.target.value)} />
        </label>
        <label className="text-xs font-semibold">
          Venue
          <select required className={inputClass} value={venue} onChange={(e) => setVenue(e.target.value)}>
            {venues.map((v) => (
              <option key={v.id} value={v.name}>
                {v.name}
              </option>
            ))}
          </select>
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className="text-xs font-semibold">
            Date
            <input required type="date" className={inputClass} value={date} onChange={(e) => setDate(e.target.value)} />
          </label>
          <label className="text-xs font-semibold">
            Time
            <input required type="time" className={inputClass} value={time} onChange={(e) => setTime(e.target.value)} />
          </label>
        </div>
        <label className="text-xs font-semibold">
          Capacity
          <input
            required
            type="number"
            min={0}
            className={inputClass}
            value={capacity}
            onChange={(e) => setCapacity(e.target.value)}
          />
        </label>
        <label className="text-xs font-semibold">
          Description
          <textarea
            className={`${inputClass} h-20 py-2`}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </label>
      </div>

      {error && (
        <p role="alert" className="mt-3 text-sm text-red-600">
          {error}
        </p>
      )}
      {done && (
        <p role="status" className="mt-3 text-sm text-green-700">
          {done}
        </p>
      )}

      <div className="mt-4 flex gap-3">
        <button type="submit" disabled={busy} className={primaryButtonClass}>
          {busy ? 'CREATING...' : 'CREATE EVENT'}
        </button>
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="h-[50px] cursor-pointer rounded-[15px] border border-[#ddd8ef] bg-white px-5 text-sm font-bold text-[#5c3ce8]"
        >
          Close
        </button>
      </div>
    </form>
  )
}
