import type { LatLng } from '../stores/planStore'

/** Shoelace area in deg² (not physical area — good enough for tiny/degenerate checks). */
export function polygonAreaDeg2(ring: LatLng[]): number {
  if (ring.length < 3) return 0
  let a = 0
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    a += ring[j][0] * ring[i][1] - ring[i][0] * ring[j][1]
  }
  return Math.abs(a / 2)
}

export function validateRegion(latlngs: LatLng[]): { valid: boolean; reason?: string } {
  if (!latlngs || latlngs.length < 3) return { valid: false, reason: 'Need at least 3 points' }
  const area = polygonAreaDeg2(latlngs)
  const areaKm2 = area * 111 * 111
  if (areaKm2 < 0.01) return { valid: false, reason: 'Region is too small (min 0.01 km²)' }
  return { valid: true }
}

export function simplifyRing(points: LatLng[], tolerance = 0.00008): LatLng[] {
  if (points.length <= 4) return points
  return dpSimplify(points, tolerance)
}

function dpSimplify(pts: LatLng[], tol: number): LatLng[] {
  if (pts.length <= 2) return pts
  let maxDist = 0
  let maxIdx = 0
  const start = pts[0]
  const end = pts[pts.length - 1]
  for (let i = 1; i < pts.length - 1; i++) {
    const d = perpDist(pts[i], start, end)
    if (d > maxDist) {
      maxDist = d
      maxIdx = i
    }
  }
  if (maxDist > tol) {
    const left = dpSimplify(pts.slice(0, maxIdx + 1), tol)
    const right = dpSimplify(pts.slice(maxIdx), tol)
    return left.slice(0, left.length - 1).concat(right)
  }
  return [start, end]
}

function perpDist(pt: LatLng, lineStart: LatLng, lineEnd: LatLng): number {
  const dx = lineEnd[0] - lineStart[0]
  const dy = lineEnd[1] - lineStart[1]
  if (dx === 0 && dy === 0) {
    return Math.hypot(pt[0] - lineStart[0], pt[1] - lineStart[1])
  }
  let t = ((pt[0] - lineStart[0]) * dx + (pt[1] - lineStart[1]) * dy) / (dx * dx + dy * dy)
  t = Math.max(0, Math.min(1, t))
  return Math.hypot(pt[0] - (lineStart[0] + t * dx), pt[1] - (lineStart[1] + t * dy))
}
