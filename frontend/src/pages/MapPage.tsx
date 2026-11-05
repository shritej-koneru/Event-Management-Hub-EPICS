import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Plus, Save, Trash2 } from 'lucide-react'

import ConfirmDialog from '@/components/Dialog'
import { ErrorNote, Loading, PageHeading } from '@/components/Feedback'
import MapCanvas from '@/components/MapCanvas'
import {
  cardClass,
  compactPrimaryClass,
  dangerButtonClass,
  inputClass,
  smallButtonClass,
} from '@/components/styles'
import { api } from '@/lib/api'
import {
  DEFAULT_METRES_PER_UNIT,
  SLUG_MAX,
  apiErrorMessage,
  clamp01,
  isValidSlug,
  prefillDistance,
  roundFraction,
  slugify,
} from '@/lib/manage'
import type { CampusMap, MapEdge, MapVenue } from '@/lib/types'
import { useFetch } from '@/lib/useFetch'
import { useSocket } from '@/realtime/context'

interface VenueDraft {
  id: string
  name: string
  x: string
  y: string
}
interface EdgeDraft {
  from: string
  to: string
  distance: string
}
interface NewVenueDraft extends VenueDraft {
  idTouched: boolean
}
interface NewPathDraft extends EdgeDraft {
  distTouched: boolean
}

type Mode =
  | { kind: 'none' }
  | { kind: 'venue'; draft: VenueDraft }
  | { kind: 'edge'; draft: EdgeDraft }
  | { kind: 'addVenue'; draft: NewVenueDraft }
  | { kind: 'addPath'; draft: NewPathDraft }

type Pending = { kind: 'venue'; venue: MapVenue } | { kind: 'edge'; edge: MapEdge }

const toVenueDraft = (v: MapVenue): VenueDraft => ({ id: v.id, name: v.name, x: String(v.x), y: String(v.y) })
const sameEdge = (a: { from: string; to: string }, b: { from: string; to: string }) =>
  (a.from === b.from && a.to === b.to) || (a.from === b.to && a.to === b.from)
const num = (s: string) => (s.trim() === '' ? NaN : Number(s))

