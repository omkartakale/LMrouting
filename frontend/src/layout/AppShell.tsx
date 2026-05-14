import { Outlet, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import { AppSidebar } from './AppSidebar'
import { MapCanvas } from '../map/MapCanvas'
import { DetailDrawer } from './DetailDrawer'
import { useUiStore } from '../stores/uiStore'
import { useSessionStore } from '../stores/sessionStore'
import { pathShowsMap } from './shellPaths'

export function AppShell() {
  const { pathname } = useLocation()
  const routingKind = useSessionStore((s) => s.routingKind)
  const showMap = pathShowsMap(pathname, routingKind)
  const mobileRailOpen = useUiStore((s) => s.mobileRailOpen)
  const setMobileRailOpen = useUiStore((s) => s.setMobileRailOpen)

  const { data: hub } = useQuery({ queryKey: ['hub'], queryFn: () => api.hub() })

  return (
    <div className="app-root">
      <header className="app-header portal-header">
        <div className="portal-header-title">
          <span className="portal-logo" aria-hidden>
            🚚
          </span>
          <div>
            <div className="portal-title">LM Allocation</div>
            <div className="portal-subtitle">Last Mile Allocation Portal</div>
          </div>
        </div>
        <div className="portal-header-user">
          <button type="button" className="icon-btn" aria-label="Notifications">
            🔔
          </button>
          <div className="portal-user-block">
            <div className="portal-user-name">Hub Supervisor</div>
            <div className="portal-user-meta">{hub ? `Hub: ${hub.hubName}` : 'Hub'}</div>
          </div>
        </div>
      </header>
      <div className="app-body portal-body">
        <AppSidebar />
        <div className="portal-workspace">
          <button
            type="button"
            className="rail-toggle btn btn-secondary"
            onClick={() => setMobileRailOpen(!mobileRailOpen)}
          >
            {mobileRailOpen ? 'Hide panel' : 'Show panel'}
          </button>
          <div className={`workspace-inner ${showMap ? 'with-map' : 'full-width'}`}>
            <aside className={`content-rail ${mobileRailOpen ? 'open' : ''} ${showMap ? '' : 'expanded'}`}>
              <Outlet />
            </aside>
            {showMap && (
              <main className="map-pane">
                <MapCanvas />
              </main>
            )}
          </div>
        </div>
        <DetailDrawer />
      </div>
    </div>
  )
}
