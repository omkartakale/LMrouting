import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './layout/AppShell'
import { Step1Mode } from './features/allocation/Step1Mode'
import { Step2Router } from './features/allocation/Step2Router'
import { Step3Review } from './features/allocation/Step3Review'
import { Step4Results } from './features/allocation/Step4Results'
import { SettingsPage } from './features/settings/SettingsPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<AppShell />}>
        <Route index element={<Navigate to="/allocation/step-1" replace />} />
        <Route path="allocation/step-1" element={<Step1Mode />} />
        <Route path="allocation/step-2" element={<Step2Router />} />
        <Route path="allocation/step-3" element={<Step3Review />} />
        <Route path="allocation/step-4" element={<Step4Results />} />
        <Route path="settings" element={<SettingsPage />} />
        <Route path="upload" element={<Navigate to="/settings" replace />} />
        <Route path="plan/*" element={<Navigate to="/allocation/step-1" replace />} />
        <Route path="run" element={<Navigate to="/allocation/step-3" replace />} />
        <Route path="review" element={<Navigate to="/allocation/step-4" replace />} />
        <Route path="handoff" element={<Navigate to="/allocation/step-4" replace />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
