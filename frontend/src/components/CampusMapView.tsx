import { useState } from 'react'
import type { FormEvent } from 'react'
import { divIcon } from 'leaflet'
import type { LatLngBoundsExpression } from 'leaflet'
import { MapContainer, Marker, Polyline, Rectangle, TileLayer, Tooltip, useMapEvents } from 'react-leaflet'
import { Plus, Save, Trash2 } from 'lucide-react'
import 'leaflet/dist/leaflet.css'

import ConfirmDialog from '@/components/Dialog'
import {
  buttonDanger,
  buttonPrimary,
  buttonSecondary,
  chipClass,
  inputClass,
  labelClass,
  noticeClass,
  panelPadded,
  textareaClass,
} from '@/components/styles'
import { api } from '@/lib/api'
import { colorToken } from '@/lib/tokens'
import {
  DESCRIPTION_MAX,
  KIND_OPTIONS,
  SLUG_MAX,
  apiErrorMessage,
  isInsideBounds,
  isValidSlug,
  newPlaceToPayload,
  padBounds,
  slugify,
  validateNewPlace,
} from '@/lib/manage'
import type { CampusBounds, CampusMap, MapVenue } from '@/lib/types'

interface Props {
  map: CampusMap
  bounds: CampusBounds
  reload: () => void
}

interface PlaceDraft {
  name: string
  id: string
  idTouched: boolean
  kind: string
  description: string
  connectToNearest: boolean
  lat: number
  lng: number
}
interface EditDraft {
  id: string
  name: string
  kind: string
  description: string
  lat: number
  lng: number
  moved: boolean
}
type Panel = { type: 'none' } | { type: 'new'; draft: PlaceDraft } | { type: 'edit'; draft: EditDraft }

const dot = (style: string) =>
  divIcon({
    className: '',
    html: `<span style="display:block;width:18px;height:18px;border-radius:50%;${style}"></span>`,
    iconSize: [18, 18],
    iconAnchor: [9, 9],
  })
// Colours reference the design tokens (index.css) so the markers follow the theme.
// Placed = solid accent; approximate = hollow ink-3 ring; unsaved = dashed accent.
const placedIcon = dot('background:var(--color-accent);border:3px solid var(--color-surface);outline:1px solid var(--color-accent)')
const derivedIcon = dot('background:transparent;border:3px solid var(--color-ink-3);outline:1px solid var(--color-surface)')
const newIcon = dot('background:var(--color-accent-soft);border:2px dashed var(--color-accent);outline:1px solid var(--color-surface)')

function ClickHandler({ onClick }: { onClick: (lat: number, lng: number) => void }) {
  useMapEvents({ click: (e) => onClick(e.latlng.lat, e.latlng.lng) })
  return null
}

