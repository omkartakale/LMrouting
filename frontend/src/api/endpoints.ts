import type {
  AffinityConfigLoadDto,
  AllocationSummary,
  AppConfigDto,
  AttendanceRowDto,
  CsvCountDto,
  DraftReassignmentDto,
  HubBoundaryResponse,
  HubDto,
  SrRouteDto,
  TimelineResponse,
} from '../types/domain'
import { apiJson } from './http'

export const api = {
  hub: () => apiJson<HubDto>('/api/hub'),
  hubBoundary: async (hubName: string): Promise<HubBoundaryResponse | null> => {
    const r = await fetch(`/api/hub/boundary?hubName=${encodeURIComponent(hubName)}`)
    if (r.status === 204 || r.status === 404) return null
    if (!r.ok) {
      const t = await r.text().catch(() => '')
      throw new Error(t || `HTTP ${r.status}`)
    }
    return r.json() as Promise<HubBoundaryResponse>
  },
  config: () => apiJson<AppConfigDto>('/api/config'),
  csvDates: () => apiJson<string[]>('/api/csv/dates'),
  csvCount: (date: string) =>
    apiJson<CsvCountDto>(`/api/csv/count/${encodeURIComponent(date)}`),
  uploadCsv: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return fetch('/api/csv/upload', { method: 'POST', body: fd }).then(async (r) => {
      const data = await r.json().catch(() => ({}))
      if (!r.ok) throw new Error((data as { message?: string }).message || `HTTP ${r.status}`)
      return data as { success?: boolean; message?: string }
    })
  },
  attendance: (date: string) =>
    apiJson<AttendanceRowDto[]>(`/api/attendance/${encodeURIComponent(date)}`),
  setAttendance: (date: string, srName: string, present: boolean) =>
    fetch(
      `/api/attendance/${encodeURIComponent(date)}/${encodeURIComponent(srName)}?present=${present}`,
      { method: 'PUT' },
    ).then((r) => {
      if (!r.ok) throw new Error(`HTTP ${r.status}`)
    }),
  getShiftDurations: () => apiJson<Record<string, number>>('/api/attendance/sr-shift-durations'),
  saveShiftDurations: (body: Record<string, number>) =>
    apiJson<Record<string, number>>('/api/attendance/sr-shift-durations', {
      method: 'PUT',
      body: JSON.stringify(body),
    }),
  addSr: (srName: string) =>
    apiJson<{ success?: string }>('/api/attendance/sr', {
      method: 'POST',
      body: JSON.stringify({ srName }),
    }),
  allocate: (date: string, allocationMode?: string | null) =>
    apiJson<AllocationSummary>('/api/allocate', {
      method: 'POST',
      body: JSON.stringify({ date, allocationMode: allocationMode ?? null }),
    }),
  summary: (date: string) =>
    apiJson<AllocationSummary>(`/api/allocate/${encodeURIComponent(date)}/summary`),
  affinityLoad: () => apiJson<AffinityConfigLoadDto>('/api/affinity-config/load'),
  affinitySave: (payload: unknown) =>
    apiJson<{ success?: boolean }>('/api/affinity-config/save', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  setCustomRegions: (body: unknown) =>
    apiJson<Record<string, unknown>>('/api/affinity-match/set-custom-regions', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  allocateCustom: (date: string, allocationMode: string) =>
    apiJson<{ allocationSummary?: AllocationSummary; error?: string; mode?: string }>(
      '/api/affinity-match/allocate-custom',
      { method: 'POST', body: JSON.stringify({ date, allocationMode }) },
    ),
  srRoute: (date: string, srName: string) =>
    apiJson<SrRouteDto>(`/api/allocate/${encodeURIComponent(date)}/sr/${encodeURIComponent(srName)}`),
  srTimeline: (date: string, srName: string) =>
    apiJson<TimelineResponse>(
      `/api/allocate/${encodeURIComponent(date)}/sr/${encodeURIComponent(srName)}/timeline`,
    ),
  polyline: (date: string, srName: string, mode: 'ors' | 'google') =>
    apiJson<[number, number][]>(
      `/api/allocate/${encodeURIComponent(date)}/sr/${encodeURIComponent(srName)}/polyline/${mode}`,
    ),
  finalize: (date: string) =>
    fetch(`/api/allocate/${encodeURIComponent(date)}/finalize`, { method: 'POST' }).then((r) => {
      if (!r.ok) throw new Error(`HTTP ${r.status}`)
    }),
  deleteOverrides: (date: string) =>
    fetch(`/api/allocate/${encodeURIComponent(date)}/override`, { method: 'DELETE' }).then((r) => {
      if (!r.ok) throw new Error(`HTTP ${r.status}`)
    }),
  previousSummary: (date: string) =>
    apiJson<AllocationSummary>(`/api/previous/${encodeURIComponent(date)}/summary`),
  rebalanceDraft: (date: string) =>
    apiJson<DraftReassignmentDto>(`/api/allocate/${encodeURIComponent(date)}/rebalance/draft`),
  rebalanceSave: (date: string, body?: { srNames?: string[] }) =>
    apiJson<DraftReassignmentDto>(`/api/allocate/${encodeURIComponent(date)}/rebalance/save`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body ?? {}),
    }),
  lmRefresh: () => apiJson<{ success?: boolean; error?: string; hubId?: number }>('/api/lm/refresh-token', { method: 'POST' }),
  lmDashboard: () =>
    apiJson<{ data?: { pendingCounts?: { srCount?: number; svcCount?: number }; allocatedCounts?: { srCount?: number; svcCount?: number } } }>(
      '/api/lm/dashboard',
      { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' },
    ),
  lmDeliveryUsers: () =>
    apiJson<{ users?: unknown[] }>('/api/lm/delivery-users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    }),
  lmPushAll: (body: unknown) =>
    fetch('/api/lm/push-all', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then(async (r) => {
      const data = await r.json().catch(() => ({}))
      if (!r.ok) throw new Error((data as { error?: string }).error || `HTTP ${r.status}`)
      return data
    }),
}
