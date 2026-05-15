import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/endpoints'
import { useSessionStore } from '../../stores/sessionStore'
import { useRunSnapshotStore } from '../../stores/runSnapshotStore'

/**
 * Read-only analytics view over the current allocation snapshot.
 * Pulls from runSnapshotStore first (the page Step 4 already cached);
 * falls back to /api/allocate/{date}/summary if needed.
 *
 * Intentionally lightweight — extends naturally as we add backend analytics
 * endpoints. Does NOT mutate session/allocation state.
 */
export function AnalyticsPage() {
  const navigate = useNavigate()
  const date = useSessionStore((s) => s.selectedDate)
  const snap = useRunSnapshotStore((s) => s.summary)

  const live = useQuery({
    queryKey: ['summary', date],
    queryFn: () => api.getAllocationSummary(date!),
    enabled: !!date && !snap,
    retry: false,
  })

  const summary = snap ?? live.data

  const stats = useMemo(() => {
    if (!summary) return null
    const total = summary.totalShipments
    const allocated = summary.allocatedShipments
    const unallocated = summary.unallocatedShipments
    const allocPct = total > 0 ? (100 * allocated) / total : 0
    const unallocPct = total > 0 ? (100 * unallocated) / total : 0
    const avgUtil =
      summary.srSummaries.length > 0
        ? summary.srSummaries.reduce((a, s) => a + (s.shiftUtilisationPct ?? 0), 0) /
          summary.srSummaries.length
        : 0
    const highest = summary.srSummaries.reduce(
      (best, s) => (s.netEarnings > (best?.netEarnings ?? -Infinity) ? s : best),
      null as (typeof summary.srSummaries)[number] | null,
    )
    const lowest = summary.srSummaries.reduce(
      (worst, s) => (s.netEarnings < (worst?.netEarnings ?? Infinity) ? s : worst),
      null as (typeof summary.srSummaries)[number] | null,
    )
    const rebHints =
      summary.regionSummaries?.reduce((acc, r) => acc + (r.suggestions?.length ?? 0), 0) ?? 0
    const unhealthy =
      summary.regionSummaries?.filter((r) => (r.healthStatus ?? '').toLowerCase() !== 'healthy') ??
      []
    return {
      total,
      allocated,
      unallocated,
      allocPct,
      unallocPct,
      avgUtil,
      highest,
      lowest,
      rebHints,
      unhealthyCount: unhealthy.length,
      meanNet: summary.meanNetEarnings,
      earningsRange: summary.earningsRange,
      earningsVariance: summary.earningsVariance,
    }
  }, [summary])

  if (!date) {
    return (
      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Analytics</h2>
        <p className="row-muted">Select a date on Allocation Step 1 to populate analytics.</p>
        <button type="button" className="btn btn-secondary" onClick={() => navigate('/allocation/step-1')}>
          Go to Step 1
        </button>
      </div>
    )
  }

  if (!summary || !stats) {
    return (
      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Analytics</h2>
        <p className="row-muted">No allocation run yet for {date}. Run allocation from Step 3 to see analytics.</p>
        <button type="button" className="btn btn-primary" onClick={() => navigate('/allocation/step-3')}>
          Go to Step 3
        </button>
      </div>
    )
  }

  return (
    <div className="panel">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
        <div>
          <h2 style={{ margin: 0 }}>Analytics</h2>
          <p className="row-muted" style={{ margin: 0, fontSize: '0.78rem' }}>
            Snapshot for {date}
          </p>
        </div>
        <button type="button" className="btn btn-secondary" onClick={() => navigate('/allocation/step-4')}>
          Open results
        </button>
      </div>

      <div className="metrics-row" style={{ gridTemplateColumns: 'repeat(4, minmax(0,1fr))', marginTop: 14 }}>
        <div className="metric-card">
          <div className="metric-value">{stats.total.toLocaleString()}</div>
          <div className="metric-label">Total shipments</div>
        </div>
        <div className="metric-card">
          <div className="metric-value" style={{ color: '#065f46' }}>
            {stats.allocated.toLocaleString()} ({stats.allocPct.toFixed(1)}%)
          </div>
          <div className="metric-label">Allocated</div>
        </div>
        <div className="metric-card">
          <div className="metric-value" style={{ color: '#b91c1c' }}>
            {stats.unallocated.toLocaleString()} ({stats.unallocPct.toFixed(1)}%)
          </div>
          <div className="metric-label">Unallocated</div>
        </div>
        <div className="metric-card">
          <div className="metric-value">{stats.avgUtil.toFixed(1)}%</div>
          <div className="metric-label">Avg. utilization</div>
        </div>
      </div>

      <h3 style={{ marginBottom: 6 }}>Earnings</h3>
      <div className="stat-grid">
        <div className="stat-box">
          <div className="v">₹{stats.meanNet.toFixed(0)}</div>
          <div className="l">Mean net</div>
        </div>
        <div className="stat-box">
          <div className="v">₹{stats.earningsRange.toFixed(0)}</div>
          <div className="l">Range</div>
        </div>
        <div className="stat-box">
          <div className="v">{stats.earningsVariance != null ? stats.earningsVariance.toFixed(2) : '—'}</div>
          <div className="l">Variance</div>
        </div>
        <div className="stat-box">
          <div className="v">{stats.highest?.srName ?? '—'}</div>
          <div className="l">Highest earner</div>
        </div>
        <div className="stat-box">
          <div className="v">{stats.lowest?.srName ?? '—'}</div>
          <div className="l">Lowest earner</div>
        </div>
      </div>

      <h3 style={{ marginBottom: 6 }}>Operational health</h3>
      <div className="stat-grid">
        <div className="stat-box">
          <div className="v">{summary.totalSrs}</div>
          <div className="l">Active SRs</div>
        </div>
        <div className="stat-box">
          <div className="v">{stats.rebHints}</div>
          <div className="l">Rebalance hints</div>
        </div>
        <div className="stat-box">
          <div className="v">{stats.unhealthyCount}</div>
          <div className="l">Regions needing attention</div>
        </div>
        <div className="stat-box">
          <div className="v">{(summary.operationalWarnings ?? []).length}</div>
          <div className="l">Operational warnings</div>
        </div>
      </div>

      {(summary.operationalWarnings ?? []).length > 0 && (
        <ul style={{ fontSize: '0.82rem', paddingLeft: 18, marginTop: 8 }}>
          {summary.operationalWarnings!.slice(0, 6).map((w, i) => (
            <li key={i}>{w}</li>
          ))}
        </ul>
      )}

      <p className="row-muted" style={{ fontSize: '0.72rem', marginTop: 16 }}>
        Analytics are derived from the current allocation snapshot. Re-run from Step 3 to refresh.
      </p>
    </div>
  )
}
