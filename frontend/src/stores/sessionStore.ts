import { create } from 'zustand';

interface SessionState {
  selectedDate: string | null;
  hubName: string | null;
  hubLat: number;
  hubLng: number;
  config: any | null;
  setSelectedDate: (date: string | null) => void;
  setHub: (name: string, lat: number, lng: number) => void;
  setConfig: (config: any) => void;
}

export const useSessionStore = create<SessionState>((set) => ({
  selectedDate: null,
  hubName: null,
  hubLat: 18.4600561,
  hubLng: 73.8884305,
  config: null,
  setSelectedDate: (date) => set({ selectedDate: date }),
  setHub: (name, lat, lng) => set({ hubName: name, hubLat: lat, hubLng: lng }),
  setConfig: (config) => set({ config }),
}));
