import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import { useSessionStore } from '../stores/sessionStore'
import { useUiStore } from '../stores/uiStore'
import { useRunSnapshotStore } from '../stores/runSnapshotStore'

export function DetailDrawer() {
  const drawerOpen = useUiStore((s) => s.drawerOpen)
  const selectedSrName = useUiStore((s) => s.selectedSrName)
  const closeDrawer = useUiStore((s) => s.closeDrawer)
  const date = useSessionStore((s) => s.selectedDate)
  const snap = useRunSnapshotStore((s) => s.summary)
  const live = useQuery({
    queryKey: ['summary', date],
    queryFn: () => api.summary(date!),
    enabled: !!date && drawerOpen && !snap,
    retry: false,
  })
  const summary = snap ?? live.data

  const mapStopsSelectedOnly = useUiStore((s) => s.mapLayerStops && !s.mapLayerStopsAll)

  const [timelineJson, setTimelineJson] = useState<string | null>(null)

  const routeQ = useQuery({
    queryKey: ['srRoute', date, selectedSrName],
    queryFn: () => api.srRoute(date!, selectedSrName!),
    enabled: !!date && !!selectedSrName && (drawerOpen || mapStopsSelectedOnly),
  })

  useEffect(() => {
    if (!date || !selectedSrName || !drawerOpen) {
      setTimelineJson(null)
      return
    }
    let cancelled = false
    api
      .srTimeline(date, selectedSrName)
      .then((t) => {
        if (!cancelled) setTimelineJson(JSON.stringify(t, null, 2))
      })
      .catch(() => {
        if (!cancelled) setTimelineJson(null)
      })
    return () => {
      cancelled = true
    }
  }, [date, selectedSrName, drawerOpen])

  if (!drawerOpen || !selectedSrName) return null

  const sr = summary?.srSummaries?.find((x) => x.srName === selectedSrName)

  return (
    <>
      <div className="drawer-backdrop" role="presentation" onClick={closeDrawer} />
      <aside className="drawer" role="dialog" aria-modal="true" aria-label="SR details">
        <div className="drawer-header">
          <span>{selectedSrName}</span>
          <button type="button" className="btn btn-ghost" onClick={closeDrawer}>
            Close
          </button>
        </div>
        <div className="drawer-body">
          {sr && (
            <div className="stat-grid" style={{ marginBottom: 12 }}>
              <div className="stat-box">
                <div className="v">{sr.shipmentCount}</div>
                <div className="l">Stops</div>
              </div>
              <div className="stat-box">
                <div className="v">{sr.estimatedDistanceKm.toFixed(1)}</div>
                <div className="l">Km</div>
              </div>
              <div className="stat-box">
                <div className="v">₹{sr.netEarnings.toFixed(0)}</div>
                <div className="l">Net</div>
              </div>
              <div className="stat-box">
                <div className="v">{sr.shiftUtilisationPct != null ? `${sr.shiftUtilisationPct.toFixed(0)}%` : '—'}</div>
                <div className="l">Util</div>
              </div>
            </div>
          )}
          <p className="row-muted">Route stops (sequence order)</p>
          {routeQ.isLoading && <p>Loading route…</p>}
          {routeQ.data?.stops && (
            <ol style={{ fontSize: '0.78rem', paddingLeft: 18, maxHeight: 200, overflow: 'auto' }}>
              {routeQ.data.stops
                .slice()
                .sort((a, b) => a.sequence - b.sequence)
                .map((st) => (
                  <li key={st.sequence}>
                    #{st.sequence} {st.pincode ?? ''} {st.shipmentId ?? ''}
                  </li>
                ))}
            </ol>
          )}
          {timelineJson && (
            <>
              <p className="row-muted" style={{ marginTop: 12 }}>
                Timeline (raw)
              </p>
              <pre
                style={{
                  fontSize: '0.68rem',
                  background: '#f9fafb',
                  padding: 8,
                  borderRadius: 6,
                  maxHeight: 220,
                  overflow: 'auto',
                }}
              >
                {timelineJson}
              </pre>
            </>
          )}
        </div>
      </aside>
    </>
  )
}
