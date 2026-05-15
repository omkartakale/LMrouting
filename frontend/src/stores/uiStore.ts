import { create } from 'zustand';

interface UiState {
  mapAppFullscreen: boolean;
  activePanel: string | null;
  setMapAppFullscreen: (fullscreen: boolean) => void;
  setActivePanel: (panel: string | null) => void;
}

export const useUiStore = create<UiState>((set) => ({
  mapAppFullscreen: false,
  activePanel: null,
  setMapAppFullscreen: (fullscreen) => set({ mapAppFullscreen: fullscreen }),
  setActivePanel: (panel) => set({ activePanel: panel }),
}));
