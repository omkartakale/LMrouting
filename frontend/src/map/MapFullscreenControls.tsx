import { useEffect, useRef, useState } from 'react'
import { useMap } from 'react-leaflet'
import { useUiStore } from '../stores/uiStore'

/**
 * Floating controls in the top-right corner of the map.
 *
 *  - In-app fullscreen: hides the left sidebar and middle content rail so the
 *    map fills the workspace. Driven by uiStore.mapAppFullscreen.
 *  - Browser fullscreen: uses the native Fullscreen API on the map pane element.
 *
 * Both controls are render-pure with respect to the rest of the app; nothing
 * mutates allocation/session state.
 */
export function MapFullscreenControls() {
  const map = useMap()
  const appFullscreen = useUiStore((s) => s.mapAppFullscreen)
  const setAppFullscreen = useUiStore((s) => s.setMapAppFullscreen)
  const [browserFullscreen, setBrowserFullscreen] = useState<boolean>(
    () => typeof document !== 'undefined' && !!document.fullscreenElement,
  )
  const paneRef = useRef<HTMLElement | null>(null)

  // Find the parent .map-pane element once we have the leaflet map instance.
  useEffect(() => {
    const container = map.getContainer()
    const pane = container.closest('.map-pane') as HTMLElement | null
    paneRef.current = pane ?? container
  }, [map])

  // Keep local state in sync with the actual fullscreen state.
  useEffect(() => {
    const onChange = () => {
      setBrowserFullscreen(!!document.fullscreenElement)
      // Leaflet may need a resize after fullscreen toggle.
      setTimeout(() => map.invalidateSize(), 100)
    }
    document.addEventListener('fullscreenchange', onChange)
    return () => document.removeEventListener('fullscreenchange', onChange)
  }, [map])

  // After in-app fullscreen toggle, ensure Leaflet recomputes its container.
  useEffect(() => {
    const t = window.setTimeout(() => map.invalidateSize(), 60)
    return () => window.clearTimeout(t)
  }, [appFullscreen, map])

  const toggleAppFullscreen = () => setAppFullscreen(!appFullscreen)

  const toggleBrowserFullscreen = async () => {
    const target = paneRef.current
    if (!target) return
    try {
      if (document.fullscreenElement) {
        await document.exitFullscreen()
      } else {
        await target.requestFullscreen()
      }
    } catch {
      /* user-rejected or unsupported — leave state to the change listener */
    }
  }

  return (
    <div className="map-fs-controls" aria-label="Map view controls">
      <button
        type="button"
        className="map-fs-btn"
        onClick={toggleAppFullscreen}
        aria-pressed={appFullscreen}
        title={appFullscreen ? 'Restore panels' : 'Hide panels (expand map)'}
      >
        <span aria-hidden>{appFullscreen ? '⤢' : '⛶'}</span>
        <span className="map-fs-btn-label">{appFullscreen ? 'Restore' : 'Expand'}</span>
      </button>
      <button
        type="button"
        className="map-fs-btn"
        onClick={toggleBrowserFullscreen}
        aria-pressed={browserFullscreen}
        title={browserFullscreen ? 'Exit fullscreen' : 'Open in fullscreen'}
      >
        <span aria-hidden>{browserFullscreen ? '✕' : '⛶'}</span>
        <span className="map-fs-btn-label">{browserFullscreen ? 'Exit' : 'Fullscreen'}</span>
      </button>
    </div>
  )
}
