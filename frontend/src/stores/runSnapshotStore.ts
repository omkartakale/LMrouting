import { create } from 'zustand';

interface RunSnapshotState {
  summary: any | null;
  date: string | null;
  mode: 'osm' | 'google' | null;
  setSummary: (summary: any, date: string, mode: 'osm' | 'google') => void;
  clear: () => void;
}

export const useRunSnapshotStore = create<RunSnapshotState>((set) => ({
  summary: null,
  date: null,
  mode: null,
  setSummary: (summary, date, mode) => set({ summary, date, mode }),
  clear: () => set({ summary: null, date: null, mode: null }),
}));
