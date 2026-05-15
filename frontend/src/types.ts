// Shared TypeScript types describing the backend response shapes the React
// layer touches directly. The bulk of the legacy runtime works with `any` to
// stay 1:1 with the original JavaScript; this file only types the values the
// new React components consume.

// /api/hub
export interface HubInfo {
  hubName: string;
  hubLat: number;
  hubLng: number;
}

// /api/config
export interface AppConfig {
  googleMapsConfigured?: boolean;
  orsConfigured?: boolean;
  googleMapsApiKey?: string;
}

// /api/csv/upload response (subset used in React)
export interface IngestionResult {
  totalRows: number;
  validCount: number;
  skippedCount: number;
  outOfRangeCount: number;
  datesFound?: string[];
  primaryDate?: string;
  warnings?: string[];
  errors?: string[];
  hubName?: string;
}

// AllocationSummary subset
export interface SrSummary {
  srName: string;
  shipmentCount: number;
  estimatedDistanceKm?: number;
  netEarnings?: number;
  grossPayout?: number;
  fuelCost?: number;
  shiftUtilisationPct?: number | null;
  affinityStatus?: 'AFFINITY_ASSIGNED' | string | null;
  territoryBoundary?: number[][];
}

export interface AllocationSummary {
  totalShipments?: number;
  allocatedShipments?: number;
  unallocatedShipments?: number;
  totalSrs?: number;
  capacityRangeMin?: number;
  capacityRangeMax?: number;
  avgShipmentsPerSr?: number;
  shiftDurationMinutes?: number;
  allocationMode?: 'count-based' | 'time-based' | string;
  srSummaries?: SrSummary[];
  priorityCounts?: {
    p0Total: number;
    p0Allocated: number;
    p0Unallocated: number;
    p1Total: number;
    p1Allocated: number;
    p1Unallocated: number;
    p2Total: number;
    p2Allocated: number;
    p2Unallocated: number;
  };
  regionSummaries?: RegionSummary[];
}

export interface RegionSummary {
  regionName: string;
  totalShipmentsInRegion: number;
  allocatedShipments: number;
  overflowShipments: number;
  activeSrCount: number;
  assignedSrCount: number;
  avgUtilisationPct: number;
  minUtilisationPct: number;
  maxUtilisationPct: number;
  healthStatus: 'HEALTHY' | 'OVERFLOW' | 'IDLE_SRS' | 'UNDERLOADED' | string;
  assignedSrNames?: string[];
  idleSrNames?: string[];
  suggestions?: Array<{
    srName: string;
    fromRegion: string;
    toRegion: string;
    priority: 'HIGH' | 'MEDIUM' | 'LOW' | string;
    reason: string;
  }>;
}

// Affinity setup page region shape (matches /api/affinity-config payload).
export interface AffinityRegion {
  id: string;
  name: string;
  color: string;
  polygon: number[][];
  assignedSRs: string[];
}

// Make the legacy globals known to TypeScript so the typed React layer can
// reference them without `// @ts-ignore` everywhere.
declare global {
  interface Window {
    L: any;
    google: any;
    markerClusterer: any;
    GMAP_API_KEY: string;
    gmapReady: boolean;
    loadGoogleMapsScript: (key: string) => void;
    onGoogleMapsReady: () => void;
    switchSidebarTab?: (name: 'upload' | 'allocate' | 'monitor') => void;
    unlockSidebarTab?: (name: 'upload' | 'allocate' | 'monitor') => void;
  }
}

export {};
