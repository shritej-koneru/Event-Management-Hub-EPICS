import { useEffect, useState } from 'react'

import CampusMapView from '@/components/CampusMapView'
import { ErrorNote, Loading } from '@/components/Feedback'
import GraphEditor from '@/components/GraphEditor'
import PageContainer from '@/components/layout/PageContainer'
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
      className={`-mb-px h-11 border-b-2 px-4 text-body font-medium transition-colors duration-150 ease-out ${
        view === v ? 'border-accent text-accent' : 'border-transparent text-ink-2 hover:text-ink'
      }`}
    >
      {text}
    </button>
  )

  return (
    <PageContainer title="Map editor" description="Venues and walking paths shared with the mobile app">
      <div role="tablist" aria-label="Map view" className="mb-5 flex gap-1 border-b border-line">
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
    </PageContainer>
  )
}
