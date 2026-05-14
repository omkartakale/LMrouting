import { usePlanStore, REGION_COLORS } from '../../stores/planStore'

export function PlanZones() {
  const regionCount = usePlanStore((s) => s.regionCount)
  const setRegionCountInput = usePlanStore((s) => s.setRegionCountInput)
  const applyRegionCount = usePlanStore((s) => s.applyRegionCount)
  const regions = usePlanStore((s) => s.regions)
  const setActiveDrawRegion = usePlanStore((s) => s.setActiveDrawRegion)
  const activeDrawRegion = usePlanStore((s) => s.activeDrawRegion)
  const clearRegion = usePlanStore((s) => s.clearRegion)
  const getSrCounts = usePlanStore((s) => s.getSrCounts)

  const counts = getSrCounts()

  return (
    <div className="panel">
      <h2 className="panel-title">Affinity zones</h2>
      <p className="row-muted">Draw polygons on the map. Use compact rows; primary action is draw/redraw only for the active region.</p>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <label htmlFor="zc">Zones to draw</label>
        <input
          id="zc"
          type="number"
          min={1}
          max={20}
          value={regionCount}
          onChange={(e) => setRegionCountInput(parseInt(e.target.value, 10) || 1)}
          style={{ width: 64, padding: 6, borderRadius: 6, border: '1px solid #d1d5db' }}
        />
        <button type="button" className="btn btn-primary" onClick={() => applyRegionCount()}>
          Set
        </button>
      </div>
      <div style={{ maxHeight: 240, overflow: 'auto' }}>
        {Array.from({ length: regionCount }, (_, i) => {
          const name = `Region ${i + 1}`
          const r = regions[name]
          const color = r?.color ?? REGION_COLORS[i % REGION_COLORS.length]
          const has = (r?.latlngs?.length ?? 0) >= 3
          const active = activeDrawRegion === name
          return (
            <div
              key={name}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: '6px 0',
                borderBottom: '1px solid #f3f4f6',
                fontSize: '0.8rem',
              }}
            >
              <span style={{ width: 10, height: 10, borderRadius: 999, background: color, flexShrink: 0 }} />
              <span style={{ minWidth: 64, fontWeight: 600 }}>{name}</span>
              {active ? (
                <button type="button" className="btn btn-secondary" style={{ fontSize: '0.72rem' }} onClick={() => setActiveDrawRegion(null)}>
                  Stop
                </button>
              ) : (
                <button
                  type="button"
                  className="btn btn-secondary"
                  style={{ fontSize: '0.72rem' }}
                  onClick={() => setActiveDrawRegion(name)}
                >
                  {has ? 'Redraw' : 'Draw'}
                </button>
              )}
              {has && <span className="badge ok">{counts[name] ?? 0} SRs</span>}
              {has && (
                <button type="button" className="btn btn-ghost" style={{ fontSize: '0.72rem' }} onClick={() => clearRegion(name)}>
                  Clear
                </button>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
