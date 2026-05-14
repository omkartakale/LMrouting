import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/endpoints'
import { useSessionStore } from '../../stores/sessionStore'
import { useRunSnapshotStore } from '../../stores/runSnapshotStore'
import { useUiStore, type MonitorTab } from '../../stores/uiStore'
import { REGION_COLORS } from '../../stores/planStore'
import type { AllocationSummary, SrSummaryDto } from '../../types/domain'
import { StepHeader } from './StepHeader'

function exportAllocationCsv(summary: AllocationSummary) {
  const headers = ['srName', 'shipmentCount', 'netEarnings', 'estimatedDistanceKm', 'shiftUtilisationPct']
  const lines = [headers.join(',')]
  for (const sr of summary.srSummaries) {
    lines.push(
      [
        sr.srName,
        sr.shipmentCount,
        sr.netEarnings.toFixed(2),
        sr.estimatedDistanceKm.toFixed(2),
        sr.shiftUtilisationPct ?? '',
      ].join(','),
    )
  }
  const blob = new Blob([lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `allocation-${summary.date}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

function TabBtn({ id, label }: { id: MonitorTab; label: string }) {
  const tab = useUiStore((s) => s.monitorTab)
  const set = useUiStore((s) => s.setMonitorTab)
  return (
    <button type="button" className={`btn ${tab === id ? 'btn-primary' : 'btn-secondary'}`} onClick={() => set(id)}>
      {label}
    </button>
  )
}

export function Step4Results() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const date = useSessionStore((s) => s.selectedDate)
  const snap = useRunSnapshotStore((s) => s.summary)
  const setSnapshot = useRunSnapshotStore((s) => s.setFromAllocation)
  const tab = useUiStore((s) => s.monitorTab)
  const openSr = useUiStore((s) => s.openSrDrawer)

  const [srFilter, setSrFilter] = useState('')
  const [regionFilter, setRegionFilter] = useState('all')
  const [timelineSr, setTimelineSr] = useState<string | null>(null)

  const live = useQuery({
    queryKey: ['summary', date],
    queryFn: () => api.summary(date!),
    enabled: !!date && !snap,
    retry: false,
  })

  const summary = snap ?? live.data

  const compareSummary = useQuery({
    queryKey: ['summaryCompare', date],
    queryFn: () => api.summary(date!),
    enabled: !!date && !!snap,
    staleTime: 60_000,
  })

  const step4Subtitle = useMemo(() => {
    if (snap && compareSummary.data) {
      const s = compareSummary.data
      if (
        snap.allocatedShipments !== s.allocatedShipments ||
        snap.unallocatedShipments !== s.unallocatedShipments ||
        snap.totalShipments !== s.totalShipments
      ) {
        return 'Viewing a saved run snapshot — the live server summary differs. Re-run from Step 3 or reload summary for matching totals.'
      }
    }
    return 'Allocation completed successfully.'
  }, [snap, compareSummary.data])

  const guidance = useMemo(() => {
    if (!summary) return null
    const chips: { key: string; label: string; kind: 'bad' | 'warn' }[] = []
    const total = summary.totalShipments
    const unalloc = summary.unallocatedShipments
    if (total > 0 && unalloc > 0) {
      const ratio = unalloc / total
      if (ratio > 0.08 || unalloc >= 15) {
        chips.push({
          key: 'unalloc',
          label: `${unalloc} unallocated`,
          kind: ratio > 0.15 || unalloc >= 40 ? 'bad' : 'warn',
        })
      } else {
        chips.push({ key: 'unalloc', label: `${unalloc} unallocated`, kind: 'warn' })
      }
    }
    if (summary.earningsImbalanceWarning) {
      chips.push({ key: 'earn', label: 'Earnings imbalance', kind: 'warn' })
    }
    const unhealthy =
      summary.regionSummaries?.filter((r) => (r.healthStatus ?? '').toLowerCase() !== 'healthy') ?? []
    if (unhealthy.length) {
      chips.push({ key: 'reg', label: `${unhealthy.length} region(s) need attention`, kind: 'warn' })
    }
    const opWarn = summary.operationalWarnings ?? []
    const cfgWarns = (summary.regionSummaries ?? []).flatMap((r) =>
      (r.configurationWarnings ?? []).map((w) => `${r.regionName}: ${w}`),
    )
    const ops = [...opWarn, ...cfgWarns].slice(0, 2)
    return { chips, ops }
  }, [summary])

  const draftQ = useQuery({
    queryKey: ['rebalanceDraft', date],
    queryFn: () => api.rebalanceDraft(date!),
    enabled: !!date && tab === 'rebalance',
  })

  const saveRebalanceMut = useMutation({
    mutationFn: () => api.rebalanceSave(date!),
    onSuccess: async () => {
      void qc.invalidateQueries({ queryKey: ['rebalanceDraft', date] })
      if (date) {
        try {
          const s = await api.summary(date)
          setSnapshot(s, useRunSnapshotStore.getState().lastPolylineMode)
        } catch {
          /* ignore */
        }
      }
    },
  })

  const loadPrev = useMutation({
    mutationFn: async () => {
      if (!date) return
      const s = await api.previousSummary(date)
      setSnapshot(s, 'osm')
    },
  })

  const finalizeMut = useMutation({
    mutationFn: async () => {
      if (date) await api.finalize(date)
    },
  })

  const undoMut = useMutation({
    mutationFn: async () => {
      if (date) await api.deleteOverrides(date)
    },
  })

  const timelineQ = useQuery({
    queryKey: ['srTimeline', date, timelineSr],
    queryFn: () => api.srTimeline(date!, timelineSr!),
    enabled: !!date && !!timelineSr && tab === 'srTimelines',
  })

  const filteredSrs = useMemo(() => {
    if (!summary?.srSummaries) return []
    const q = srFilter.trim().toLowerCase()
    return summary.srSummaries.filter((sr) => {
      if (q && !sr.srName.toLowerCase().includes(q)) return false
      if (regionFilter === 'all') return true
      const reg = summary.regionSummaries?.find((r) => r.regionName === regionFilter)
      if (!reg) return true
      return reg.assignedSrNames?.includes(sr.srName) || reg.activeSrNames?.includes(sr.srName)
    })
  }, [summary, srFilter, regionFilter])

  useEffect(() => {
    const setNames = useUiStore.getState().setMapNudgedRegionNames
    if (!date) {
      setNames([])
      return () => setNames([])
    }
    if (!summary) {
      setNames([])
      return () => setNames([])
    }
    if (regionFilter !== 'all') {
      setNames([regionFilter])
      return () => setNames([])
    }
    if (tab === 'rebalance') {
      const picked =
        summary.regionSummaries?.filter((r) => {
          const h = (r.healthStatus ?? '').toLowerCase()
          if (h && h !== 'healthy') return true
          if ((r.suggestions?.length ?? 0) > 0) return true
          if ((r.overflowShipments ?? 0) > 0) return true
          if ((r.configurationWarnings?.length ?? 0) > 0) return true
          return false
        }).map((r) => r.regionName) ?? []
      setNames(picked)
      return () => setNames([])
    }
    setNames([])
    return () => setNames([])
  }, [date, summary, regionFilter, tab])

  if (!date) {
    return (
      <div className="panel">
        <p className="row-muted">Select a date in Step 1.</p>
        <button type="button" className="btn btn-secondary" onClick={() => navigate('/allocation/step-1')}>
          Go to Step 1
        </button>
      </div>
    )
  }

  if (!summary) {
    return (
      <div className="panel">
        <StepHeader step={4} title="Allocation Results & Monitor" subtitle="Run allocation from Step 3 to see results here." />
        <p className="row-muted">No allocation snapshot yet for {date}.</p>
        <button type="button" className="btn btn-primary" onClick={() => navigate('/allocation/step-3')}>
          Go to Review (Step 3)
        </button>
      </div>
    )
  }

  const allocPct =
    summary.totalShipments > 0 ? (100 * summary.allocatedShipments) / summary.totalShipments : 0
  const unallocPct =
    summary.totalShipments > 0 ? (100 * summary.unallocatedShipments) / summary.totalShipments : 0
  const avgUtil =
    summary.srSummaries.length > 0
      ? summary.srSummaries.reduce((a, s) => a + (s.shiftUtilisationPct ?? 0), 0) / summary.srSummaries.length
      : 0

  const highest = summary.srSummaries.reduce(
    (best, s) => (s.netEarnings > (best?.netEarnings ?? -Infinity) ? s : best),
    null as SrSummaryDto | null,
  )
  const lowest = summary.srSummaries.reduce(
    (worst, s) => (s.netEarnings < (worst?.netEarnings ?? Infinity) ? s : worst),
    null as SrSummaryDto | null,
  )

  const rebCount =
    summary.regionSummaries?.reduce((acc, r) => acc + (r.suggestions?.length ?? 0), 0) ?? 0

  return (
    <div className="panel">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12, flexWrap: 'wrap' }}>
        <StepHeader step={4} title="Allocation Results & Monitor" subtitle={step4Subtitle} />
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button type="button" className="btn btn-secondary" onClick={() => exportAllocationCsv(summary)}>
            Export
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={saveRebalanceMut.isPending}
            onClick={() => {
              useUiStore.getState().setMonitorTab('rebalance')
              void draftQ.refetch()
            }}
          >
            Rebalance
          </button>
        </div>
      </div>

      <div className="metrics-row" style={{ gridTemplateColumns: 'repeat(5, minmax(0,1fr))' }}>
        <div className="metric-card">
          <div className="metric-value">{summary.totalShipments.toLocaleString()}</div>
          <div className="metric-label">Total Shipments</div>
        </div>
        <div className="metric-card">
          <div className="metric-value" style={{ color: '#065f46' }}>
            {summary.allocatedShipments.toLocaleString()} ({allocPct.toFixed(1)}%)
          </div>
          <div className="metric-label">Allocated</div>
        </div>
        <div className="metric-card">
          <div className="metric-value" style={{ color: '#b91c1c' }}>
            {summary.unallocatedShipments.toLocaleString()} ({unallocPct.toFixed(1)}%)
          </div>
          <div className="metric-label">Unallocated</div>
        </div>
        <div className="metric-card">
          <div className="metric-value">{summary.totalSrs}</div>
          <div className="metric-label">Active SRs</div>
        </div>
        <div className="metric-card">
          <div className="metric-value" style={{ color: '#065f46' }}>
            {avgUtil.toFixed(1)}%
          </div>
          <div className="metric-label">Avg. Utilization</div>
        </div>
      </div>

      {guidance &&
        (guidance.chips.length > 0 || guidance.ops.length > 0 || rebCount > 0) && (
          <div className="step4-guidance-strip" role="region" aria-label="Supervisor guidance">
            <div className="step4-guidance-chips">
              {guidance.chips.map((c) => (
                <span key={c.key} className={`step4-guidance-chip step4-guidance-chip--${c.kind}`}>
                  {c.label}
                </span>
              ))}
            </div>
            {guidance.ops.length > 0 && (
              <ul className="step4-guidance-ops">
                {guidance.ops.map((t, i) => (
                  <li key={i}>{t}</li>
                ))}
              </ul>
            )}
            <button
              type="button"
              className="btn btn-secondary"
              style={{ flexShrink: 0 }}
              onClick={() => {
                useUiStore.getState().setMonitorTab('rebalance')
                if (date) {
                  void qc.prefetchQuery({
                    queryKey: ['rebalanceDraft', date],
                    queryFn: () => api.rebalanceDraft(date),
                  })
                }
              }}
            >
              View rebalancing suggestions
            </button>
          </div>
        )}

      <div className="step4-mid">
        <div className="sr-search-row">
          <input type="search" placeholder="Search SR…" value={srFilter} onChange={(e) => setSrFilter(e.target.value)} />
          <select value={regionFilter} onChange={(e) => setRegionFilter(e.target.value)}>
            <option value="all">All Regions</option>
            {(summary.regionSummaries ?? []).map((r) => (
              <option key={r.regionName} value={r.regionName}>
                {r.regionName}
              </option>
            ))}
          </select>
        </div>
        <div className="sr-card-list">
          {filteredSrs.map((sr, i) => (
            <button key={sr.srName} type="button" className="sr-card" onClick={() => openSr(sr.srName)}>
              <span className="sr-card-dot" style={{ background: REGION_COLORS[i % REGION_COLORS.length] }} />
              <span style={{ flex: 1 }}>
                <strong>{sr.srName}</strong>
                <div className="row-muted" style={{ fontSize: '0.72rem' }}>
                  {sr.shipmentCount} stops · ₹{sr.netEarnings.toFixed(2)}
                </div>
              </span>
              <span style={{ fontWeight: 700, color: sr.shiftUtilisationPct != null && sr.shiftUtilisationPct > 90 ? '#065f46' : '#111' }}>
                {sr.shiftUtilisationPct != null ? `${sr.shiftUtilisationPct.toFixed(0)}%` : '—'}
              </span>
            </button>
          ))}
        </div>
      </div>

      <div className="monitor-tab-row">
        <TabBtn id="overview" label="Overview" />
        <TabBtn id="srTimelines" label="SR Timelines" />
        <TabBtn id="shipments" label="Shipments" />
        <TabBtn id="rebalance" label="Rebalancing" />
        <TabBtn id="earnings" label="Earnings" />
      </div>

      {tab === 'overview' && (
        <div className="stat-grid">
          <div className="stat-box">
            <div className="v">{summary.earningsVariance != null ? summary.earningsVariance.toFixed(2) : '—'}</div>
            <div className="l">Earnings variance</div>
          </div>
          <div className="stat-box">
            <div className="v">{highest?.srName ?? '—'}</div>
            <div className="l">Highest earner</div>
          </div>
          <div className="stat-box">
            <div className="v">{lowest?.srName ?? '—'}</div>
            <div className="l">Lowest earner</div>
          </div>
          <div className="stat-box">
            <div className="v">₹{summary.earningsRange.toFixed(0)}</div>
            <div className="l">Earnings range</div>
          </div>
          <div className="stat-box">
            <div className="v">{rebCount}</div>
            <div className="l">Rebalance hints</div>
          </div>
        </div>
      )}

      {tab === 'srTimelines' && (
        <div className="panel">
          <label className="row-muted" htmlFor="tl-sr">
            SR
          </label>
          <select
            id="tl-sr"
            value={timelineSr ?? ''}
            onChange={(e) => setTimelineSr(e.target.value || null)}
            style={{ width: '100%', marginTop: 6, padding: 8, borderRadius: 6, border: '1px solid var(--border)' }}
          >
            <option value="">— Select SR —</option>
            {summary.srSummaries.map((s) => (
              <option key={s.srName} value={s.srName}>
                {s.srName}
              </option>
            ))}
          </select>
          {timelineQ.isLoading && <p className="row-muted">Loading timeline…</p>}
          {timelineQ.data && (
            <pre style={{ fontSize: '0.72rem', maxHeight: 240, overflow: 'auto', background: 'var(--bg-soft)', padding: 10, borderRadius: 8 }}>
              {JSON.stringify(timelineQ.data, null, 2)}
            </pre>
          )}
        </div>
      )}

      {tab === 'shipments' && (
        <div className="panel">
          <p className="row-muted">Per-SR stop lists are available from an SR card (open details).</p>
          <p style={{ fontSize: '0.82rem' }}>
            Total allocated stops:{' '}
            <strong>{summary.srSummaries.reduce((a, s) => a + s.shipmentCount, 0)}</strong> across {summary.srSummaries.length}{' '}
            SRs.
          </p>
        </div>
      )}

      {tab === 'rebalance' && (
        <div className="panel">
          {draftQ.isLoading && <p>Loading recommendations…</p>}
          {draftQ.data && (
            <>
              <p className="row-muted">{draftQ.data.message}</p>
              <ul style={{ fontSize: '0.82rem', paddingLeft: 18 }}>
                {draftQ.data.recommendations?.map((r, i) => (
                  <li key={i}>
                    <strong>{r.srName ?? r.fromSr}</strong>: {r.reason ?? r.message ?? '—'} ({r.priority ?? '—'})
                  </li>
                ))}
              </ul>
              <button type="button" className="btn btn-primary" disabled={saveRebalanceMut.isPending} onClick={() => saveRebalanceMut.mutate()}>
                {saveRebalanceMut.isPending ? 'Applying…' : 'Apply rebalance & re-run'}
              </button>
            </>
          )}
        </div>
      )}

      {tab === 'earnings' && (
        <div className="stat-grid">
          <div className="stat-box">
            <div className="v">₹{summary.meanNetEarnings.toFixed(0)}</div>
            <div className="l">Mean net</div>
          </div>
          <div className="stat-box">
            <div className="v">₹{summary.earningsRange.toFixed(0)}</div>
            <div className="l">Range</div>
          </div>
          <div className="stat-box">
            <div className="v">{highest ? `₹${highest.netEarnings.toFixed(2)}` : '—'}</div>
            <div className="l">Top net</div>
          </div>
          <div className="stat-box">
            <div className="v">{lowest ? `₹${lowest.netEarnings.toFixed(2)}` : '—'}</div>
            <div className="l">Bottom net</div>
          </div>
        </div>
      )}

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 14 }}>
        <button type="button" className="btn btn-ghost" disabled={loadPrev.isPending} onClick={() => loadPrev.mutate()}>
          Load previous run
        </button>
        <button type="button" className="btn btn-ghost" disabled={undoMut.isPending} onClick={() => undoMut.mutate()}>
          Undo overrides
        </button>
        <button type="button" className="btn btn-ghost" disabled={finalizeMut.isPending} onClick={() => finalizeMut.mutate()}>
          Finalize
        </button>
        <button type="button" className="btn btn-secondary" onClick={() => navigate('/allocation/step-3')}>
          Back to Review
        </button>
      </div>

      <button
        type="button"
        className="btn btn-secondary step4-fab"
        title="Adjust regions or SR mapping, then return to refresh or re-run allocation."
        onClick={() => navigate('/allocation/step-2?return=' + encodeURIComponent('/allocation/step-4'))}
      >
        Edit affinity zones
      </button>
    </div>
  )
}
