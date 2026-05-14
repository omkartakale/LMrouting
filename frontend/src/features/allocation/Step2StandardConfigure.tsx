import { useQuery } from '@tanstack/react-query'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../../api/endpoints'
import { useSessionStore } from '../../stores/sessionStore'
import { StepHeader } from './StepHeader'
import { safeReturnPath } from './safeReturnPath'

export function Step2StandardConfigure() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const returnTo = safeReturnPath(searchParams.get('return'))
  const date = useSessionStore((s) => s.selectedDate)
  const allocationEngine = useSessionStore((s) => s.allocationEngine)
  const setAllocationEngine = useSessionStore((s) => s.setAllocationEngine)
  const cfgQ = useQuery({ queryKey: ['config'], queryFn: () => api.config() })

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
      <StepHeader step={2} title="Configure Standard Run" subtitle="Confirm the operating date and routing engine." />

      <div className="panel" style={{ background: 'var(--bg-soft)', marginBottom: 12 }}>
        <div className="panel-title" style={{ fontSize: '0.9rem' }}>
          Operating date
        </div>
        <p style={{ margin: 0, fontWeight: 600 }}>{date}</p>
        <p className="row-muted" style={{ marginTop: 8 }}>
          Standard allocation uses the full optimiser for all present SRs. Switch to Affinity in Step 1 if you need custom
          regions.
        </p>
      </div>

      <div className="panel-title">Routing engine</div>
      <p className="row-muted" style={{ marginBottom: 10 }}>
        This choice is used when you run allocation from Step 3.
      </p>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
        <button
          type="button"
          className={`btn ${allocationEngine === 'osm' ? 'btn-primary' : 'btn-secondary'}`}
          onClick={() => setAllocationEngine('osm')}
        >
          OpenStreetMap (ORS)
        </button>
        <button
          type="button"
          className={`btn ${allocationEngine === 'google' ? 'btn-google' : 'btn-secondary'}`}
          disabled={!cfgQ.data?.googleMapsConfigured}
          title={!cfgQ.data?.googleMapsConfigured ? 'Configure Google Maps API key on the server' : ''}
          onClick={() => setAllocationEngine('google')}
        >
          Google Maps
        </button>
      </div>

      {returnTo && (
        <div className="info-banner" style={{ marginTop: 14 }}>
          <span>
            You opened this screen from results. Save any changes here before returning. Re-run allocation from Step 3 if
            you need refreshed routes and totals.
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
