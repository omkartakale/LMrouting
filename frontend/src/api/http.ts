export async function apiJson<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: HeadersInit = {
    Accept: 'application/json',
    ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
    ...((init?.headers as Record<string, string>) || {}),
  }
  const r = await fetch(path, { ...init, headers })
  if (!r.ok) {
    let msg = `HTTP ${r.status}`
    try {
      const err = await r.json()
      msg = (err as { error?: string; message?: string }).error
        || (err as { message?: string }).message
        || JSON.stringify(err)
    } catch {
      /* ignore */
    }
    throw new Error(msg)
  }
  if (r.status === 204) return undefined as T
  return r.json() as Promise<T>
}
