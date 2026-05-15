import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import AllocationPage from './routes/AllocationPage';

// Entry point for index.html — the Shipment Allocation Optimizer v3 SPA.
// React owns the layout; the legacy allocation runtime (legacy-allocation.ts)
// boots on mount via useEffect and operates against the same DOM IDs used by
// the original static page. This preserves 100% of the original flow and API
// contracts with the Spring Boot backend.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AllocationPage />
  </StrictMode>,
);
