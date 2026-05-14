import type { RoutingKind } from '../stores/sessionStore'

export function pathShowsMap(pathname: string, routingKind: RoutingKind): boolean {
  if (pathname.includes('/allocation/step-4')) return true
  if (pathname.includes('/allocation/step-2') && routingKind === 'affinity') return true
  return false
}
