import { api } from '../api/endpoints'
import type { AllocationSummary } from '../types/domain'
import type { AllocationMode, RoutingKind } from '../stores/sessionStore'

export interface RunAllocationContext {
  date: string
  routingKind: RoutingKind
  allocationMode: AllocationMode
  regions: Record<string, { latlngs: [number, number][] }>
  srZoneMap: Record<string, string>
  getSrCounts: () => Record<string, number>
}

export async function runAllocation(ctx: RunAllocationContext): Promise<AllocationSummary> {
  const { date, routingKind, allocationMode, regions, srZoneMap, getSrCounts } = ctx
  const drawn = Object.fromEntries(
    Object.entries(regions)
      .filter(([, r]) => r.latlngs.length >= 3)
      .map(([k, r]) => [k, r.latlngs]),
  ) as Record<string, [number, number][]>

  if (routingKind === 'standard') {
    return api.allocate(date, null)
  }

  if (Object.keys(drawn).length === 0) {
    throw new Error('Draw at least one region on the map before running affinity allocation.')
  }
  if (allocationMode === 'time-based') {
    const assigned = Object.values(srZoneMap).filter((z) => drawn[z]).length
    if (assigned === 0) {
      throw new Error('Assign SRs to drawn regions before running time-based affinity allocation.')
    }
  }

  const srCounts = getSrCounts()
  const setRes = await api.setCustomRegions({ regions: drawn, srCounts, srZoneMap })
  if (setRes && typeof setRes === 'object' && 'error' in setRes && setRes.error) {
    throw new Error(String(setRes.error))
  }
  const data = await api.allocateCustom(date, allocationMode)
  if (data.error) throw new Error(data.error)
  if (!data.allocationSummary) throw new Error('Allocation completed but no summary was returned.')
  return data.allocationSummary
}
