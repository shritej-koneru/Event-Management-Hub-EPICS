import { useState } from 'react'
import type { FormEvent } from 'react'

import { api } from '@/lib/api'
import { draftEventWithAi, useAiEnabled } from '@/lib/ai'
import {
  ACCESSIBILITY_OPTIONS,
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
  const aiEnabled = useAiEnabled()
  const [notes, setNotes] = useState('')
  const [aiBusy, setAiBusy] = useState(false)
  const [aiNote, setAiNote] = useState<string | null>(null)

  /** Fills title/description/tags from the organizer's notes. Never saves; the organizer edits and submits. */
  async function draftWithAi() {
    setAiBusy(true)
    setError(null)
    setAiNote(null)
    try {
      const d = await draftEventWithAi(notes.trim())
      setForm((f) => ({ ...f, title: d.title, description: d.description }))
      setAiNote(`Draft added${d.tags.length ? ` (suggested tags: ${d.tags.join(', ')})` : ''}. Check every detail before saving.`)
    } catch (err) {
      setError(apiErrorMessage(err))
    } finally {
      setAiBusy(false)
    }
  }

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

          {aiEnabled && (
            <div className="rounded-md border border-line p-3 sm:col-span-2" aria-label="AI draft">
              <label className={labelClass}>
                Draft with AI from your notes (optional)
                <textarea
                  maxLength={800}
                  rows={2}
                  className={textareaClass}
                  value={notes}
                  placeholder="e.g. beginner robotics workshop, bring a laptop"
                  onChange={(e) => setNotes(e.target.value)}
                />
              </label>
              <div className="mt-2 flex items-center gap-3">
                <button type="button" className={buttonSecondary} disabled={aiBusy || !notes.trim()} onClick={draftWithAi}>
                  {aiBusy ? 'Drafting...' : 'Draft with AI'}
                </button>
                <p className="text-caption text-ink-3">
                  Fills the title, description and tags only. Review and edit before you save.
                </p>
              </div>
              {aiNote && (
                <p role="status" className="mt-2 text-small text-success">
                  {aiNote}
                </p>
              )}
            </div>
          )}

          <fieldset className="sm:col-span-2">
            <legend className={labelClass}>Price</legend>
            <div className="flex flex-wrap items-center gap-2">
              <select
                aria-label="Price"
                className={inputClass}
                value={form.priceMode}
                onChange={(e) => setForm((f) => ({ ...f, priceMode: e.target.value as EventFormValues['priceMode'] }))}
              >
                <option value="unknown">Not stated</option>
                <option value="free">Free</option>
                <option value="paid">Paid</option>
              </select>
              {form.priceMode === 'paid' && (
                <>
                  <input
                    aria-label="Price amount"
                    type="number"
                    min={0}
                    step="0.01"
                    className={inputClass}
                    value={form.priceAmount}
                    onChange={set('priceAmount')}
                  />
                  <input
                    aria-label="Currency"
                    maxLength={3}
                    className={inputClass}
                    value={form.priceCurrency}
                    onChange={set('priceCurrency')}
                  />
                </>
              )}
            </div>
          </fieldset>

          <fieldset className="sm:col-span-2">
            <legend className={labelClass}>Accessibility (only what you know for sure)</legend>
            <div className="grid gap-2 sm:grid-cols-2">
              {ACCESSIBILITY_OPTIONS.map(({ key, label: text }) => (
                <label key={key} className="flex items-center justify-between gap-2 text-small text-ink-2">
                  {text}
                  <select
                    aria-label={text}
                    className={inputClass}
                    value={form.accessibility[key]}
                    onChange={(e) =>
                      setForm((f) => ({
                        ...f,
                        accessibility: { ...f.accessibility, [key]: e.target.value as 'unknown' | 'yes' | 'no' },
                      }))
                    }
                  >
                    <option value="unknown">Not stated</option>
                    <option value="yes">Yes</option>
                    <option value="no">No</option>
                  </select>
                </label>
              ))}
            </div>
          </fieldset>
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
