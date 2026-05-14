import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/endpoints'
import { runAllocation } from '../../lib/allocationRun'
import { useSessionStore } from '../../stores/sessionStore'
import { usePlanStore, REGION_COLORS } from '../../stores/planStore'
import { useRunSnapshotStore } from '../../stores/runSnapshotStore'
import type { AllocationSummary } from '../../types/domain'
import { MetricTip } from './MetricTip'
import { StepHeader } from './StepHeader'

const EST_CAP_PER_SR = 540

const T_TOTAL =
  'Shipments loaded for this operating date from your CSV (all rows in memory for the date). Not split by allocation outcome until you run.'
const T_SRS = 'Present SRs vs total registered for this date (attendance). Only present SRs are included in the rough capacity estimate below.'
const T_CAP =
  `Rough pre-run estimate: present SRs × ${EST_CAP_PER_SR} stops. Not the engine’s true capacity model—used only to sanity-check scale before you run.`
const T_UTIL =
  'Estimated share of that rough capacity filled by total shipments (capped at 100%). A low % usually means the 540/SR heuristic is larger than today’s volume—not a problem by itself.'
const T_UNALLOC =
  'Max(0, total shipments minus rough capacity). Often 0 when the heuristic capacity is high. Authoritative unallocated count appears in Step 4 after you run allocation.'
const T_PRIORITY =
  'P0/P1/P2 shares come from the last saved allocation summary for this date, not a forecast. Run once to populate, or run again after changes.'

