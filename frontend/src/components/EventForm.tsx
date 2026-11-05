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

import Sheet from './Sheet'
import {
  buttonPrimary,
  buttonSecondary,
  errorTextClass,
  inputClass,
  labelClass,
  textareaClass,
} from './styles'

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
    <Sheet title={label} onClose={onCancel}>
      <form onSubmit={submit} aria-label={label} className="flex min-h-0 flex-1 flex-col">
        <div className="grid flex-1 content-start gap-4 overflow-y-auto px-5 py-4 sm:grid-cols-2">
          <label className={`${labelClass} sm:col-span-2`}>
            Title
            <input required maxLength={200} className={inputClass} value={form.title} onChange={set('title')} />
          </label>
          <label className={labelClass}>
            Category
            <input required maxLength={60} className={inputClass} value={form.category} onChange={set('category')} />
          </label>
          <label className={labelClass}>
            Venue
            <select required className={inputClass} value={form.venue} onChange={set('venue')}>
              {names.map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </select>
          </label>
          <label className={labelClass}>
            Date
            <input required type="date" className={inputClass} value={form.date} onChange={set('date')} />
          </label>
          <label className={labelClass}>
            Time
            <input required type="time" className={inputClass} value={form.time} onChange={set('time')} />
          </label>
          <label className={labelClass}>
            Capacity
            <input required type="number" min={0} className={inputClass} value={form.capacity} onChange={set('capacity')} />
          </label>
          <label className={labelClass}>
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
          <label className={`${labelClass} sm:col-span-2`}>
            Host
            <input maxLength={120} className={inputClass} value={form.host} onChange={set('host')} />
          </label>
          <label className={`${labelClass} sm:col-span-2`}>
            Description
            <textarea
              maxLength={2000}
              rows={4}
              className={textareaClass}
              value={form.description}
              onChange={set('description')}
            />
          </label>
        </div>

        <div className="border-t border-line px-5 py-3">
          {error && (
            <p role="alert" className={`${errorTextClass} mb-3`}>
              {error}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onCancel} className={buttonSecondary}>
              Cancel
            </button>
            <button type="submit" disabled={busy} className={buttonPrimary}>
              {busy ? 'Saving...' : event ? 'Save changes' : 'Create event'}
            </button>
          </div>
        </div>
      </form>
    </Sheet>
  )
}
