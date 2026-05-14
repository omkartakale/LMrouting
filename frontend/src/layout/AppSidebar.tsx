import { NavLink, useLocation } from 'react-router-dom'
import { useUiStore } from '../stores/uiStore'

export function AppSidebar() {
  const { pathname } = useLocation()
  const allocationActive = pathname.startsWith('/allocation')

  return (
    <nav className="app-sidebar" aria-label="Application">
      <div className="app-sidebar-brand">LM</div>
      <ul className="app-sidebar-list">
        <li>
          <NavLink
            to="/allocation/step-1"
            className={() => (allocationActive ? 'active' : '')}
            onClick={() => useUiStore.getState().setMainNav('allocation')}
          >
            <span className="app-sidebar-icon" aria-hidden>
              ◆
            </span>
            Allocation
          </NavLink>
        </li>
        <li>
          <span className="app-sidebar-link disabled" title="Coming soon">
            <span className="app-sidebar-icon" aria-hidden>
              ○
            </span>
            Monitor
          </span>
        </li>
        <li>
          <span className="app-sidebar-link disabled" title="Coming soon">
            <span className="app-sidebar-icon" aria-hidden>
              ○
            </span>
            Analytics
          </span>
        </li>
        <li>
          <NavLink
            to="/settings"
            className={({ isActive }) => (isActive ? 'active' : '')}
            onClick={() => useUiStore.getState().setMainNav('settings')}
          >
            <span className="app-sidebar-icon" aria-hidden>
              ⚙
            </span>
            Settings
          </NavLink>
        </li>
      </ul>
      <div className="app-sidebar-footer">
        <span className="app-sidebar-link disabled" title="Coming soon">
          <span className="app-sidebar-icon" aria-hidden>
            ?
          </span>
          Help &amp; Support
        </span>
      </div>
    </nav>
  )
}
