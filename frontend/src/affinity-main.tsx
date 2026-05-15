import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import AffinitySetupPage from './routes/AffinitySetupPage';

// Entry point for affinity-setup.html. Same React + legacy-runtime pattern as
// the main allocation page.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AffinitySetupPage />
  </StrictMode>,
);
