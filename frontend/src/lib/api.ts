// Thin wrapper around fetch() that hits the same Spring Boot endpoints the
// legacy index.html used. The legacy runtime continues to use raw fetch with
// these exact URLs; this module documents the full API surface in one place
// for the new typed React layer, and is re-used wherever React code needs to
// reach the backend directly.
//
// Mapping (matches src/main/java/com/example/LMrouting/controller/**):
//   /api/hub                                   → HubBoundaryController.getHub
//   /api/hub/boundary?hubName=...              → HubBoundaryController.getBoundary
//   /api/config                                → LmIntegrationController (or shared)
//   /api/pincode-boundary                      → PincodeBoundaryController.list
//   /api/pincode-boundary/{pincode}            → PincodeBoundaryController.byPincode
//   /api/csv/upload                            → CsvUploadController.upload
//   /api/csv/dates                             → CsvUploadController.dates
//   /api/attendance/{date}                     → AttendanceController.list
//   /api/attendance/{date}/{srName}?present=…  → AttendanceController.update
//   /api/attendance/sr                         → AttendanceController.addSr
//   /api/attendance/sr-shift-durations         → AttendanceController.shiftDurations
//   /api/allocate                              → AllocationController.allocate
//   /api/allocate/{date}/sr/{srName}           → AllocationController.routeForSr
//   /api/allocate/{date}/sr/{srName}/timeline  → AllocationController.timeline
//   /api/allocate/{date}/sr/{srName}/polyline/{provider} → AllocationController.polyline
//   /api/allocate/{date}/override              → AllocationController.override
//   /api/allocate/{date}/finalize              → AllocationController.finalize
//   /api/allocate/{date}/summary               → AllocationController.summary
//   /api/shipments/{date}                      → ShipmentController.byDate
//   /api/affinity-match/{date}/pincodes        → AffinityMatchingController.pincodes
//   /api/affinity-match/clear                  → AffinityMatchingController.clear
//   /api/affinity-match/set-custom-regions     → AffinityMatchingController.setCustomRegions
//   /api/affinity-match/allocate-custom        → AffinityMatchingController.allocateCustom
//   /api/affinity-config/save                  → AffinityConfigStorageController.save
//   /api/affinity-config/load                  → AffinityConfigStorageController.load
//   /api/affinity-config/clear                 → AffinityConfigStorageController.clear
//   /api/previous/{date}/summary               → PreviousAllocationController.summary
//   /api/previous/{date}/sr/{srName}           → PreviousAllocationController.route
//   /api/lm/refresh-token                      → LmIntegrationController.refreshToken
//   /api/lm/dashboard                          → LmIntegrationController.dashboard
//   /api/lm/delivery-users                     → LmIntegrationController.deliveryUsers
//   /api/lm/push-all                           → LmIntegrationController.pushAll
//   /api/lm/confirm                            → LmIntegrationController.confirm

const JSON_HEADERS = { 'Content-Type': 'application/json' };

async function getJson<T>(url: string): Promise<T> {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.json();
}

