import { useEffect, useState } from 'react'

import CampusMapView from '@/components/CampusMapView'
import { ErrorNote, Loading, PageHeading } from '@/components/Feedback'
import GraphEditor from '@/components/GraphEditor'
import { api } from '@/lib/api'
import type { CampusMap } from '@/lib/types'
import { useFetch } from '@/lib/useFetch'
import { useSocket } from '@/realtime/context'

type View = 'campus' | 'graph'

export default function MapPage() {
  const { socket } = useSocket()
  const map = useFetch((signal) => api<CampusMap>('/navigation/map', { signal }))
  const reload = map.reload
  const [view, setView] = useState<View>('campus')

  // Another manager (or this one) changed the map: refresh both views.
  useEffect(() => {
    if (!socket) return
    socket.on('map:updated', reload)
    return () => {
      socket.off('map:updated', reload)
    }
  }, [socket, reload])

  const tab = (v: View, text: string) => (
    <button
      type="button"
      role="tab"
      id={`tab-${v}`}
      aria-selected={view === v}
      aria-controls="map-panel"
      onClick={() => setView(v)}
      className={`h-10 cursor-pointer rounded-[13px] px-4 text-sm font-bold transition-all ${
        view === v ? 'bg-white text-[#5c3ce8]' : 'bg-white/15 text-white hover:bg-white/25'
      }`}
    >
      {text}
    </button>
  )

  return (
    <div className="flex flex-col px-[18px] pb-6 pt-[25px]">
      <PageHeading title="Map editor" subtitle="Venues and walking paths shared with the mobile app" />
      <div role="tablist" aria-label="Map view" className="mb-4 flex gap-2">
        {tab('campus', 'Campus map')}
        {tab('graph', 'Graph')}
      </div>

      {map.error && <ErrorNote>{map.error}</ErrorNote>}
      {map.loading && !map.data && <Loading label="Loading map..." />}

      <div role="tabpanel" id="map-panel" aria-labelledby={`tab-${view}`}>
        {map.data && view === 'graph' && <GraphEditor map={map} reload={reload} />}
        {map.data &&
          view === 'campus' &&
          (map.data.bounds ? (
            <CampusMapView map={map.data} bounds={map.data.bounds} reload={reload} />
          ) : (
            <ErrorNote>
              The server did not send campus bounds, so the campus map is unavailable. Use the Graph view.
            </ErrorNote>
          ))}
      </div>
    </div>
  )
}
