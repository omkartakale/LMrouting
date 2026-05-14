import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type AllocationMode = 'count-based' | 'time-based'
export type RoutingKind = 'standard' | 'affinity'
export type AllocationEngine = 'osm' | 'google'

interface SessionState {
  selectedDate: string
  allocationMode: AllocationMode
  routingKind: RoutingKind
  allocationEngine: AllocationEngine
  setSelectedDate: (d: string) => void
  setAllocationMode: (m: AllocationMode) => void
  setRoutingKind: (k: RoutingKind) => void
  setAllocationEngine: (e: AllocationEngine) => void
}

export const useSessionStore = create<SessionState>()(
  persist(
    (set) => ({
      selectedDate: '',
      allocationMode: 'count-based',
      routingKind: 'affinity',
      allocationEngine: 'osm',
      setSelectedDate: (selectedDate) => set({ selectedDate }),
      setAllocationMode: (allocationMode) => set({ allocationMode }),
      setRoutingKind: (routingKind) => set({ routingKind }),
      setAllocationEngine: (allocationEngine) => set({ allocationEngine }),
    }),
    { name: 'lm-alloc-session-v2' },
  ),
)
