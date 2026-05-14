import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../../api/endpoints'
import { useSessionStore } from '../../stores/sessionStore'
import { StepHeader } from './StepHeader'

export function Step1Mode() {
  const navigate = useNavigate()
  const routingKind = useSessionStore((s) => s.routingKind)
  const selectedDate = useSessionStore((s) => s.selectedDate)
  const setRoutingKind = useSessionStore((s) => s.setRoutingKind)
  const setSelectedDate = useSessionStore((s) => s.setSelectedDate)

  const datesQ = useQuery({ queryKey: ['csvDates'], queryFn: () => api.csvDates() })

  const canContinue = !!selectedDate && !!routingKind

  return (
    <div className="panel">
      <StepHeader step={1} title="Choose Allocation Mode" subtitle="Select how you want to allocate shipments." />

      <div className="mode-cards">
        <button
          type="button"
          className={`mode-card ${routingKind === 'standard' ? 'selected' : ''}`}
          onClick={() => setRoutingKind('standard')}
        >
          <div aria-hidden>📍</div>
          <h3>Standard Routing</h3>
          <p className="row-muted">Allocate shipments using standard routing</p>
          <ul>
            <li>OSM Routing</li>
            <li>Google Maps Routing</li>
          </ul>
          <span className={`btn ${routingKind === 'standard' ? 'btn-primary' : 'btn-secondary'}`} style={{ pointerEvents: 'none' }}>
            {routingKind === 'standard' ? 'Selected' : 'Select'}
          </span>
        </button>
        <button
          type="button"
          className={`mode-card ${routingKind === 'affinity' ? 'selected' : ''}`}
          onClick={() => setRoutingKind('affinity')}
        >
          <div aria-hidden>🔗</div>
          <h3>Affinity Routing</h3>
          <p className="row-muted">Allocate shipments based on affinity regions</p>
          <ul>
            <li>Count-Based Allocation</li>
            <li>Time-Based Allocation</li>
          </ul>
          <span className={`btn ${routingKind === 'affinity' ? 'btn-primary' : 'btn-secondary'}`} style={{ pointerEvents: 'none' }}>
            {routingKind === 'affinity' ? 'Selected' : 'Select'}
          </span>
        </button>
      </div>

      <div style={{ marginTop: 18 }}>
        <label htmlFor="step1-date" className="row-muted" style={{ display: 'block', marginBottom: 6 }}>
          Shipment date
        </label>
        <select
          id="step1-date"
          value={selectedDate}
          onChange={(e) => setSelectedDate(e.target.value)}
          style={{ width: '100%', maxWidth: 360, padding: 10, borderRadius: 8, border: '1px solid var(--border)' }}
        >
          <option value="">— Select date —</option>
          {(datesQ.data ?? []).map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
        <p className="row-muted" style={{ marginTop: 8 }}>
          No dates?{' '}
          <Link to="/settings">Import shipments in Settings</Link>.
        </p>
      </div>

      <div className="info-banner">
        <span aria-hidden>ℹ️</span>
        <span>
          {routingKind === 'affinity'
            ? 'Affinity Routing allows you to map SRs to specific regions and allocate based on count or time.'
            : 'Standard Routing runs the full optimiser across the hub without custom affinity polygons.'}
        </span>
      </div>

      <div className="wizard-footer">
        <span />
        <button type="button" className="btn btn-primary" disabled={!canContinue} onClick={() => navigate('/allocation/step-2')}>
          Continue
        </button>
      </div>
    </div>
  )
}
