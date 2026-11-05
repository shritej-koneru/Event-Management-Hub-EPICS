import { describe, expect, it } from 'vitest'

import { ApiError } from './api'
import {
  CANVAS_H,
  CANVAS_W,
  apiErrorMessage,
  canvasToFraction,
  emptyEventForm,
  euclideanUnits,
  eventFormToPayload,
  eventToForm,
  fractionToCanvas,
  isUpcoming,
  isValidSlug,
  prefillDistance,
  slugify,
  validateEventForm,
} from './manage'
import type { EventItem } from './types'

describe('slug', () => {
  it('accepts lowercase slugs', () => {
    for (const s of ['a', 'main-hall', 'room-204', 'x1-y2-z3']) expect(isValidSlug(s)).toBe(true)
  })
  it('rejects bad slugs', () => {
    for (const s of ['', 'Main', 'a b', '-a', 'a-', 'a--b', 'a_b', 'a'.repeat(41)]) expect(isValidSlug(s)).toBe(false)
    expect(isValidSlug('a'.repeat(40))).toBe(true)
  })
  it('slugifies names into valid slugs', () => {
    expect(slugify('Main Hall 2')).toBe('main-hall-2')
    expect(slugify('  Cafe & Bar!! ')).toBe('cafe-bar')
    expect(isValidSlug(slugify('A'.repeat(60) + ' x'))).toBe(true)
  })
})

describe('canvas <-> fraction', () => {
  it('round-trips', () => {
    expect(canvasToFraction(fractionToCanvas(0.25, CANVAS_W), CANVAS_W)).toBeCloseTo(0.25)
    expect(fractionToCanvas(0.5, CANVAS_H)).toBe(300)
  })
  it('clamps out-of-range positions', () => {
    expect(canvasToFraction(-10, CANVAS_W)).toBe(0)
    expect(canvasToFraction(CANVAS_W * 2, CANVAS_W)).toBe(1)
    expect(canvasToFraction(5, 0)).toBe(0)
  })
})

describe('distance prefill', () => {
  it('is Euclidean with x scaled by the 4:3 aspect', () => {
    expect(euclideanUnits({ x: 0, y: 0 }, { x: 0, y: 1 })).toBeCloseTo(1)
    expect(euclideanUnits({ x: 0, y: 0 }, { x: 0.75, y: 0 })).toBeCloseTo(1)
  })
  it('multiplies by metres per unit and clamps to 1..1000', () => {
    expect(prefillDistance({ x: 0, y: 0 }, { x: 0, y: 0.5 })).toBe(100)
    expect(prefillDistance({ x: 0, y: 0 }, { x: 0, y: 0.5 }, 50)).toBe(25)
    expect(prefillDistance({ x: 0.1, y: 0.1 }, { x: 0.1, y: 0.1 })).toBe(1)
    expect(prefillDistance({ x: 0, y: 0 }, { x: 1, y: 1 }, 5000)).toBe(1000)
  })
})

const event: EventItem = {
  id: 'e1',
  title: 'Hack Night',
  category: 'Tech',
  venue: 'Main Hall',
  venueId: 'main-hall',
  date: '2030-01-02',
  time: '18:30',
  durationMin: 90,
  capacity: 120,
  registered: 10,
  host: 'CS Club',
  description: 'Build things',
  tags: [],
  icon: 'x',
}

describe('event form', () => {
  it('maps an event to form values and back to a payload', () => {
    const form = eventToForm(event)
    expect(form.capacity).toBe('120')
    expect(eventFormToPayload(form)).toEqual({
      title: 'Hack Night',
      category: 'Tech',
      venue: 'Main Hall',
      date: '2030-01-02',
      time: '18:30',
      durationMin: 90,
      capacity: 120,
      host: 'CS Club',
      description: 'Build things',
    })
  })
  it('trims text and coerces numbers', () => {
    const p = eventFormToPayload({ ...emptyEventForm(), title: '  A ', category: ' B', venue: 'V ', capacity: '7' })
    expect(p.title).toBe('A')
    expect(p.category).toBe('B')
    expect(p.capacity).toBe(7)
  })
  it('validates required fields', () => {
    const ok = { ...eventToForm(event) }
    expect(validateEventForm(ok)).toBeNull()
    expect(validateEventForm({ ...ok, title: ' ' })).toMatch(/title/i)
    expect(validateEventForm({ ...ok, date: '' })).toMatch(/date/i)
    expect(validateEventForm({ ...ok, capacity: '-1' })).toMatch(/capacity/i)
    expect(validateEventForm({ ...ok, durationMin: '0' })).toMatch(/duration/i)
  })
  it('classifies upcoming vs past', () => {
    const now = new Date('2030-01-02T12:00:00')
    expect(isUpcoming(event, now)).toBe(true)
    expect(isUpcoming({ date: '2030-01-01', time: '10:00' }, now)).toBe(false)
  })
})

describe('apiErrorMessage', () => {
  it('maps known codes', () => {
    expect(apiErrorMessage(new ApiError(400, 'PAST_DATE', 'x'))).toMatch(/past/i)
    expect(apiErrorMessage(new ApiError(409, 'DUPLICATE_EVENT', 'x'))).toMatch(/already exists/i)
    expect(apiErrorMessage(new ApiError(409, 'VENUE_IN_USE', 'x'))).toMatch(/used by/i)
    expect(apiErrorMessage(new ApiError(429, 'RATE_LIMITED', 'x'))).toMatch(/too many/i)
  })
  it('falls back by status, then server message', () => {
    expect(apiErrorMessage(new ApiError(403, 'FORBIDDEN', 'nope'))).toMatch(/permission/i)
    expect(apiErrorMessage(new ApiError(429, 'HTTP_429', 'slow'))).toMatch(/too many/i)
    expect(apiErrorMessage(new ApiError(400, 'VALIDATION_ERROR', 'bad field'))).toBe('bad field')
  })
  it('handles plain errors and unknowns', () => {
    expect(apiErrorMessage(new Error('boom'))).toBe('boom')
    expect(apiErrorMessage(42)).toBe('Something went wrong')
  })
})
