/** Mirrors backend JSON field names (Jackson defaults). */

export interface HubDto {
  hubName: string
  hubLat: number
  hubLng: number
}

/** GET /api/hub/boundary — coordinates are [lat, lng] for Leaflet (per backend DTO). */
export interface HubBoundaryResponse {
  hubName: string
  facilityName: string
  coordinates: [number, number][]
  originalBoundary?: [number, number][] | null
}

export interface AppConfigDto {
  googleMapsConfigured: boolean
  orsConfigured: boolean
  googleMapsApiKey?: string | null
}

export interface SrSummaryDto {
  srName: string
  shipmentCount: number
  heavyShipmentCount: number
  estimatedDistanceKm: number
  grossPayout: number
  fuelCost: number
  netEarnings: number
  affinityStatus?: string | null
  estimatedWorkloadMinutes?: number | null
  shiftUtilisationPct?: number | null
  operationalWarning?: string | null
  territoryBoundary?: [number, number][] | null
}

export interface SrRebalanceSuggestionDto {
  srName?: string
  fromRegion?: string
  toRegion?: string
  reason?: string
  fromRegionUtilisation?: number
  toRegionOverflow?: number
  priority?: string
  /** legacy shape */
  fromSr?: string
  toSr?: string
  shipmentCount?: number
  estimatedPayoutDelta?: number
  message?: string
}

export interface DraftReassignmentDto {
  date: string
  status: string
  recommendations: SrRebalanceSuggestionDto[]
  totalRecommendations: number
  highPriorityCount: number
  mediumPriorityCount: number
  reassignedSrTargetMinUtilization: number
  reassignedSrTargetMaxUtilization: number
  nativeRegionSrMinUtilization: number
  estimatedAdditionalShipmentsAllocated: number
  message: string
}

export interface CsvCountDto {
  date: string
  total: number
  outOfRange: number
}

export interface RegionSummaryDto {
  regionName: string
  totalShipmentsInRegion: number
  allocatedShipments: number
  overflowShipments: number
  assignedSrCount: number
  activeSrCount: number
  idleSrCount: number
  assignedSrNames: string[]
  activeSrNames: string[]
  idleSrNames: string[]
  avgUtilisationPct: number
  maxUtilisationPct: number
  minUtilisationPct: number
  healthStatus: string
  suggestions?: SrRebalanceSuggestionDto[] | null
  configurationWarnings?: string[] | null
  smallLeftoverWarning?: boolean | null
}

export interface PriorityCountsDto {
  p0Total: number
  p0Allocated: number
  p0Unallocated: number
  p1Total: number
  p1Allocated: number
  p1Unallocated: number
  p2Total: number
  p2Allocated: number
  p2Unallocated: number
}

export interface AllocationSummary {
  date: string
  totalShipments: number
  allocatedShipments: number
  unallocatedShipments: number
  capacityRangeMin: number
  capacityRangeMax: number
  totalSrs: number
  minShipmentsPerSr: number
  maxShipmentsPerSr: number
  avgShipmentsPerSr: number
  fairnessVariance: number
  srSummaries: SrSummaryDto[]
  earningsVariance: number
  earningsRange: number
  meanNetEarnings: number
  allocationMode?: string | null
  shiftDurationMinutes?: number | null
  overflowShipments?: number | null
  noRegionShipments?: number | null
  regionSummaries?: RegionSummaryDto[] | null
  operationalWarnings?: string[] | null
  earningsImbalanceWarning?: boolean | null
  priorityCounts?: PriorityCountsDto | null
}

export interface AttendanceRowDto {
  srName: string
  present: boolean
}

export interface AffinityConfigLoadDto {
  hubId?: string
  regionCount?: number
  regions?: {
    id?: string
    name: string
    color?: string
    polygon: [number, number][]
    srCount?: number
  }[]
  srZoneMap?: Record<string, string>
}

export interface SrRouteDto {
  srName: string
  stops: {
    sequence: number
    latitude: number
    longitude: number
    pincode?: string
    shipmentId?: string
    isOverride?: boolean
    outOfRange?: boolean
  }[]
}

export interface TimelineResponse {
  segments?: unknown[]
  // loosely typed — UI uses specific fields when present
  [key: string]: unknown
}
