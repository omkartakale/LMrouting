import { create } from 'zustand'
import { api } from '../api/endpoints'

/**
 * Stable, distinct hues for affinity regions (moderate saturation / mid lightness
 * so overlaps stay readable and not neon). Index 0 = Region 1, etc.
 */
export function affinityRegionColor(indexZeroBased: number): string {
  const i = ((indexZeroBased % 256) + 256) % 256
  const hue = (i * 137.508046) % 360
  return `hsl(${Math.round(hue)}, 52%, 48%)`
}

/** Precomputed swatches for list UIs that expect an array (same formula). */
export const REGION_COLORS: string[] = Array.from({ length: 40 }, (_, i) => affinityRegionColor(i))

function regionColorIndexFromName(name: string, fallbackIndex: number): number {
  const m = /^Region\s+(\d+)\s*$/i.exec(String(name).trim())
  if (m) return Math.max(0, parseInt(m[1], 10) - 1)
  return fallbackIndex
}

export type LatLng = [number, number]

export interface RegionShape {
  latlngs: LatLng[]
  color: string
}

interface PlanState {
  regionCount: number
  regions: Record<string, RegionShape>
  srZoneMap: Record<string, string>
  shiftDurations: Record<string, number>
  activeDrawRegion: string | null
  dirty: boolean
  lastSavedAt: number | null
  setRegionCountInput: (n: number) => void
  applyRegionCount: () => void
  setActiveDrawRegion: (name: string | null) => void
  setPolygonForRegion: (name: string, latlngs: LatLng[], color: string) => void
  clearRegion: (name: string) => void
  setSrZone: (sr: string, zone: string) => void
  setShift: (sr: string, minutes: number) => void
  hydrateShifts: (m: Record<string, number>) => void
  loadFromServer: () => Promise<void>
  saveCrewAndZones: (hubName: string) => Promise<void>
  getSrCounts: () => Record<string, number>
  buildAffinityPayload: (hubName: string) => unknown
  persistRegions: (hubName: string) => Promise<void>
}

function emptyRegionsForCount(n: number): Record<string, RegionShape> {
  const out: Record<string, RegionShape> = {}
  for (let i = 1; i <= n; i++) {
    const name = `Region ${i}`
    out[name] = { latlngs: [], color: affinityRegionColor(i - 1) }
  }
  return out
}

export const usePlanStore = create<PlanState>((set, get) => ({
  regionCount: 3,
  regions: {},
  srZoneMap: {},
  shiftDurations: {},
  activeDrawRegion: null,
  dirty: false,
  lastSavedAt: null,

  setRegionCountInput: (regionCount) => set({ regionCount }),

  applyRegionCount: () => {
    const n = Math.min(20, Math.max(1, get().regionCount))
    set({
      regionCount: n,
      regions: emptyRegionsForCount(n),
      srZoneMap: {},
      activeDrawRegion: null,
      dirty: true,
    })
  },

  setActiveDrawRegion: (activeDrawRegion) => set({ activeDrawRegion }),

  setPolygonForRegion: (name, latlngs, color) => {
    set((s) => ({
      regions: { ...s.regions, [name]: { latlngs, color } },
      activeDrawRegion: null,
      dirty: true,
    }))
  },

  clearRegion: (name) => {
    set((s) => {
      const regions = { ...s.regions }
      if (regions[name]) regions[name] = { ...regions[name], latlngs: [] }
      const srZoneMap = { ...s.srZoneMap }
      Object.keys(srZoneMap).forEach((sr) => {
        if (srZoneMap[sr] === name) delete srZoneMap[sr]
      })
      return { regions, srZoneMap, dirty: true }
    })
  },

  setSrZone: (sr, zone) => {
    set((s) => {
      const srZoneMap = { ...s.srZoneMap }
      if (!zone) delete srZoneMap[sr]
      else srZoneMap[sr] = zone
      return { srZoneMap, dirty: true }
    })
  },

  setShift: (sr, minutes) => {
    set((s) => ({
      shiftDurations: { ...s.shiftDurations, [sr]: minutes },
      dirty: true,
    }))
  },

  hydrateShifts: (m) => set({ shiftDurations: { ...m } }),

  loadFromServer: async () => {
    const data = await api.affinityLoad()
    if (!data?.regions?.length) return
    const regions: Record<string, RegionShape> = {}
    data.regions.forEach((r, idx) => {
      const name = r.name || r.id || 'Region'
      if (r.polygon?.length) {
        const colorIdx = regionColorIndexFromName(name, idx)
        regions[name] = {
          latlngs: r.polygon.map((p) => [p[0], p[1]] as LatLng),
          color: affinityRegionColor(colorIdx),
        }
      }
    })
    const rc = data.regionCount && data.regionCount > 0 ? data.regionCount : Object.keys(regions).length
    set({
      regionCount: rc || 3,
      regions: Object.keys(regions).length ? regions : get().regions,
      srZoneMap: data.srZoneMap ? { ...data.srZoneMap } : {},
      dirty: false,
    })
  },

  saveCrewAndZones: async (hubName: string) => {
    const { regions, srZoneMap, shiftDurations, regionCount } = get()
    const regionList: {
      id: string
      name: string
      color: string
      polygon: LatLng[]
      assignedSRs: string[]
      srCount: number
    }[] = []
    Object.entries(regions).forEach(([name, r]) => {
      if (r.latlngs.length >= 3) {
        const assigned = Object.entries(srZoneMap)
          .filter(([, z]) => z === name)
          .map(([sr]) => sr)
        regionList.push({
          id: name,
          name,
          color: r.color,
          polygon: r.latlngs,
          assignedSRs: assigned,
          srCount: Math.max(1, assigned.length || 1),
        })
      }
    })
    await api.affinitySave({
      hubId: hubName || 'PNQ HDP',
      regionCount,
      regions: regionList,
      srZoneMap,
    })
    if (Object.keys(shiftDurations).length) {
      await api.saveShiftDurations(shiftDurations)
    }
    set({ dirty: false, lastSavedAt: Date.now() })
  },

  getSrCounts: () => {
    const { srZoneMap, regions } = get()
    const counts: Record<string, number> = {}
    Object.keys(regions).forEach((rn) => {
      counts[rn] = 0
    })
    Object.values(srZoneMap).forEach((z) => {
      if (counts[z] != null) counts[z] += 1
    })
    return counts
  },

  buildAffinityPayload: (hubName: string) => {
    const { regions, srZoneMap, regionCount } = get()
    const regionList: {
      id: string
      name: string
      color: string
      polygon: LatLng[]
      assignedSRs: string[]
      srCount: number
    }[] = []
    Object.entries(regions).forEach(([name, r]) => {
      if (r.latlngs.length >= 3) {
        const assigned = Object.entries(srZoneMap)
          .filter(([, z]) => z === name)
          .map(([sr]) => sr)
        regionList.push({
          id: name,
          name,
          color: r.color,
          polygon: r.latlngs,
          assignedSRs: assigned,
          srCount: Math.max(1, assigned.length || 1),
        })
      }
    })
    return {
      hubId: hubName || 'PNQ HDP',
      regionCount,
      regions: regionList,
      srZoneMap,
    }
  },

  persistRegions: async (hubName: string) => {
    await api.affinitySave(get().buildAffinityPayload(hubName))
  },
}))