export default function MapPage() {
  const { socket } = useSocket()
  const map = useFetch((signal) => api<CampusMap>('/navigation/map', { signal }))
  const reload = map.reload
  const [mode, setMode] = useState<Mode>({ kind: 'none' })
  const [metresPerUnit, setMetresPerUnit] = useState(String(DEFAULT_METRES_PER_UNIT))
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [pending, setPending] = useState<Pending | null>(null)
  const [pendingError, setPendingError] = useState<string | null>(null)

  // Another manager changed the map: refresh.
  useEffect(() => {
    if (!socket) return
    socket.on('map:updated', reload)
    return () => {
      socket.off('map:updated', reload)
    }
  }, [socket, reload])

  const venues = map.data?.venues ?? []
  const edges = map.data?.edges ?? []
  const nameOf = (id: string) => venues.find((v) => v.id === id)?.name ?? id
  const mpu = Number(metresPerUnit) > 0 ? Number(metresPerUnit) : DEFAULT_METRES_PER_UNIT

  // Venues as drawn: the selected one follows its draft position while editing.
  const drawn: MapVenue[] = venues.map((v) => {
    if (mode.kind === 'venue' && mode.draft.id === v.id) {
      return { ...v, x: clamp01(num(mode.draft.x) || 0), y: clamp01(num(mode.draft.y) || 0) }
    }
    return v
  })

  const ghost =
    mode.kind === 'addVenue' && mode.draft.x !== '' && mode.draft.y !== ''
      ? { name: mode.draft.name, x: clamp01(num(mode.draft.x)), y: clamp01(num(mode.draft.y)) }
      : null

  function start(next: Mode) {
    setMode(next)
    setError(null)
    setNotice(null)
  }
  const selectVenue = (id: string) => {
    const v = venues.find((x) => x.id === id)
    if (v) start({ kind: 'venue', draft: toVenueDraft(v) })
  }
  const selectEdge = (e: MapEdge) =>
    start({ kind: 'edge', draft: { from: e.from, to: e.to, distance: String(e.distanceM) } })

  async function run(action: () => Promise<void>, done: string, after?: () => void) {
    setBusy(true)
    setError(null)
    try {
      await action()
      setNotice(done)
      after?.()
      reload()
    } catch (err) {
      setError(apiErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  function saveVenue(e: FormEvent) {
    e.preventDefault()
    if (mode.kind !== 'venue') return
    const { id, name, x, y } = mode.draft
    const nx = num(x)
    const ny = num(y)
    if (!name.trim() || name.trim().length > 60) return setError('Name must be 1-60 characters.')
    if (!(nx >= 0 && nx <= 1 && ny >= 0 && ny <= 1)) return setError('X and Y must be between 0 and 1.')
    void run(
      () => api(`/manage/venues/${encodeURIComponent(id)}`, { method: 'PUT', body: { name: name.trim(), x: nx, y: ny } }),
      `Saved ${name.trim()}.`,
    )
  }

  function createVenue(e: FormEvent) {
    e.preventDefault()
    if (mode.kind !== 'addVenue') return
    const { id, name, x, y } = mode.draft
    const nx = num(x)
    const ny = num(y)
    if (!isValidSlug(id)) return setError(`Id must be a lowercase slug (a-z, 0-9, hyphens), max ${SLUG_MAX} chars.`)
    if (!name.trim() || name.trim().length > 60) return setError('Name must be 1-60 characters.')
    if (!(nx >= 0 && nx <= 1 && ny >= 0 && ny <= 1))
      return setError('Click the map (or type X and Y between 0 and 1) to place the venue.')
    void run(
      () => api('/manage/venues', { method: 'POST', body: { id, name: name.trim(), x: nx, y: ny } }),
      `Added ${name.trim()}.`,
      () => setMode({ kind: 'venue', draft: { id, name: name.trim(), x: String(nx), y: String(ny) } }),
    )
  }

  function saveEdge(e: FormEvent) {
    e.preventDefault()
    if (mode.kind !== 'edge') return
    const d = num(mode.draft.distance)
    if (!Number.isInteger(d) || d < 1 || d > 1000) return setError('Distance must be a whole number, 1-1000 metres.')
    const { from, to } = mode.draft
    void run(() => api('/manage/edges', { method: 'PUT', body: { from, to, distanceM: d } }), 'Path updated.')
  }

  function createPath(e: FormEvent) {
    e.preventDefault()
    if (mode.kind !== 'addPath') return
    const { from, to, distance } = mode.draft
    const d = num(distance)
    if (!from || !to) return setError('Pick two venues.')
    if (from === to) return setError('A path needs two different venues.')
    if (!Number.isInteger(d) || d < 1 || d > 1000) return setError('Distance must be a whole number, 1-1000 metres.')
    void run(
      () => api('/manage/edges', { method: 'POST', body: { from, to, distanceM: d } }),
      'Path added.',
      () => setMode({ kind: 'none' }),
    )
  }

  async function confirmDelete() {
    if (!pending) return
    setBusy(true)
    setPendingError(null)
    try {
      if (pending.kind === 'venue') {
        await api(`/manage/venues/${encodeURIComponent(pending.venue.id)}`, { method: 'DELETE' })
        setNotice(`Deleted ${pending.venue.name}.`)
      } else {
        await api('/manage/edges', { method: 'DELETE', query: { from: pending.edge.from, to: pending.edge.to } })
        setNotice('Path deleted.')
      }
      setPending(null)
      setMode({ kind: 'none' })
      reload()
    } catch (err) {
      setPendingError(apiErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  function updatePathDraft(patch: Partial<NewPathDraft>) {
    if (mode.kind !== 'addPath') return
    const next = { ...mode.draft, ...patch }
    const a = venues.find((v) => v.id === next.from)
    const b = venues.find((v) => v.id === next.to)
    if (!next.distTouched && a && b && a.id !== b.id) next.distance = String(prefillDistance(a, b, mpu))
    setMode({ kind: 'addPath', draft: next })
  }

  const selectedVenueId = mode.kind === 'venue' ? mode.draft.id : null
  const selectedEdge = mode.kind === 'edge' ? mode.draft : null
  const label = 'block text-xs font-semibold'

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <PageHeading title="Map editor" subtitle="Venues and walking paths shared with the mobile app" />
        <div className="flex gap-2">
          <button
            type="button"
            className={compactPrimaryClass}
            onClick={() =>
              start({ kind: 'addVenue', draft: { id: '', name: '', x: '', y: '', idTouched: false } })
            }
          >
            <Plus className="h-4 w-4" aria-hidden />
            Add venue
          </button>
          <button
            type="button"
            className={compactPrimaryClass}
            disabled={venues.length < 2}
            onClick={() =>
              start({
                kind: 'addPath',
                draft: { from: venues[0]?.id ?? '', to: '', distance: '', distTouched: false },
              })
            }
          >
            <Plus className="h-4 w-4" aria-hidden />
            Add path
          </button>
        </div>
      </div>

      <p className="mb-4 rounded-[14px] bg-white/15 px-4 py-3 text-xs text-white">
        Venue ids are permanent and shared with the mobile app and event data. They cannot be changed after a venue is
        created. Names and positions can be edited at any time.
      </p>

      {map.error && <ErrorNote>{map.error}</ErrorNote>}
      {notice && (
        <p role="status" className="mb-3 rounded-[14px] bg-green-500/25 px-4 py-3 text-sm text-white">
          {notice}
        </p>
      )}
      {map.loading && !map.data && <Loading label="Loading map..." />}

      {map.data && (
        <div className="grid gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(280px,1fr)]">
          <div className={cardClass}>
            <MapCanvas
              venues={drawn}
              edges={edges}
              selectedVenueId={selectedVenueId}
              selectedEdge={selectedEdge}
              ghost={ghost}
              placing={mode.kind === 'addVenue'}
              onSelectVenue={selectVenue}
              onSelectEdge={selectEdge}
              onMoveVenue={(id, x, y) =>
                setMode((m) =>
                  m.kind === 'venue' && m.draft.id === id
                    ? { kind: 'venue', draft: { ...m.draft, x: String(x), y: String(y) } }
                    : m,
                )
              }
              onPlace={(x, y) =>
                setMode((m) =>
                  m.kind === 'addVenue' ? { kind: 'addVenue', draft: { ...m.draft, x: String(x), y: String(y) } } : m,
                )
              }
            />
            <p className="mt-2 text-xs text-[#77758b]">
              {mode.kind === 'addVenue'
                ? 'Click the map to place the new venue.'
                : 'Select a venue or path. Drag a venue to move it, then save.'}
            </p>
          </div>

          <div className={cardClass} aria-live="polite">
            {error && (
              <p role="alert" className="mb-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
                {error}
              </p>
            )}

            {mode.kind === 'none' && (
              <p className="text-sm text-[#77758b]">
                Nothing selected. Choose a venue or path on the map or from the lists below.
              </p>
            )}

            {mode.kind === 'venue' && (
              <form onSubmit={saveVenue} aria-label="Edit venue" className="flex flex-col gap-3">
                <h2 className="text-lg font-bold text-[#5c3ce8]">Edit venue</h2>
                <p className="text-xs text-[#77758b]">
                  Id: <code>{mode.draft.id}</code> (permanent)
                </p>
                <label className={label}>
                  Name
                  <input
                    required
                    maxLength={60}
                    className={inputClass}
                    value={mode.draft.name}
                    onChange={(e) => setMode({ kind: 'venue', draft: { ...mode.draft, name: e.target.value } })}
                  />
                </label>
                <div className="grid grid-cols-2 gap-3">
                  <label className={label}>
                    X (0-1)
                    <input
                      required
                      type="number"
                      min={0}
                      max={1}
                      step={0.001}
                      className={inputClass}
                      value={mode.draft.x}
                      onChange={(e) => setMode({ kind: 'venue', draft: { ...mode.draft, x: e.target.value } })}
                    />
                  </label>
                  <label className={label}>
                    Y (0-1)
                    <input
                      required
                      type="number"
                      min={0}
                      max={1}
                      step={0.001}
                      className={inputClass}
                      value={mode.draft.y}
                      onChange={(e) => setMode({ kind: 'venue', draft: { ...mode.draft, y: e.target.value } })}
                    />
                  </label>
                </div>
                <div className="flex flex-wrap gap-2">
                  <button type="submit" disabled={busy} className={compactPrimaryClass}>
                    <Save className="h-4 w-4" aria-hidden />
                    Save venue
                  </button>
                  <button
                    type="button"
                    className={dangerButtonClass}
                    onClick={() => {
                      const v = venues.find((x) => x.id === mode.draft.id)
                      if (v) {
                        setPendingError(null)
                        setPending({ kind: 'venue', venue: v })
                      }
                    }}
                  >
                    <Trash2 className="h-3.5 w-3.5" aria-hidden />
                    Delete venue
                  </button>
                </div>
              </form>
            )}

            {mode.kind === 'addVenue' && (
              <form onSubmit={createVenue} aria-label="Add venue" className="flex flex-col gap-3">
                <h2 className="text-lg font-bold text-[#5c3ce8]">Add venue</h2>
                <label className={label}>
                  Name
                  <input
                    required
                    maxLength={60}
                    className={inputClass}
                    value={mode.draft.name}
                    onChange={(e) => {
                      const name = e.target.value
                      setMode({
                        kind: 'addVenue',
                        draft: { ...mode.draft, name, id: mode.draft.idTouched ? mode.draft.id : slugify(name) },
                      })
                    }}
                  />
                </label>
                <label className={label}>
                  Id (permanent slug)
                  <input
                    required
                    maxLength={SLUG_MAX}
                    className={inputClass}
                    value={mode.draft.id}
                    aria-invalid={mode.draft.id !== '' && !isValidSlug(mode.draft.id)}
                    onChange={(e) =>
                      setMode({ kind: 'addVenue', draft: { ...mode.draft, id: e.target.value, idTouched: true } })
                    }
                  />
                  <span className="mt-1 block font-normal text-[#77758b]">
                    Lowercase letters, digits and hyphens, e.g. <code>main-hall</code>. Cannot be changed later.
                  </span>
                </label>
                <div className="grid grid-cols-2 gap-3">
                  <label className={label}>
                    X (0-1)
                    <input
                      type="number"
                      min={0}
                      max={1}
                      step={0.001}
                      className={inputClass}
                      value={mode.draft.x}
                      onChange={(e) => setMode({ kind: 'addVenue', draft: { ...mode.draft, x: e.target.value } })}
                    />
                  </label>
                  <label className={label}>
                    Y (0-1)
                    <input
                      type="number"
                      min={0}
                      max={1}
                      step={0.001}
                      className={inputClass}
                      value={mode.draft.y}
                      onChange={(e) => setMode({ kind: 'addVenue', draft: { ...mode.draft, y: e.target.value } })}
                    />
                  </label>
                </div>
                <div className="flex gap-2">
                  <button type="submit" disabled={busy} className={compactPrimaryClass}>
                    <Plus className="h-4 w-4" aria-hidden />
                    Create venue
                  </button>
                  <button type="button" className={smallButtonClass} onClick={() => start({ kind: 'none' })}>
                    Cancel
                  </button>
                </div>
              </form>
            )}

            {mode.kind === 'edge' && (
              <form onSubmit={saveEdge} aria-label="Edit path" className="flex flex-col gap-3">
                <h2 className="text-lg font-bold text-[#5c3ce8]">Edit path</h2>
                <p className="text-sm">
                  {nameOf(mode.draft.from)} &harr; {nameOf(mode.draft.to)}
                </p>
                <label className={label}>
                  Distance (metres)
                  <input
                    required
                    type="number"
                    min={1}
                    max={1000}
                    step={1}
                    className={inputClass}
                    value={mode.draft.distance}
                    onChange={(e) => setMode({ kind: 'edge', draft: { ...mode.draft, distance: e.target.value } })}
                  />
                </label>
                <div className="flex flex-wrap gap-2">
                  <button type="submit" disabled={busy} className={compactPrimaryClass}>
                    <Save className="h-4 w-4" aria-hidden />
                    Save path
                  </button>
                  <button
                    type="button"
                    className={dangerButtonClass}
                    onClick={() => {
                      const d = mode.draft
                      const edge = edges.find((x) => sameEdge(x, d))
                      if (edge) {
                        setPendingError(null)
                        setPending({ kind: 'edge', edge })
                      }
                    }}
                  >
                    <Trash2 className="h-3.5 w-3.5" aria-hidden />
                    Delete path
                  </button>
                </div>
              </form>
            )}

            {mode.kind === 'addPath' && (
              <form onSubmit={createPath} aria-label="Add path" className="flex flex-col gap-3">
                <h2 className="text-lg font-bold text-[#5c3ce8]">Add path</h2>
                <label className={label}>
                  From venue
                  <select
                    className={inputClass}
                    value={mode.draft.from}
                    onChange={(e) => updatePathDraft({ from: e.target.value })}
                  >
                    {venues.map((v) => (
                      <option key={v.id} value={v.id}>
                        {v.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label className={label}>
                  To venue
                  <select
                    className={inputClass}
                    value={mode.draft.to}
                    onChange={(e) => updatePathDraft({ to: e.target.value })}
                  >
                    <option value="">Select a venue</option>
                    {venues
                      .filter((v) => v.id !== mode.draft.from)
                      .map((v) => (
                        <option key={v.id} value={v.id}>
                          {v.name}
                        </option>
                      ))}
                  </select>
                </label>
                <label className={label}>
                  Metres per canvas unit
                  <input
                    type="number"
                    min={1}
                    className={inputClass}
                    value={metresPerUnit}
                    onChange={(e) => setMetresPerUnit(e.target.value)}
                  />
                  <span className="mt-1 block font-normal text-[#77758b]">
                    Used to suggest a distance (1 unit = map height). Default {DEFAULT_METRES_PER_UNIT}.
                  </span>
                </label>
                <label className={label}>
                  Distance (metres)
                  <input
                    required
                    type="number"
                    min={1}
                    max={1000}
                    step={1}
                    className={inputClass}
                    value={mode.draft.distance}
                    onChange={(e) => updatePathDraft({ distance: e.target.value, distTouched: true })}
                  />
                </label>
                <div className="flex gap-2">
                  <button type="submit" disabled={busy} className={compactPrimaryClass}>
                    <Plus className="h-4 w-4" aria-hidden />
                    Create path
                  </button>
                  <button type="button" className={smallButtonClass} onClick={() => start({ kind: 'none' })}>
                    Cancel
                  </button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}

      {map.data && (
        <div className="mt-5 grid gap-5 md:grid-cols-2">
          <section aria-labelledby="venue-list" className={cardClass}>
            <h2 id="venue-list" className="mb-2 text-lg font-bold text-[#5c3ce8]">
              Venues ({venues.length})
            </h2>
            <ul className="divide-y divide-[#eee9fb]">
              {venues.map((v) => (
                <li key={v.id} className="flex items-center justify-between gap-2 py-2 text-sm">
                  <span>
                    {v.name}
                    <span className="block text-xs text-[#77758b]">
                      {v.id} ({roundFraction(v.x)}, {roundFraction(v.y)})
                    </span>
                  </span>
                  <button
                    type="button"
                    className={smallButtonClass}
                    aria-label={`Edit venue ${v.name}`}
                    onClick={() => selectVenue(v.id)}
                  >
                    Edit
                  </button>
                </li>
              ))}
            </ul>
          </section>
          <section aria-labelledby="edge-list" className={cardClass}>
            <h2 id="edge-list" className="mb-2 text-lg font-bold text-[#5c3ce8]">
              Paths ({edges.length})
            </h2>
            <ul className="divide-y divide-[#eee9fb]">
              {edges.map((e) => (
                <li key={`${e.from}|${e.to}`} className="flex items-center justify-between gap-2 py-2 text-sm">
                  <span>
                    {nameOf(e.from)} &harr; {nameOf(e.to)}
                    <span className="block text-xs text-[#77758b]">{e.distanceM} m</span>
                  </span>
                  <button
                    type="button"
                    className={smallButtonClass}
                    aria-label={`Edit path ${nameOf(e.from)} to ${nameOf(e.to)}`}
                    onClick={() => selectEdge(e)}
                  >
                    Edit
                  </button>
                </li>
              ))}
            </ul>
          </section>
        </div>
      )}

      {pending && (
        <ConfirmDialog
          title={pending.kind === 'venue' ? 'Delete venue?' : 'Delete path?'}
          confirmLabel={pending.kind === 'venue' ? 'Delete venue' : 'Delete path'}
          busy={busy}
          error={pendingError}
          onConfirm={confirmDelete}
          onCancel={() => setPending(null)}
        >
          {pending.kind === 'venue'
            ? `"${pending.venue.name}" and all paths connected to it will be removed. This cannot be undone.`
            : `The path between ${nameOf(pending.edge.from)} and ${nameOf(pending.edge.to)} will be removed.`}
        </ConfirmDialog>
      )}
    </div>
  )
}
