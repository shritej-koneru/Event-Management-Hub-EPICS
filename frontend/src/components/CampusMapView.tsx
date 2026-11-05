import { useState } from 'react'
import type { FormEvent } from 'react'
import { divIcon } from 'leaflet'
import type { LatLngBoundsExpression } from 'leaflet'
import { MapContainer, Marker, Polyline, Rectangle, TileLayer, Tooltip, useMapEvents } from 'react-leaflet'
import { Plus, Save, Trash2 } from 'lucide-react'
import 'leaflet/dist/leaflet.css'

import ConfirmDialog from '@/components/Dialog'
import { cardClass, compactPrimaryClass, dangerButtonClass, inputClass, smallButtonClass } from '@/components/styles'
import { api } from '@/lib/api'
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
const placedIcon = dot('background:#5c3ce8;border:3px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.5)')
const derivedIcon = dot('background:#fff;border:3px dashed #77758b;box-shadow:0 1px 4px rgba(0,0,0,.4)')
const newIcon = dot('background:#f59e0b;border:3px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.5)')

function ClickHandler({ onClick }: { onClick: (lat: number, lng: number) => void }) {
  useMapEvents({ click: (e) => onClick(e.latlng.lat, e.latlng.lng) })
  return null
}

const label = 'block text-xs font-semibold'

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
    <div className="grid gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(280px,1fr)]">
      <div className={cardClass}>
        <div className="h-[460px] w-full overflow-hidden rounded-[14px]" data-testid="campus-map">
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
            <Rectangle bounds={rect} pathOptions={{ color: '#5c3ce8', weight: 2, fill: false, dashArray: '6 6' }} />
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
                  pathOptions={{ color: '#6145ef', weight: 3, opacity: 0.7 }}
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
        <ul className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-xs text-[#4a4660]" aria-label="Map legend">
          <li className="flex items-center gap-1.5">
            <span aria-hidden className="inline-block h-3.5 w-3.5 rounded-full border-2 border-white bg-[#5c3ce8] shadow" />
            Placed on the map
          </li>
          <li className="flex items-center gap-1.5">
            <span
              aria-hidden
              className="inline-block h-3.5 w-3.5 rounded-full border-2 border-dashed border-[#77758b] bg-white"
            />
            Approximate position from the graph
          </li>
          <li className="flex items-center gap-1.5">
            <span aria-hidden className="inline-block h-3.5 w-3.5 rounded-full border-2 border-white bg-[#f59e0b] shadow" />
            New place (unsaved)
          </li>
        </ul>
        <p className="mt-2 text-xs text-[#77758b]">
          Click inside the dashed rectangle to add a place. Select a marker to edit it, then drag it to move it.
        </p>
      </div>

      <div className={cardClass} aria-live="polite">
        {notice && (
          <p role="status" className="mb-3 rounded-lg bg-green-50 px-3 py-2 text-sm text-green-800">
            {notice}
          </p>
        )}
        {error && (
          <p role="alert" className="mb-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </p>
        )}

        {panel.type === 'none' && (
          <p className="text-sm text-[#77758b]">Click the map to add a new place, or select a marker to edit it.</p>
        )}

        {panel.type === 'new' && (
          <form onSubmit={createPlace} aria-label="New place" className="flex flex-col gap-3">
            <h2 className="text-lg font-bold text-[#5c3ce8]">New place</h2>
            <p className="text-xs text-[#77758b]">
              Lat {panel.draft.lat.toFixed(6)}, Lng {panel.draft.lng.toFixed(6)}
            </p>
            <label className={label}>
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
            <label className={label}>
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
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={panel.draft.connectToNearest}
                onChange={(e) =>
                  setPanel({ type: 'new', draft: { ...panel.draft, connectToNearest: e.target.checked } })
                }
              />
              Connect to nearest place
            </label>
            <div className="flex gap-2">
              <button type="submit" disabled={busy} className={compactPrimaryClass}>
                <Plus className="h-4 w-4" aria-hidden />
                Create place
              </button>
              <button type="button" className={smallButtonClass} onClick={closePanel}>
                Cancel
              </button>
            </div>
          </form>
        )}

        {edit && (
          <form onSubmit={saveEdit} aria-label="Edit place" className="flex flex-col gap-3">
            <h2 className="text-lg font-bold text-[#5c3ce8]">Edit place</h2>
            <p className="text-xs text-[#77758b]">
              Id: <code>{edit.id}</code> (permanent). Lat {edit.lat.toFixed(6)}, Lng {edit.lng.toFixed(6)}
              {edit.moved && ' (moved, not saved yet)'}
            </p>
            <label className={label}>
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
              <button type="submit" disabled={busy} className={compactPrimaryClass}>
                <Save className="h-4 w-4" aria-hidden />
                Save place
              </button>
              <button
                type="button"
                className={dangerButtonClass}
                onClick={() => {
                  const v = byId.get(edit.id)
                  if (v) {
                    setDeleteError(null)
                    setDeleting(v)
                  }
                }}
              >
                <Trash2 className="h-3.5 w-3.5" aria-hidden />
                Delete place
              </button>
              <button type="button" className={smallButtonClass} onClick={closePanel}>
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
      <label className={label}>
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
      <label className={label}>
        Description
        <textarea
          maxLength={DESCRIPTION_MAX}
          rows={3}
          className={`${inputClass} h-auto py-2`}
          value={props.description}
          onChange={(e) => props.onDescription(e.target.value)}
        />
      </label>
    </>
  )
}
