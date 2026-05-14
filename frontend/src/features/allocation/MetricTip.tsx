import type { ReactNode } from 'react'

/** Metric card with native tooltip (title) for supervisor hints. */
export function MetricTip({ label, value, title, valueColor }: { label: string; value: ReactNode; title: string; valueColor?: string }) {
  return (
    <div className="metric-card" title={title}>
      <div className="metric-value" style={valueColor ? { color: valueColor } : undefined}>
        {value}
      </div>
      <div className="metric-label" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
        {label}
        <span className="metric-info" aria-hidden>
          ⓘ
        </span>
      </div>
    </div>
  )
}
