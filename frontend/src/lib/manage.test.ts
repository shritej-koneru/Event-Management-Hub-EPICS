import { describe, expect, it } from 'vitest'

import { ApiError } from './api'
import {
  CANVAS_H,
  CANVAS_W,
  apiErrorMessage,
  isInsideBounds,
  latLngToXY,
  newPlaceToPayload,
  padBounds,
  validateNewPlace,
  xyToLatLng,
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
      price: null, // unknown stays unknown
      accessibility: {},
    })
  })
  it('sends only explicitly stated price and accessibility', () => {
    const base = eventToForm(event)
    const form = {
      ...base,
      priceMode: 'paid' as const,
      priceAmount: '250.50',
      priceCurrency: 'inr',
      accessibility: { ...base.accessibility, stepFree: 'yes' as const, captions: 'no' as const },
    }
    const p = eventFormToPayload(form)
    expect(p.price).toEqual({ type: 'paid', amountCents: 25050, currency: 'INR' })
    expect(p.accessibility).toEqual({ stepFree: true, captions: false })
    expect(eventFormToPayload({ ...form, priceMode: 'free' }).price).toEqual({ type: 'free' })
    expect(validateEventForm({ ...form, priceAmount: '0' })).toMatch(/price/i)
    expect(validateEventForm({ ...form, priceCurrency: 'RUPEES' })).toMatch(/currency/i)
  })
  it('round-trips a stored price and accessibility into the form', () => {
    const form = eventToForm({ ...event, price: { type: 'free' }, accessibility: { stepFree: true, hearingLoop: false } })
    expect(form.priceMode).toBe('free')
    expect(form.accessibility).toMatchObject({ stepFree: 'yes', hearingLoop: 'no', captions: 'unknown' })
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
    const now = new Date('2030-01-02T12:00:00Z')
    expect(isUpcoming(event, now)).toBe(true)
    expect(isUpcoming({ date: '2030-01-02' }, now)).toBe(true) // today counts, like the server
    expect(isUpcoming({ date: '2030-01-01' }, now)).toBe(false)
    // 00:30 in India on 3 Jan is still 2 Jan in UTC: the server's day wins
    expect(isUpcoming({ date: '2030-01-02' }, new Date('2030-01-03T00:30:00+05:30'))).toBe(true)
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

describe('geo projection (contract 1.5.0)', () => {
  const b = { north: 16.487077, south: 16.483477, west: 80.69028, east: 80.69528 }

  it('maps corners and centre to the unit square', () => {
    expect(latLngToXY(b.north, b.west, b)).toEqual({ x: 0, y: 0 })
    const se = latLngToXY(b.south, b.east, b)
    expect(se.x).toBeCloseTo(1, 9)
    expect(se.y).toBeCloseTo(1, 9)
    const c = latLngToXY(16.485277, 80.69278, b)
    expect(c.x).toBeCloseTo(0.5, 6)
    expect(c.y).toBeCloseTo(0.5, 6)
  })

  it('follows the spec formulas exactly', () => {
    const { x, y } = latLngToXY(16.4861, 80.6931, b)
    expect(x).toBeCloseTo((80.6931 - b.west) / (b.east - b.west), 12)
    expect(y).toBeCloseTo((b.north - 16.4861) / (b.north - b.south), 12)
  })

  it('round-trips and uses the supplied bounds, not constants', () => {
    const other = { north: 1, south: 0, west: 10, east: 12 }
    const ll = xyToLatLng(0.25, 0.75, other)
    expect(ll).toEqual({ lat: 0.25, lng: 10.5 })
    const back = latLngToXY(ll.lat, ll.lng, other)
    expect(back.x).toBeCloseTo(0.25, 12)
    expect(back.y).toBeCloseTo(0.75, 12)
  })

  it('detects inside / outside (edges inclusive)', () => {
    expect(isInsideBounds(16.485, 80.692, b)).toBe(true)
    expect(isInsideBounds(b.north, b.west, b)).toBe(true)
    expect(isInsideBounds(b.north + 0.0001, 80.692, b)).toBe(false)
    expect(isInsideBounds(16.485, b.east + 0.0001, b)).toBe(false)
  })

  it('padBounds is slightly larger than bounds', () => {
    const [[s, w], [n, e]] = padBounds(b)
    expect(s).toBeLessThan(b.south)
    expect(w).toBeLessThan(b.west)
    expect(n).toBeGreaterThan(b.north)
    expect(e).toBeGreaterThan(b.east)
  })
})

describe('new place form', () => {
  const b = { north: 16.487077, south: 16.483477, west: 80.69028, east: 80.69528 }
  const base = {
    name: '  Main Hall 2 ',
    id: slugify('Main Hall 2'),
    kind: ' Hall ',
    description: ' Big ',
    lat: 16.4855,
    lng: 80.6925,
    connectToNearest: true,
  }

  it('slugs the name', () => {
    expect(base.id).toBe('main-hall-2')
    expect(slugify('  Café & Bar!! ')).toBe('caf-bar')
  })

  it('builds the POST payload with lat/lng', () => {
    expect(newPlaceToPayload(base)).toEqual({
      id: 'main-hall-2',
      name: 'Main Hall 2',
      lat: 16.4855,
      lng: 80.6925,
      kind: 'Hall',
      description: 'Big',
      connectToNearest: true,
    })
    expect(newPlaceToPayload({ ...base, connectToNearest: false }).connectToNearest).toBe(false)
  })

  it('validates', () => {
    expect(validateNewPlace(base, b)).toBeNull()
    expect(validateNewPlace({ ...base, name: ' ' }, b)).toMatch(/name/i)
    expect(validateNewPlace({ ...base, id: 'Bad Id' }, b)).toMatch(/slug/i)
    expect(validateNewPlace({ ...base, lat: 17 }, b)).toBe('Outside campus area.')
    expect(validateNewPlace({ ...base, description: 'x'.repeat(501) }, b)).toMatch(/500/)
  })

  it('maps new error codes', () => {
    expect(apiErrorMessage(new ApiError(400, 'OUTSIDE_CAMPUS', 'x'))).toMatch(/outside campus/i)
    expect(apiErrorMessage(new ApiError(400, 'CANNOT_DEMOTE_SELF', 'x'))).toMatch(/own role/i)
    expect(apiErrorMessage(new ApiError(409, 'EMAIL_TAKEN', 'x'))).toMatch(/email/i)
  })
})
