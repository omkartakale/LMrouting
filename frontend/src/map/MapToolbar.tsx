import { useLocation } from 'react-router-dom'
import { useUiStore } from '../stores/uiStore'

export function MapToolbar() {
  const { pathname } = useLocation()
  if (!pathname.includes('/allocation/step-4')) return null

  const mapLayerRoutes = useUiStore((s) => s.mapLayerRoutes)
  const mapLayerRegions = useUiStore((s) => s.mapLayerRegions)
  const mapLayerStops = useUiStore((s) => s.mapLayerStops)
  const mapLayerStopsAll = useUiStore((s) => s.mapLayerStopsAll)
  const p0 = useUiStore((s) => s.mapPriorityP0)
  const p1 = useUiStore((s) => s.mapPriorityP1)
  const p2 = useUiStore((s) => s.mapPriorityP2)
  const setRoutes = useUiStore((s) => s.setMapLayerRoutes)
  const setRegions = useUiStore((s) => s.setMapLayerRegions)
  const setStops = useUiStore((s) => s.setMapLayerStops)
  const setStopsAll = useUiStore((s) => s.setMapLayerStopsAll)
  const setP0 = useUiStore((s) => s.setMapPriorityP0)
  const setP1 = useUiStore((s) => s.setMapPriorityP1)
  const setP2 = useUiStore((s) => s.setMapPriorityP2)

  return (
    <div className="map-toolbar">
      <span className="map-toolbar-label">Show</span>
      <label className="map-toolbar-toggle">
        <input type="checkbox" checked={mapLayerRoutes} onChange={(e) => setRoutes(e.target.checked)} />
        SR Routes
      </label>
      <label className="map-toolbar-toggle" title="Stop markers from the selected SR’s route (open an SR card first).">
        <input type="checkbox" checked={mapLayerStops} onChange={(e) => setStops(e.target.checked)} />
        Steps
      </label>
      <label
        className={`map-toolbar-toggle ${mapLayerStops ? '' : 'map-toolbar-disabled'}`}
        title="Load every SR’s stops in batches and cluster them (heavier)."
      >
        <input
          type="checkbox"
          disabled={!mapLayerStops}
          checked={mapLayerStopsAll}
          onChange={(e) => setStopsAll(e.target.checked)}
        />
        All SRs
      </label>
      <label className="map-toolbar-toggle">
        <input type="checkbox" checked={mapLayerRegions} onChange={(e) => setRegions(e.target.checked)} />
        Regions
      </label>
      <span className="map-toolbar-divider" />
      <span className="map-toolbar-label">Priority</span>
      <label className="map-toolbar-toggle map-p0">
        <input type="checkbox" checked={p0} onChange={(e) => setP0(e.target.checked)} />
        P0
      </label>
      <label className="map-toolbar-toggle map-p1">
        <input type="checkbox" checked={p1} onChange={(e) => setP1(e.target.checked)} />
        P1
      </label>
      <label className="map-toolbar-toggle map-p2">
        <input type="checkbox" checked={p2} onChange={(e) => setP2(e.target.checked)} />
        P2
      </label>
    </div>
  )
}
