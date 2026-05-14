import { useEffect, useRef } from 'react'
import L from 'leaflet'
import { useMap } from 'react-leaflet'
import { api } from '../api/endpoints'
import { useRunSnapshotStore } from '../stores/runSnapshotStore'
import { useUiStore } from '../stores/uiStore'
import { REGION_COLORS } from '../stores/planStore'

/** Fetches per-SR polylines after an allocation run and draws them on the map. */
export function RoutePolylineOverlay() {
  const map = useMap()
  const summary = useRunSnapshotStore((s) => s.summary)
  const lastPolylineMode = useRunSnapshotStore((s) => s.lastPolylineMode)
  const showRoutes = useUiStore((s) => s.mapLayerRoutes)
  const selectedSrName = useUiStore((s) => s.selectedSrName)
  const groupRef = useRef<L.LayerGroup | null>(null)

  useEffect(() => {
    if (groupRef.current) {
      map.removeLayer(groupRef.current)
      groupRef.current = null
    }
    if (!showRoutes || !summary?.srSummaries?.length || !summary.date) return

    const g = L.layerGroup().addTo(map)
    groupRef.current = g
    let cancelled = false
    const plMode = lastPolylineMode === 'google' ? 'google' : 'ors'

    const ordered = [...summary.srSummaries].sort((a, b) => {
      const asel = a.srName === selectedSrName
      const bsel = b.srName === selectedSrName
      if (asel && !bsel) return 1
      if (!asel && bsel) return -1
      return 0
    })

    ;(async () => {
      for (let i = 0; i < ordered.length; i++) {
        if (cancelled) return
        const sr = ordered[i]
        const origIdx = summary.srSummaries.findIndex((s) => s.srName === sr.srName)
        const col = REGION_COLORS[origIdx >= 0 ? origIdx % REGION_COLORS.length : i % REGION_COLORS.length]
        const isSel = !!selectedSrName && sr.srName === selectedSrName
        const weight = isSel ? 5 : 2
        const opacity = selectedSrName ? (isSel ? 0.95 : 0.28) : 0.55
        try {
          const coords = await api.polyline(summary.date, sr.srName, plMode)
          if (cancelled) return
          if (coords?.length) {
            L.polyline(coords.map((p) => [p[0], p[1]] as L.LatLngExpression), {
              color: col,
              weight,
              opacity,
              smoothFactor: 1,
              lineCap: 'round',
              lineJoin: 'round',
            }).addTo(g)
          }
        } catch {
          /* ignore per-SR failures */
        }
      }
    })()

    return () => {
      cancelled = true
      if (groupRef.current) {
        map.removeLayer(groupRef.current)
        groupRef.current = null
      }
    }
  }, [map, summary, lastPolylineMode, showRoutes, selectedSrName])

  return null
}
