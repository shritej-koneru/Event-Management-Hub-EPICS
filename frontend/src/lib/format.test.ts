import { describe, expect, it } from 'vitest'

import { categoryDotClass, cleanAnnouncement, formatDate, formatDateTime, formatTime, roomLabel } from './format'

describe('formatDate', () => {
  it('formats as weekday day month', () => {
    expect(formatDate('2026-11-06')).toBe('Fri 6 Nov')
    expect(formatDate('2026-01-01')).toBe('Thu 1 Jan')
  })
  it('is a calendar date, not shifted by timezone', () => {
    expect(formatDate('2026-03-29')).toBe('Sun 29 Mar')
  })
  it('returns unparseable input unchanged', () => {
    expect(formatDate('soon')).toBe('soon')
    expect(formatDate('2026-13-40')).toBe('2026-13-40')
  })
})

describe('formatDateTime / formatTime', () => {
  it('uses 24 h local time', () => {
    const d = new Date(2026, 10, 6, 14, 5)
    expect(formatDateTime(d)).toBe('6 Nov, 14:05')
    expect(formatTime(d)).toBe('14:05')
  })
  it('pads midnight', () => {
    expect(formatDateTime(new Date(2026, 0, 2, 0, 0))).toBe('2 Jan, 00:00')
  })
  it('returns empty string for invalid input', () => {
    expect(formatDateTime('nope')).toBe('')
    expect(formatTime('nope')).toBe('')
  })
})

describe('categoryDotClass', () => {
  it('maps known categories and falls back to other', () => {
    expect(categoryDotClass('Tech')).toBe('bg-cat-tech')
    expect(categoryDotClass('Cultural')).toBe('bg-cat-arts')
    expect(categoryDotClass('Sports')).toBe('bg-cat-sports')
    expect(categoryDotClass('Gardening')).toBe('bg-cat-other')
  })
})

describe('cleanAnnouncement', () => {
  it('strips a trailing event id suffix only', () => {
    expect(cleanAnnouncement('New event: Hack Night at Main Hall [evt_mw41abc]')).toBe(
      'New event: Hack Night at Main Hall',
    )
    expect(cleanAnnouncement('Room [A] changed')).toBe('Room [A] changed')
    expect(cleanAnnouncement('Plain text')).toBe('Plain text')
  })
})

describe('roomLabel', () => {
  const names = { 'main-hall': 'Main Hall' }
  it('maps all/empty/global to All attendees', () => {
    expect(roomLabel('all', names)).toBe('All attendees')
    expect(roomLabel(null, names)).toBe('All attendees')
    expect(roomLabel('room-204', names, true)).toBe('All attendees')
  })
  it('uses the venue name, falling back to the id', () => {
    expect(roomLabel('main-hall', names)).toBe('Main Hall')
    expect(roomLabel('room-204', names)).toBe('room-204')
  })
})
