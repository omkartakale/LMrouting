import { useEffect, useRef } from 'react'
import L from 'leaflet'
import { Polygon, useMap } from 'react-leaflet'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/endpoints'

const PANE = 'hubBoundaryPane'

/** Hub service boundary + optional admin boundary (same API and styling as legacy UI). */
export function HubBoundaryLayers() {
  const map = useMap()
  const fittedRef = useRef(false)
  const lastHubRef = useRef<string | null>(null)

  const { data: hub } = useQuery({ queryKey: ['hub'], queryFn: () => api.hub() })

  const boundaryQ = useQuery({
    queryKey: ['hubBoundary', hub?.hubName],
    queryFn: () => api.hubBoundary(hub!.hubName),
    enabled: !!hub?.hubName,
    staleTime: 1000 * 60 * 30,
  })

  useEffect(() => {
    if (map.getPane(PANE)) return
    map.createPane(PANE)
    const el = map.getPane(PANE)
    if (el) {
      el.style.zIndex = '200'
      el.style.pointerEvents = 'none'
    }
  }, [map])

  useEffect(() => {
    const name = hub?.hubName ?? null
    if (name !== lastHubRef.current) {
      lastHubRef.current = name
      fittedRef.current = false
    }
  }, [hub?.hubName])

  useEffect(() => {
    const ring = boundaryQ.data?.coordinates
    if (!ring?.length || ring.length < 3) return
    if (fittedRef.current) return
    try {
      const latlngs = ring.map((p) => [p[0], p[1]] as L.LatLngExpression)
      const b = L.polygon(latlngs).getBounds()
      if (b.isValid()) {
        map.fitBounds(b.pad(0.05))
        fittedRef.current = true
        requestAnimationFrame(() => {
          map.invalidateSize({ animate: false })
        })
      }
    } catch {
      /* ignore */
    }
  }, [boundaryQ.data, map])

  const data = boundaryQ.data
  if (!data?.coordinates?.length || data.coordinates.length < 3) return null

  const primary = data.coordinates.map((p) => [p[0], p[1]] as [number, number])
  const original = data.originalBoundary?.length && data.originalBoundary.length >= 3 ? data.originalBoundary : null

  return (
    <>
      <Polygon
        positions={primary}
        pathOptions={{
          pane: PANE,
          color: '#b71c1c',
          weight: 4,
          opacity: 1,
          fillColor: '#e53935',
          fillOpacity: 0.04,
          interactive: false,
        }}
      />
      {original && (
        <Polygon
          positions={original.map((p) => [p[0], p[1]] as [number, number])}
          pathOptions={{
            pane: PANE,
            color: '#00838f',
            weight: 2,
            opacity: 0.75,
            dashArray: '6, 8',
            fillColor: '#00bcd4',
            fillOpacity: 0.04,
            interactive: false,
          }}
        />
      )}
    </>
  )
}
