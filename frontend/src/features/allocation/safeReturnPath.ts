/** Validates `?return=` for same-origin in-app paths (e.g. back to Step 4 after editing affinity). */
export function safeReturnPath(raw: string | null): string | null {
  if (!raw) return null
  let path: string
  try {
    path = decodeURIComponent(raw.trim())
  } catch {
    return null
  }
  if (!path.startsWith('/') || path.startsWith('//')) return null
  const pathnameOnly = path.split('?')[0]
  if (!/^\/allocation\/step-[1-4]$/.test(pathnameOnly)) return null
  return pathnameOnly
}