async function postJson<T>(url: string, body: unknown): Promise<T> {
  const r = await fetch(url, { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.json();
}

export const api = {
  // ── Config / hub ─────────────────────────────────────────────────────────
  getHub: () => getJson<any>('/api/hub'),
  getConfig: () => getJson<any>('/api/config'),
  getHubBoundary: (hubName: string) =>
    fetch('/api/hub/boundary?hubName=' + encodeURIComponent(hubName)),
  getPincodeBoundaries: () => getJson<any>('/api/pincode-boundary'),
  getPincodeBoundary: (pincode: string) =>
    getJson<any>('/api/pincode-boundary/' + encodeURIComponent(pincode)),

  // ── CSV ──────────────────────────────────────────────────────────────────
  uploadCsv: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return fetch('/api/csv/upload', { method: 'POST', body: fd });
  },
  getCsvDates: () => getJson<string[]>('/api/csv/dates'),

  // ── Attendance ───────────────────────────────────────────────────────────
  getAttendance: (date: string) => getJson<any[]>('/api/attendance/' + encodeURIComponent(date)),
  setAttendance: (date: string, srName: string, present: boolean) =>
    fetch(
      '/api/attendance/' +
        encodeURIComponent(date) +
        '/' +
        encodeURIComponent(srName) +
        '?present=' +
        present,
      { method: 'PUT' },
    ),
  addSr: (srName: string) =>
    fetch('/api/attendance/sr', { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ srName }) }),
  getShiftDurations: () => getJson<Record<string, number>>('/api/attendance/sr-shift-durations'),
  saveShiftDurations: (durations: Record<string, number>) =>
    fetch('/api/attendance/sr-shift-durations', {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(durations),
    }),

  // ── Allocation ───────────────────────────────────────────────────────────
  allocate: (body: { date: string; allocationMode: string }) => postJson<any>('/api/allocate', body),
  getRouteForSr: (date: string, srName: string) =>
    getJson<any>('/api/allocate/' + encodeURIComponent(date) + '/sr/' + encodeURIComponent(srName)),
  getTimeline: (date: string, srName: string) =>
    getJson<any>(
      '/api/allocate/' + encodeURIComponent(date) + '/sr/' + encodeURIComponent(srName) + '/timeline',
    ),
  getPolyline: (date: string, srName: string, provider: 'ors' | 'google') =>
    getJson<any>(
      '/api/allocate/' +
        encodeURIComponent(date) +
        '/sr/' +
        encodeURIComponent(srName) +
        '/polyline/' +
        provider,
    ),
  override: (date: string, body: { shippingId: string; targetSrName: string }) =>
    postJson<any>('/api/allocate/' + encodeURIComponent(date) + '/override', body),
  undoOverride: (date: string) =>
    fetch('/api/allocate/' + encodeURIComponent(date) + '/override', { method: 'DELETE' }),
  finalize: (date: string) =>
    fetch('/api/allocate/' + encodeURIComponent(date) + '/finalize', { method: 'POST' }),
  getAllocationSummary: (date: string) =>
    getJson<any>('/api/allocate/' + encodeURIComponent(date) + '/summary'),

  // ── Shipments / Affinity / Previous ──────────────────────────────────────
  getShipments: (date: string) => getJson<any[]>('/api/shipments/' + encodeURIComponent(date)),
  getAvailablePincodes: (date: string) =>
    getJson<any>('/api/affinity-match/' + encodeURIComponent(date) + '/pincodes'),
  clearAffinityMatch: () => fetch('/api/affinity-match/clear', { method: 'DELETE' }),
  setCustomRegions: (body: any) => postJson<any>('/api/affinity-match/set-custom-regions', body),
  allocateCustom: (body: any) => postJson<any>('/api/affinity-match/allocate-custom', body),
  saveAffinityConfig: (body: any) => postJson<any>('/api/affinity-config/save', body),
  loadAffinityConfig: () => getJson<any>('/api/affinity-config/load'),
  clearAffinityConfig: () => fetch('/api/affinity-config/clear', { method: 'DELETE' }),
  getPreviousSummary: (date: string) =>
    getJson<any>('/api/previous/' + encodeURIComponent(date) + '/summary'),
  getPreviousRoute: (date: string, srName: string) =>
    getJson<any>('/api/previous/' + encodeURIComponent(date) + '/sr/' + encodeURIComponent(srName)),

  // ── LM Integration ───────────────────────────────────────────────────────
  lmRefreshToken: () => postJson<any>('/api/lm/refresh-token', {}),
  lmDashboard: () => postJson<any>('/api/lm/dashboard', {}),
  lmDeliveryUsers: () => postJson<any>('/api/lm/delivery-users', {}),
  lmPushAll: (body: any) => postJson<any>('/api/lm/push-all', body),
  lmConfirm: (body: any) => postJson<any>('/api/lm/confirm', body),
};