export default function CampusMapView({ map, bounds, reload }: Props) {
  const [panel, setPanel] = useState<Panel>({ type: 'none' })
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [deleting, setDeleting] = useState<MapVenue | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const byId = new Map(map.venues.map((v) => [v.id, v]))
  const pos = (v: MapVenue): [number, number] | null =>
    typeof v.lat === 'number' && typeof v.lng === 'number' ? [v.lat, v.lng] : null
  const edit = panel.type === 'edit' ? panel.draft : null

  function onMapClick(lat: number, lng: number) {
    setNotice(null)
    if (!isInsideBounds(lat, lng, bounds)) {
      setPanel({ type: 'none' })
      setError('Outside campus area')
      return
    }
    setError(null)
    // Keep what was typed if a new place is already being drafted.
    setPanel((p) =>
      p.type === 'new'
        ? { type: 'new', draft: { ...p.draft, lat, lng } }
        : {
            type: 'new',
            draft: { name: '', id: '', idTouched: false, kind: '', description: '', connectToNearest: true, lat, lng },
          },
    )
  }

  function selectVenue(v: MapVenue) {
    const p = pos(v)
    if (!p) return
    setError(null)
    setNotice(null)
    setPanel({
      type: 'edit',
      draft: {
        id: v.id,
        name: v.name,
        kind: v.kind ?? '',
        description: v.description ?? '',
        lat: p[0],
        lng: p[1],
        moved: false,
      },
    })
  }

  function closePanel() {
    setPanel({ type: 'none' })
    setError(null)
  }

  async function createPlace(e: FormEvent) {
    e.preventDefault()
    if (panel.type !== 'new') return
    const msg = validateNewPlace(panel.draft, bounds)
    if (msg) return setError(msg)
    setBusy(true)
    setError(null)
    try {
      await api('/manage/venues', { method: 'POST', body: newPlaceToPayload(panel.draft) })
      setNotice(`Added ${panel.draft.name.trim()}. It now appears in the app and in the Graph view.`)
      setPanel({ type: 'none' })
      reload()
    } catch (err) {
      setError(apiErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  async function saveEdit(e: FormEvent) {
    e.preventDefault()
    if (!edit) return
    if (!edit.name.trim() || edit.name.trim().length > 60) return setError('Name must be 1-60 characters.')
    if (edit.description.length > DESCRIPTION_MAX)
      return setError(`Description must be at most ${DESCRIPTION_MAX} characters.`)
    const body: Record<string, unknown> = {
      name: edit.name.trim(),
      kind: edit.kind.trim(),
      description: edit.description.trim(),
    }
    if (edit.moved) {
      if (!isInsideBounds(edit.lat, edit.lng, bounds)) return setError('Outside campus area')
      body.lat = edit.lat
      body.lng = edit.lng
    }
    setBusy(true)
    setError(null)
    try {
      await api(`/manage/venues/${encodeURIComponent(edit.id)}`, { method: 'PUT', body })
      setNotice(`Saved ${edit.name.trim()}.`)
      setPanel({ type: 'none' })
      reload()
    } catch (err) {
      setError(apiErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  async function confirmDelete() {
    if (!deleting) return
    setBusy(true)
    setDeleteError(null)
    try {
      await api(`/manage/venues/${encodeURIComponent(deleting.id)}`, { method: 'DELETE' })
      setNotice(`Deleted ${deleting.name}.`)
      setDeleting(null)
      setPanel({ type: 'none' })
      reload()
    } catch (err) {
      setDeleteError(apiErrorMessage(err)) // includes VENUE_IN_USE
    } finally {
      setBusy(false)
    }
  }

  const rect: LatLngBoundsExpression = [
    [bounds.south, bounds.west],
    [bounds.north, bounds.east],
  ]

  return (
    <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(280px,1fr)]">
      <div className={panelPadded}>
        <div className="h-[460px] w-full overflow-hidden rounded-control border border-line" data-testid="campus-map">
          <MapContainer
            center={[bounds.center.lat, bounds.center.lng]}
            zoom={17}
            minZoom={15}
            maxBounds={padBounds(bounds)}
            className="h-full w-full"
          >
            <TileLayer
              url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
              attribution="&copy; OpenStreetMap contributors"
              maxZoom={19}
            />
            <Rectangle bounds={rect} pathOptions={{ color: colorToken('accent'), weight: 2, fill: false, dashArray: '6 6' }} />
            <ClickHandler onClick={onMapClick} />
            {map.edges.map((ed) => {
              const a = byId.get(ed.from)
              const b = byId.get(ed.to)
              const pa = a && pos(a)
              const pb = b && pos(b)
              if (!pa || !pb) return null
              return (
                <Polyline
                  key={`${ed.from}|${ed.to}`}
                  positions={[pa, pb]}
                  pathOptions={{ color: colorToken('walkway'), weight: 3, opacity: 1 }}
                >
                  <Tooltip sticky>{ed.distanceM} m</Tooltip>
                </Polyline>
              )
            })}
            {map.venues.map((v) => {
              const p = pos(v)
              if (!p) return null
              const selected = edit?.id === v.id
              const at: [number, number] = selected ? [edit.lat, edit.lng] : p
              return (
                <Marker
                  key={v.id}
                  position={at}
                  icon={v.geoPlaced ? placedIcon : derivedIcon}
                  draggable={selected}
                  zIndexOffset={selected ? 1000 : 0}
                  eventHandlers={{
                    click: () => selectVenue(v),
                    dragend: (ev) => {
                      const ll = (ev.target as { getLatLng(): { lat: number; lng: number } }).getLatLng()
                      setPanel((cur) =>
                        cur.type === 'edit' && cur.draft.id === v.id
                          ? { type: 'edit', draft: { ...cur.draft, lat: ll.lat, lng: ll.lng, moved: true } }
                          : cur,
                      )
                    },
                  }}
                >
                  <Tooltip direction="top" offset={[0, -10]}>
                    {v.name}
                    {!v.geoPlaced && ' (approx.)'}
                  </Tooltip>
                </Marker>
              )
            })}
            {panel.type === 'new' && (
              <Marker position={[panel.draft.lat, panel.draft.lng]} icon={newIcon} interactive={false} />
            )}
          </MapContainer>
        </div>
        <ul className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-small text-ink-2" aria-label="Map legend">
          <li className="flex items-center gap-1.5">
            <span aria-hidden className="inline-block h-4 w-4 rounded-full border-2 border-surface bg-accent outline outline-1 outline-accent" />
            Placed on the map
          </li>
          <li className="flex items-center gap-1.5">
            <span aria-hidden className="inline-block h-4 w-4 rounded-full border-2 border-ink-3 bg-transparent" />
            Approximate position from the graph
          </li>
          <li className="flex items-center gap-1.5">
            <span aria-hidden className="inline-block h-4 w-4 rounded-full border-2 border-dashed border-accent bg-accent-soft" />
            New place (unsaved)
          </li>
        </ul>
        <p className="mt-2 text-small text-ink-2">
          Click inside the dashed rectangle to add a place. Select a marker to edit it, then drag it to move it.
        </p>
      </div>

      <div className={panelPadded} aria-live="polite">
        {notice && (
          <p role="status" className={`${noticeClass('success')} mb-3`}>
            {notice}
          </p>
        )}
        {error && (
          <p role="alert" className={`${noticeClass('danger')} mb-3`}>
            {error}
          </p>
        )}

        {panel.type === 'none' && (
          <div>
            <p className="text-body text-ink-2">Click the map to add a new place, or select a marker to edit it.</p>
            <h2 className="mb-1 mt-4 text-overline font-semibold uppercase tracking-[0.06em] text-ink-3">
              Places ({map.venues.length})
            </h2>
            <ul aria-label="Places" className="max-h-[320px] overflow-y-auto rounded-control border border-line">
              {map.venues.map((v) => (
                <li key={v.id} className="border-t border-line first:border-t-0">
                  <button
                    type="button"
                    disabled={!pos(v)}
                    onClick={() => selectVenue(v)}
                    className="flex min-h-11 w-full items-center justify-between gap-2 px-3 py-1.5 text-left hover:bg-paper disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    <span className="min-w-0">
                      <span className="block truncate text-body font-medium text-ink">{v.name}</span>
                      {v.kind && <span className="block truncate text-caption font-medium text-ink-3">{v.kind}</span>}
                    </span>
                    <span className={`${chipClass(v.geoPlaced ? 'accent' : 'neutral')} shrink-0`}>
                      {v.geoPlaced ? 'Placed' : 'Approximate'}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}

        {panel.type === 'new' && (
          <form onSubmit={createPlace} aria-label="New place" className="flex flex-col gap-3">
            <h2 className="text-heading font-semibold text-ink">New place</h2>
            <p className="text-small text-ink-2">
              Lat {panel.draft.lat.toFixed(6)}, Lng {panel.draft.lng.toFixed(6)}
            </p>
            <label className={labelClass}>
              Name
              <input
                required
                maxLength={60}
                className={inputClass}
                value={panel.draft.name}
                onChange={(e) => {
                  const name = e.target.value
                  setPanel({
                    type: 'new',
                    draft: { ...panel.draft, name, id: panel.draft.idTouched ? panel.draft.id : slugify(name) },
                  })
                }}
              />
            </label>
            <label className={labelClass}>
              Id (permanent slug)
              <input
                required
                maxLength={SLUG_MAX}
                className={inputClass}
                value={panel.draft.id}
                aria-invalid={panel.draft.id !== '' && !isValidSlug(panel.draft.id)}
                onChange={(e) =>
                  setPanel({ type: 'new', draft: { ...panel.draft, id: e.target.value, idTouched: true } })
                }
              />
            </label>
            <KindDescription
              kind={panel.draft.kind}
              description={panel.draft.description}
              onKind={(kind) => setPanel({ type: 'new', draft: { ...panel.draft, kind } })}
              onDescription={(description) => setPanel({ type: 'new', draft: { ...panel.draft, description } })}
            />
            <label className="flex min-h-9 items-center gap-2 text-body">
              <input
                type="checkbox"
                className="h-4 w-4 accent-accent"
                checked={panel.draft.connectToNearest}
                onChange={(e) =>
                  setPanel({ type: 'new', draft: { ...panel.draft, connectToNearest: e.target.checked } })
                }
              />
              Connect to nearest place
            </label>
            <div className="flex gap-2">
              <button type="submit" disabled={busy} className={buttonPrimary}>
                <Plus className="h-4 w-4" aria-hidden />
                Create place
              </button>
              <button type="button" className={buttonSecondary} onClick={closePanel}>
                Cancel
              </button>
            </div>
          </form>
        )}

        {edit && (
          <form onSubmit={saveEdit} aria-label="Edit place" className="flex flex-col gap-3">
            <h2 className="text-heading font-semibold text-ink">Edit place</h2>
            <p className="text-small text-ink-2">
              Id: <code className="rounded-control bg-surface-sunken px-1">{edit.id}</code> (permanent). Lat {edit.lat.toFixed(6)}, Lng {edit.lng.toFixed(6)}
              {edit.moved && ' (moved, not saved yet)'}
            </p>
            <label className={labelClass}>
              Name
              <input
                required
                maxLength={60}
                className={inputClass}
                value={edit.name}
                onChange={(e) => setPanel({ type: 'edit', draft: { ...edit, name: e.target.value } })}
              />
            </label>
            <KindDescription
              kind={edit.kind}
              description={edit.description}
              onKind={(kind) => setPanel({ type: 'edit', draft: { ...edit, kind } })}
              onDescription={(description) => setPanel({ type: 'edit', draft: { ...edit, description } })}
            />
            <div className="flex flex-wrap gap-2">
              <button type="submit" disabled={busy} className={buttonPrimary}>
                <Save className="h-4 w-4" aria-hidden />
                Save place
              </button>
              <button
                type="button"
                className={buttonDanger}
                onClick={() => {
                  const v = byId.get(edit.id)
                  if (v) {
                    setDeleteError(null)
                    setDeleting(v)
                  }
                }}
              >
                <Trash2 className="h-4 w-4" aria-hidden />
                Delete place
              </button>
              <button type="button" className={buttonSecondary} onClick={closePanel}>
                Close
              </button>
            </div>
          </form>
        )}
      </div>

      {deleting && (
        <ConfirmDialog
          title="Delete place?"
          confirmLabel="Delete place"
          busy={busy}
          error={deleteError}
          onConfirm={confirmDelete}
          onCancel={() => setDeleting(null)}
        >
          {`"${deleting.name}" and all paths connected to it will be removed. This cannot be undone.`}
        </ConfirmDialog>
      )}
    </div>
  )
}

function KindDescription(props: {
  kind: string
  description: string
  onKind: (v: string) => void
  onDescription: (v: string) => void
}) {
  return (
    <>
      <label className={labelClass}>
        Kind
        <input
          list="campus-kinds"
          maxLength={40}
          className={inputClass}
          value={props.kind}
          onChange={(e) => props.onKind(e.target.value)}
        />
        <datalist id="campus-kinds">
          {KIND_OPTIONS.map((k) => (
            <option key={k} value={k} />
          ))}
        </datalist>
      </label>
      <label className={labelClass}>
        Description
        <textarea
          maxLength={DESCRIPTION_MAX}
          rows={3}
          className={textareaClass}
          value={props.description}
          onChange={(e) => props.onDescription(e.target.value)}
        />
      </label>
    </>
  )
}
