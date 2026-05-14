import { create } from 'zustand'

export type MainNav = 'allocation' | 'settings'

export type MonitorTab = 'overview' | 'srTimelines' | 'shipments' | 'rebalance' | 'earnings'

interface UiState {
  mainNav: MainNav
  selectedSrName: string | null
  drawerOpen: boolean
  monitorTab: MonitorTab
  mobileRailOpen: boolean
  mapLayerRegions: boolean
  mapLayerRoutes: boolean
  mapLayerStops: boolean
  /** When true with `mapLayerStops`, load every SR’s stops (clustered). */
  mapLayerStopsAll: boolean
  mapPriorityP0: boolean
  mapPriorityP1: boolean
  mapPriorityP2: boolean
  /** Region names whose polygons get a stronger outline on the map (Step 4 filter / rebalance). */
  mapNudgedRegionNames: string[]
  openSrDrawer: (srName: string) => void
  closeDrawer: () => void
  setMonitorTab: (t: MonitorTab) => void
  setMobileRailOpen: (v: boolean) => void
  setMainNav: (n: MainNav) => void
  setMapLayerRegions: (v: boolean) => void
  setMapLayerRoutes: (v: boolean) => void
  setMapLayerStops: (v: boolean) => void
  setMapLayerStopsAll: (v: boolean) => void
  setMapPriorityP0: (v: boolean) => void
  setMapPriorityP1: (v: boolean) => void
  setMapPriorityP2: (v: boolean) => void
  setMapNudgedRegionNames: (names: string[]) => void
}

export const useUiStore = create<UiState>((set) => ({
  mainNav: 'allocation',
  selectedSrName: null,
  drawerOpen: false,
  monitorTab: 'overview',
  mobileRailOpen: true,
  mapLayerRegions: true,
  mapLayerRoutes: true,
  mapLayerStops: false,
  mapLayerStopsAll: false,
  mapPriorityP0: true,
  mapPriorityP1: true,
  mapPriorityP2: true,
  mapNudgedRegionNames: [],
  openSrDrawer: (srName) => set({ selectedSrName: srName, drawerOpen: true }),
  closeDrawer: () => set({ selectedSrName: null, drawerOpen: false }),
  setMonitorTab: (monitorTab) => set({ monitorTab }),
  setMobileRailOpen: (mobileRailOpen) => set({ mobileRailOpen }),
  setMainNav: (mainNav) => set({ mainNav }),
  setMapLayerRegions: (mapLayerRegions) => set({ mapLayerRegions }),
  setMapLayerRoutes: (mapLayerRoutes) => set({ mapLayerRoutes }),
  setMapLayerStops: (mapLayerStops) =>
    set(mapLayerStops ? { mapLayerStops } : { mapLayerStops: false, mapLayerStopsAll: false }),
  setMapLayerStopsAll: (mapLayerStopsAll) => set({ mapLayerStopsAll }),
  setMapPriorityP0: (mapPriorityP0) => set({ mapPriorityP0 }),
  setMapPriorityP1: (mapPriorityP1) => set({ mapPriorityP1 }),
  setMapPriorityP2: (mapPriorityP2) => set({ mapPriorityP2 }),
  setMapNudgedRegionNames: (mapNudgedRegionNames) => set({ mapNudgedRegionNames }),
}))