export function Step3Review() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const date = useSessionStore((s) => s.selectedDate)
  const routingKind = useSessionStore((s) => s.routingKind)
  const allocationMode = useSessionStore((s) => s.allocationMode)
  const allocationEngine = useSessionStore((s) => s.allocationEngine)
  const regions = usePlanStore((s) => s.regions)
  const srZoneMap = usePlanStore((s) => s.srZoneMap)
  const getSrCounts = usePlanStore((s) => s.getSrCounts)
  const setSnapshot = useRunSnapshotStore((s) => s.setFromAllocation)

  const [runErr, setRunErr] = useState<string | null>(null)

  const drawnKeys = useMemo(
    () =>
      Object.entries(regions)
        .filter(([, r]) => r.latlngs.length >= 3)
        .map(([k]) => k),
    [regions],
  )

  const attQ = useQuery({
    queryKey: ['attendance', date],
    queryFn: () => api.attendance(date!),
    enabled: !!date,
  })

  const countQ = useQuery({
    queryKey: ['csvCount', date],
    queryFn: () => api.csvCount(date!),
    enabled: !!date,
  })

  const summaryQ = useQuery({
    queryKey: ['summary', date],
    queryFn: () => api.summary(date!),
    enabled: !!date,
    retry: false,
  })

  const presentList = attQ.data?.filter((a) => a.present) ?? []
  const presentCount = presentList.length
  const totalSr = attQ.data?.length ?? 0
  const totalShipments = countQ.data?.total ?? 0

  const totalCapacity = presentCount * EST_CAP_PER_SR
  const expectedUtilPct =
    totalCapacity > 0 && totalShipments > 0 ? Math.min(100, (totalShipments / totalCapacity) * 100) : 0
  const unallocatedEst = Math.max(0, totalShipments - totalCapacity)

  const zoneSrOk =
    routingKind !== 'affinity' ||
    allocationMode !== 'time-based' ||
    (drawnKeys.length > 0 && Object.values(srZoneMap).some((z) => drawnKeys.includes(z)))

  const hasDate = !!date
  const capVsShip = totalShipments === 0 || totalCapacity > 0

  const mappedOk =
    routingKind !== 'affinity' ||
    allocationMode === 'count-based' ||
    Object.values(srZoneMap).some((z) => drawnKeys.includes(z))

  const allValid = hasDate && (routingKind !== 'affinity' || (drawnKeys.length > 0 && zoneSrOk && mappedOk))

  const runMut = useMutation({
    mutationFn: async (): Promise<AllocationSummary> => {
      if (!date) throw new Error('Pick a date')
      const ctx = {
        date,
        routingKind,
        allocationMode,
        regions,
        srZoneMap,
        getSrCounts,
      }
      return runAllocation(ctx)
    },
    onSuccess: (summary) => {
      setSnapshot(summary, allocationEngine)
      void qc.invalidateQueries({ queryKey: ['summary', date] })
      setRunErr(null)
      navigate('/allocation/step-4')
    },
    onError: (e: Error) => setRunErr(e.message),
  })

  const pc = summaryQ.data?.priorityCounts

  if (!date) {
    return (
      <div className="panel">
        <p className="row-muted">Select a shipment date in Step 1 first.</p>
        <button type="button" className="btn btn-secondary" onClick={() => navigate('/allocation/step-1')}>
          Back to Step 1
        </button>
      </div>
    )
  }

  const regionSummaries = drawnKeys.map((name, idx) => {
    const n = getSrCounts()[name] ?? 0
    const cap = n * EST_CAP_PER_SR
    return (
      <div key={name} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, fontSize: '0.85rem' }}>
        <span className="sr-card-dot" style={{ background: regions[name]?.color ?? REGION_COLORS[idx % REGION_COLORS.length] }} />
        <span style={{ flex: 1, fontWeight: 600 }}>{name}</span>
        <span className="row-muted">{n} SRs</span>
        <span className="row-muted">{cap.toLocaleString()} cap</span>
      </div>
    )
  })

  return (
    <div className="panel">
      <StepHeader step={3} title="Review & Validate" subtitle="Review configuration before running allocation." />

      <p className="row-muted" style={{ marginBottom: 10 }}>
        Pre-run cards below use <strong>approximations</strong> where noted. After you run allocation, use <strong>Step 4</strong> for authoritative
        allocated/unallocated counts and utilisation.
      </p>

      <div className="metrics-row">
        <MetricTip label="Total Shipments" value={totalShipments.toLocaleString()} title={T_TOTAL} />
        <MetricTip label="Active SRs" value={`${presentCount} of ${totalSr || '—'}`} title={T_SRS} />
        <MetricTip label="Total Capacity (est.)" value={totalCapacity.toLocaleString()} title={T_CAP} />
        <MetricTip label="Expected Utilization" value={`${expectedUtilPct.toFixed(1)}%`} title={T_UTIL} valueColor="#065f46" />
        <MetricTip label="Unallocated (Est.)" value={unallocatedEst.toLocaleString()} title={T_UNALLOC} valueColor="#b91c1c" />
      </div>

      <div className="review-columns">
        <div className="panel">
          <div className="panel-title">Region summary</div>
          {routingKind === 'affinity' && drawnKeys.length ? (
            regionSummaries
          ) : (
            <p className="row-muted">Regions appear here for affinity runs with drawn polygons.</p>
          )}
        </div>
        <div className="panel">
          <div className="panel-title">Priority breakdown</div>
          {pc ? (
            <>
              <div className="row-muted" style={{ marginBottom: 4 }}>
                P0 (Highest): {pc.p0Total} ({totalShipments ? ((100 * pc.p0Total) / totalShipments).toFixed(1) : 0}%)
              </div>
              <div className="priority-bar">
                <span style={{ width: `${totalShipments ? (100 * pc.p0Total) / totalShipments : 0}%`, background: '#b91c1c' }} />
              </div>
              <div className="row-muted" style={{ margin: '10px 0 4px' }}>
                P1: {pc.p1Total} ({totalShipments ? ((100 * pc.p1Total) / totalShipments).toFixed(1) : 0}%)
              </div>
              <div className="priority-bar">
                <span style={{ width: `${totalShipments ? (100 * pc.p1Total) / totalShipments : 0}%`, background: '#ea580c' }} />
              </div>
              <div className="row-muted" style={{ margin: '10px 0 4px' }}>
                P2: {pc.p2Total} ({totalShipments ? ((100 * pc.p2Total) / totalShipments).toFixed(1) : 0}%)
              </div>
              <div className="priority-bar">
                <span style={{ width: `${totalShipments ? (100 * pc.p2Total) / totalShipments : 0}%`, background: '#2563eb' }} />
              </div>
            </>
          ) : (
            <p className="row-muted" title={T_PRIORITY}>
              Priority mix appears after at least one allocation exists for this date. (Hover for details.)
            </p>
          )}
        </div>
        <div className="panel">
          <div className="panel-title">Validation checks</div>
          <div className={`checklist-item ${hasDate ? 'ok' : 'bad'}`}>
            {hasDate ? '✓' : '✗'} Date selected
          </div>
          <div className={`checklist-item ${routingKind !== 'affinity' || mappedOk ? 'ok' : 'bad'}`}>
            {routingKind !== 'affinity' || mappedOk ? '✓' : '✗'} SRs mapped to regions (affinity)
          </div>
          <div className={`checklist-item ${routingKind !== 'affinity' || drawnKeys.length ? 'ok' : 'bad'}`}>
            {routingKind !== 'affinity' || drawnKeys.length ? '✓' : '✗'} Region capacity / polygons (affinity)
          </div>
          <div className={`checklist-item ${capVsShip ? 'ok' : 'bad'}`}>
            {capVsShip ? '✓' : '✗'} Total capacity vs shipments
          </div>
          <div className={`checklist-item ${zoneSrOk ? 'ok' : 'bad'}`}>
            {zoneSrOk ? '✓' : '✗'} Time-based SR-zone mapping
          </div>
        </div>
      </div>

      <div className="info-banner">
        <span aria-hidden>💡</span>
        <span>
          {allValid
            ? 'Looks good! You can now run the allocation.'
            : 'Fix the items marked in red before running allocation.'}
        </span>
      </div>

      {runErr && <p style={{ color: '#b91c1c' }}>{runErr}</p>}

      <div className="wizard-footer">
        <button type="button" className="btn btn-secondary" onClick={() => navigate('/allocation/step-2')}>
          Back
        </button>
        <button
          type="button"
          className="btn btn-primary"
          disabled={!allValid || runMut.isPending}
          onClick={() => runMut.mutate()}
        >
          {runMut.isPending ? 'Running…' : '▶ Run Allocation'}
        </button>
      </div>
    </div>
  )
}
