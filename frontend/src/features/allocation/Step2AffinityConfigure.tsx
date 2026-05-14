import { useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useSessionStore } from '../../stores/sessionStore'
import { usePlanStore } from '../../stores/planStore'
import { PlanZones } from '../plan/PlanZones'
import { PlanCrew } from '../plan/PlanCrew'
import { StepHeader } from './StepHeader'
import { safeReturnPath } from './safeReturnPath'

export function Step2AffinityConfigure() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const returnTo = safeReturnPath(searchParams.get('return'))
  const date = useSessionStore((s) => s.selectedDate)
  const allocationMode = useSessionStore((s) => s.allocationMode)
  const setAllocationMode = useSessionStore((s) => s.setAllocationMode)

  useEffect(() => {
    void usePlanStore
      .getState()
      .loadFromServer()
      .finally(() => {
        const st = usePlanStore.getState()
        if (Object.keys(st.regions).length === 0) st.applyRegionCount()
      })
  }, [])

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

  return (
    <div className="panel">
      <StepHeader step={2} title="Configure Affinity" subtitle="Choose allocation type and map SRs to regions" />

      <div className="alloc-toggle-row">
        <button
          type="button"
          className={`alloc-toggle ${allocationMode === 'count-based' ? 'selected' : ''}`}
          onClick={() => setAllocationMode('count-based')}
        >
          <strong>Count-Based</strong>
          <div className="row-muted" style={{ marginTop: 6 }}>
            Fixed shipment count per SR
          </div>
        </button>
        <button
          type="button"
          className={`alloc-toggle ${allocationMode === 'time-based' ? 'selected' : ''}`}
          onClick={() => setAllocationMode('time-based')}
        >
          <strong>Time-Based</strong>
          <div className="row-muted" style={{ marginTop: 6 }}>
            Based on SR shift duration
          </div>
        </button>
      </div>

      <div className="step2-split-heading">
        <h2 className="panel-title" style={{ margin: 0 }}>
          SR to Region Mapping
        </h2>
        <span className="row-muted" style={{ fontSize: '0.78rem' }}>
          Use regions below, then draw on the map
        </span>
      </div>

      <PlanCrew />

      <PlanZones />

      {returnTo && (
        <div className="info-banner" style={{ marginTop: 14 }}>
          <span>
            You opened this screen from results. Save crew and region mapping if you changed anything, then return to
            results. You may need to re-run allocation from Step 3 so the map and totals stay in sync.
          </span>
        </div>
      )}

      <div className="wizard-footer">
        <button type="button" className="btn btn-secondary" onClick={() => navigate('/allocation/step-1')}>
          Back
        </button>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, justifyContent: 'flex-end' }}>
          {returnTo && (
            <button type="button" className="btn btn-secondary" onClick={() => navigate(returnTo)}>
              Back to results
            </button>
          )}
          <button type="button" className="btn btn-primary" onClick={() => navigate('/allocation/step-3')}>
            Next
          </button>
        </div>
      </div>
    </div>
  )
}
