import { useEffect, useRef } from 'react'
import L from 'leaflet'
import { useMap } from 'react-leaflet'
import { usePlanStore } from '../stores/planStore'
import { simplifyRing, validateRegion } from './regionUtils'
import { api } from '../api/endpoints'

interface PmApi {
  enableDraw: (shape: string, options: Record<string, unknown>) => void
  disableDraw: () => void
  globalDrawModeEnabled: () => boolean
}

/**
 * Enables Leaflet-Geoman polygon draw when {@link usePlanStore}'s activeDrawRegion is set.
 */
export function GeomanDrawBridge() {
  const map = useMap()
  const activeDrawRegion = usePlanStore((s) => s.activeDrawRegion)
  const color = usePlanStore((s) => {
    if (!s.activeDrawRegion) return '#1976d2'
    return s.regions[s.activeDrawRegion]?.color || '#1976d2'
  })
  const setPolygon = usePlanStore((s) => s.setPolygonForRegion)
  const session = useRef(0)

  useEffect(() => {
    if (!activeDrawRegion || !map) return
    const pm = (map as unknown as { pm?: PmApi }).pm
    if (!pm) {
      console.warn('Leaflet Geoman (pm) not available on map')
      return
    }

    const sid = ++session.current
    const onCreate = (e: L.LeafletEvent) => {
      if (sid !== session.current) return
      const layer = (e as L.LeafletEvent & { layer: L.Polygon }).layer
      const raw = (layer.getLatLngs()[0] as L.LatLng[]).map((ll) => [ll.lat, ll.lng] as [number, number])
      map.removeLayer(layer)
      pm.disableDraw()

      const v = validateRegion(raw)
      if (!v.valid) {
        window.alert(v.reason || 'Invalid region')
        usePlanStore.getState().setActiveDrawRegion(null)
        return
      }
      const coords = simplifyRing(raw)
      setPolygon(activeDrawRegion, coords, color)
      void api
        .hub()
        .then((h) => usePlanStore.getState().persistRegions(h.hubName))
        .catch(() => {})
    }

    map.on('pm:create', onCreate)
    pm.enableDraw('Polygon', {
      snappable: false,
      cursorMarker: true,
      allowSelfIntersection: false,
      continueDrawing: false,
      freehand: true,
      freehandInterval: 50,
      templineStyle: { color, weight: 2, dashArray: '5,5' },
      hintlineStyle: { color, weight: 1, dashArray: '5,5' },
      pathOptions: { color, weight: 2.5, fillColor: color, fillOpacity: 0.18 },
    })

    return () => {
      session.current++
      map.off('pm:create', onCreate)
      if (pm.globalDrawModeEnabled()) pm.disableDraw()
    }
  }, [activeDrawRegion, map, setPolygon, color])

  return null
}
