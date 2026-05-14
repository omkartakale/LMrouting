import { useSessionStore } from '../../stores/sessionStore'
import { Step2AffinityConfigure } from './Step2AffinityConfigure'
import { Step2StandardConfigure } from './Step2StandardConfigure'

export function Step2Router() {
  const routingKind = useSessionStore((s) => s.routingKind)
  return routingKind === 'standard' ? <Step2StandardConfigure /> : <Step2AffinityConfigure />
}
