import { useState } from 'react'
import type { FormEvent } from 'react'

import { api } from '@/lib/api'
import {
  apiErrorMessage,
  emptyEventForm,
  eventFormToPayload,
  eventToForm,
  validateEventForm,
} from '@/lib/manage'
import type { EventFormValues } from '@/lib/manage'
import type { EventItem, MapVenue } from '@/lib/types'

import { compactPrimaryClass, inputClass, smallButtonClass } from './styles'

interface EventFormProps {
  /** When set, the form edits this event (PUT); otherwise it creates (POST). */
  event?: EventItem
  venues: MapVenue[]
  onSaved: () => void
  onCancel: () => void
}

/** Create / edit event form. Server errors (PAST_DATE, DUPLICATE_EVENT, 403...) are shown inline. */
export default function EventForm({ event, venues, onSaved, onCancel }: EventFormProps) {
  const [form, setForm] = useState<EventFormValues>(() =>
    event ? eventToForm(event) : { ...emptyEventForm(), venue: venues[0]?.name ?? '' },
  )
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const set = (key: keyof EventFormValues) => (e: { target: { value: string } }) =>
    setForm((f) => ({ ...f, [key]: e.target.value }))

  async function submit(e: FormEvent) {
    e.preventDefault()
    const problem = validateEventForm(form)
    if (problem) {
      setError(problem)
      return
    }
    setBusy(true)
    setError(null)
    try {
      const body = eventFormToPayload(form)
      if (event) await api(`/events/${encodeURIComponent(event.id)}`, { method: 'PUT', body })
      else await api('/events', { method: 'POST', body })
      onSaved()
    } catch (err) {
      setError(apiErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const label = event ? 'Edit event' : 'Create event'
  // Keep a venue that no longer exists on the map selectable when editing.
  const names = venues.map((v) => v.name)
  if (form.venue && !names.includes(form.venue)) names.unshift(form.venue)

  return (
    <form
      onSubmit={submit}
      aria-label={label}
      className="mb-5 rounded-[22px] bg-[rgba(255,255,255,0.97)] p-5 text-[#282348] shadow-[0_15px_35px_rgba(20,0,80,0.25)]"
    >
      <h2 className="mb-3 text-lg font-bold text-[#5c3ce8]">{label}</h2>
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="text-xs font-semibold sm:col-span-2">
          Title
          <input required maxLength={200} className={inputClass} value={form.title} onChange={set('title')} />
        </label>
        <label className="text-xs font-semibold">
          Category
          <input required maxLength={60} className={inputClass} value={form.category} onChange={set('category')} />
        </label>
        <label className="text-xs font-semibold">
          Venue
          <select required className={inputClass} value={form.venue} onChange={set('venue')}>
            {names.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs font-semibold">
          Date
          <input required type="date" className={inputClass} value={form.date} onChange={set('date')} />
        </label>
        <label className="text-xs font-semibold">
          Time
          <input required type="time" className={inputClass} value={form.time} onChange={set('time')} />
        </label>
        <label className="text-xs font-semibold">
          Capacity
          <input required type="number" min={0} className={inputClass} value={form.capacity} onChange={set('capacity')} />
        </label>
        <label className="text-xs font-semibold">
          Duration (minutes)
          <input
            required
            type="number"
            min={1}
            max={1440}
            className={inputClass}
            value={form.durationMin}
            onChange={set('durationMin')}
          />
        </label>
        <label className="text-xs font-semibold sm:col-span-2">
          Host
          <input maxLength={120} className={inputClass} value={form.host} onChange={set('host')} />
        </label>
        <label className="text-xs font-semibold sm:col-span-2">
          Description
          <textarea
            maxLength={2000}
            className={`${inputClass} h-24 py-2`}
            value={form.description}
            onChange={set('description')}
          />
        </label>
      </div>

      {error && (
        <p role="alert" className="mt-3 text-sm text-red-600">
          {error}
        </p>
      )}

      <div className="mt-4 flex gap-3">
        <button type="submit" disabled={busy} className={compactPrimaryClass}>
          {busy ? 'Saving...' : event ? 'Save changes' : 'Create event'}
        </button>
        <button type="button" onClick={onCancel} className={smallButtonClass}>
          Cancel
        </button>
      </div>
    </form>
  )
}
