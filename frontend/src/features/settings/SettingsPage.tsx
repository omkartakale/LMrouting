import { DataImportPanel } from './DataImportPanel'

export function SettingsPage() {
  return (
    <div className="panel">
      <h1 className="step-title" style={{ marginBottom: 6 }}>
        Settings
      </h1>
      <p className="step-sub" style={{ marginBottom: 16 }}>
        Import shipment files and manage known operating dates.
      </p>
      <DataImportPanel />
    </div>
  )
}
