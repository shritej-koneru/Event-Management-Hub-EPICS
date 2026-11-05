import { useRef } from 'react'
import type { KeyboardEvent, PointerEvent } from 'react'

import { CANVAS_H, CANVAS_W, canvasToFraction, fractionToCanvas, roundFraction } from '@/lib/manage'
import type { MapEdge, MapVenue } from '@/lib/types'

interface MapCanvasProps {
  venues: MapVenue[]
  edges: MapEdge[]
  selectedVenueId: string | null
  selectedEdge: { from: string; to: string } | null
  /** Pending (not yet created) venue shown as a dashed circle. */
  ghost: { name: string; x: number; y: number } | null
  /** True while placing a new venue: canvas clicks report a position. */
  placing: boolean
  onSelectVenue: (id: string) => void
  onSelectEdge: (edge: MapEdge) => void
  onMoveVenue: (id: string, x: number, y: number) => void
  onPlace: (x: number, y: number) => void
}

const sameEdge = (a: { from: string; to: string }, b: { from: string; to: string }) =>
  (a.from === b.from && a.to === b.to) || (a.from === b.to && a.to === b.from)

/** SVG campus map. Coordinates are fractions 0..1; viewBox is 800x600 (4:3). */
export default function MapCanvas({
  venues,
  edges,
  selectedVenueId,
  selectedEdge,
  ghost,
  placing,
  onSelectVenue,
  onSelectEdge,
  onMoveVenue,
  onPlace,
}: MapCanvasProps) {
  const svgRef = useRef<SVGSVGElement>(null)
  const dragging = useRef<string | null>(null)

  const byId = new Map(venues.map((v) => [v.id, v]))

  function toFractions(e: { clientX: number; clientY: number }) {
    const rect = svgRef.current?.getBoundingClientRect()
    if (!rect || rect.width === 0 || rect.height === 0) return null
    const cx = ((e.clientX - rect.left) / rect.width) * CANVAS_W
    const cy = ((e.clientY - rect.top) / rect.height) * CANVAS_H
    return { x: roundFraction(canvasToFraction(cx, CANVAS_W)), y: roundFraction(canvasToFraction(cy, CANVAS_H)) }
  }

  function onCanvasClick(e: PointerEvent<SVGSVGElement>) {
    if (!placing) return
    const p = toFractions(e)
    if (p) onPlace(p.x, p.y)
  }

  function startDrag(e: PointerEvent<SVGGElement>, id: string) {
    e.stopPropagation()
    dragging.current = id
    e.currentTarget.setPointerCapture(e.pointerId)
    onSelectVenue(id)
  }

  function drag(e: PointerEvent<SVGGElement>) {
    if (dragging.current === null) return
    const p = toFractions(e)
    if (p) {
      onMoveVenue(dragging.current, p.x, p.y)
    }
  }

  function endDrag(e: PointerEvent<SVGGElement>) {
    if (dragging.current === null) return
    dragging.current = null
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
  }

  function keySelect(e: KeyboardEvent, action: () => void) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      action()
    }
  }

  return (
    <svg
      ref={svgRef}
      viewBox={`0 0 ${CANVAS_W} ${CANVAS_H}`}
      role="group"
      aria-label="Campus map editor. Venues are circles, paths are lines. Use the lists below for keyboard editing."
      className={`aspect-[4/3] w-full touch-none select-none rounded-card border border-line bg-surface ${
        placing ? 'cursor-crosshair' : ''
      }`}
      onPointerDown={onCanvasClick}
    >
      <defs>
        <pattern id="map-grid" width={GRID} height={GRID} patternUnits="userSpaceOnUse">
          <path d={`M ${GRID} 0 L 0 0 0 ${GRID}`} fill="none" className="stroke-line" strokeOpacity={0.4} strokeWidth={1} />
        </pattern>
      </defs>
      <rect width={CANVAS_W} height={CANVAS_H} fill="url(#map-grid)" pointerEvents="none" />

      {edges.map((edge) => {
        const a = byId.get(edge.from)
        const b = byId.get(edge.to)
        if (!a || !b) return null
        const x1 = fractionToCanvas(a.x, CANVAS_W)
        const y1 = fractionToCanvas(a.y, CANVAS_H)
        const x2 = fractionToCanvas(b.x, CANVAS_W)
        const y2 = fractionToCanvas(b.y, CANVAS_H)
        const selected = selectedEdge !== null && sameEdge(selectedEdge, edge)
        const select = () => onSelectEdge(edge)
        return (
          <g
            key={`${edge.from}|${edge.to}`}
            role="button"
            tabIndex={0}
            aria-label={`Path ${a.name} to ${b.name}, ${edge.distanceM} metres`}
            aria-pressed={selected}
            className="group cursor-pointer outline-none"
            onPointerDown={(e) => {
              e.stopPropagation()
              select()
            }}
            onKeyDown={(e) => keySelect(e, select)}
          >
            <line
              x1={x1}
              y1={y1}
              x2={x2}
              y2={y2}
              strokeWidth={18}
              className="stroke-transparent group-focus-visible:stroke-accent/25"
            />
            <line
              x1={x1}
              y1={y1}
              x2={x2}
              y2={y2}
              strokeWidth={3}
              strokeLinecap="round"
              className={selected ? 'stroke-accent' : 'stroke-walkway'}
            />
            <MapLabel x={(x1 + x2) / 2} y={(y1 + y2) / 2} text={`${edge.distanceM} m`} />
          </g>
        )
      })}

      {venues.map((v) => {
        const cx = fractionToCanvas(v.x, CANVAS_W)
        const cy = fractionToCanvas(v.y, CANVAS_H)
        const selected = v.id === selectedVenueId
        return (
          <g
            key={v.id}
            role="button"
            tabIndex={0}
            aria-label={`Venue ${v.name}`}
            aria-pressed={selected}
            className="group cursor-grab outline-none"
            onPointerDown={(e) => startDrag(e, v.id)}
            onPointerMove={drag}
            onPointerUp={endDrag}
            onPointerCancel={endDrag}
            onKeyDown={(e) => keySelect(e, () => onSelectVenue(v.id))}
          >
            <circle
              cx={cx}
              cy={cy}
              r={selected ? 17 : 14}
              fill="none"
              strokeWidth={2}
              className="stroke-accent opacity-0 group-focus-visible:opacity-100"
            />
            <circle
              cx={cx}
              cy={cy}
              r={selected ? 10 : 8}
              strokeWidth={selected ? 3 : 2}
              className={selected ? 'fill-accent stroke-surface' : 'fill-surface stroke-ink'}
            />
            <MapLabel x={cx} y={cy - 24} text={v.name} />
          </g>
        )
      })}

      {ghost && (
        <g aria-hidden>
          <circle
            cx={fractionToCanvas(ghost.x, CANVAS_W)}
            cy={fractionToCanvas(ghost.y, CANVAS_H)}
            r={10}
            strokeWidth={2}
            strokeDasharray="4 3"
            className="fill-accent-soft stroke-accent"
          />
          <MapLabel
            x={fractionToCanvas(ghost.x, CANVAS_W)}
            y={fractionToCanvas(ghost.y, CANVAS_H) - 24}
            text={ghost.name || 'New venue'}
           
          />
        </g>
      )}
    </svg>
  )
}

const GRID = 24

/** Small 500 ink label on a surface chip with a 1px line and 4px radius. */
function MapLabel({ x, y, text }: { x: number; y: number; text: string }) {
  const w = Math.max(24, text.length * 7.4 + 12)
  return (
    <g pointerEvents="none">
      <rect x={x - w / 2} y={y - 10} width={w} height={20} rx={4} strokeWidth={1} className="fill-surface stroke-line" />
      <text
        x={x}
        y={y + 4}
        textAnchor="middle"
        fontSize={13}
        fontWeight={500}
        className="fill-ink"
      >
        {text}
      </text>
    </g>
  )
}
