import { Polygon } from 'react-leaflet'
import { usePlanStore } from '../stores/planStore'
import { useUiStore } from '../stores/uiStore'

export function RegionPolyLayers() {
  const regions = usePlanStore((s) => s.regions)
  const show = useUiStore((s) => s.mapLayerRegions)
  const nudged = useUiStore((s) => s.mapNudgedRegionNames)
  if (!show) return null
  return (
    <>
      {Object.entries(regions).map(([name, r]) => {
        if (r.latlngs.length < 3) return null
        const strokeNudge = nudged.includes(name)
        return (
          <Polygon
            key={name}
            positions={r.latlngs}
            pathOptions={{
              color: r.color,
              weight: strokeNudge ? 4 : 2,
              opacity: strokeNudge ? 1 : 0.88,
              fillColor: r.color,
              fillOpacity: strokeNudge ? 0.16 : 0.12,
            }}
          />
        )
      })}
    </>
  )
}
