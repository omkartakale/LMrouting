import { create } from 'zustand'
import type { AllocationSummary } from '../types/domain'

export type PolylineMode = 'osm' | 'google'

interface RunSnapshotState {
  summary: AllocationSummary | null
  lastPolylineMode: PolylineMode
  setFromAllocation: (summary: AllocationSummary, mode: PolylineMode) => void
  clear: () => void
}

export const useRunSnapshotStore = create<RunSnapshotState>((set) => ({
  summary: null,
  lastPolylineMode: 'osm',
  setFromAllocation: (summary, lastPolylineMode) => set({ summary, lastPolylineMode }),
  clear: () => set({ summary: null }),
}))
