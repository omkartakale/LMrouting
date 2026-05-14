import { useEffect, useMemo, useRef } from 'react'
import { MapContainer, Marker, TileLayer, useMap } from 'react-leaflet'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import '@geoman-io/leaflet-geoman-free/dist/leaflet-geoman.css'
import '@geoman-io/leaflet-geoman-free'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import { GeomanDrawBridge } from './GeomanDrawBridge'
import { HubBoundaryLayers } from './HubBoundaryLayers'
import { RegionPolyLayers } from './RegionPolyLayers'
import { RoutePolylineOverlay } from './RoutePolylineOverlay'
import { StopMarkersOverlay } from './StopMarkersOverlay'
import { MapToolbar } from './MapToolbar'

function MapRecenter({ lat, lng }: { lat: number; lng: number }) {
  const map = useMap()
  useEffect(() => {
    map.setView([lat, lng], 13)
    map.invalidateSize()
  }, [lat, lng, map])
  return null
}

/** Leaflet measures the container once; flex layouts often need a second pass after paint. */
function MapLayoutFix() {
  const map = useMap()
  const mapRef = useRef(map)
  mapRef.current = map

  useEffect(() => {
    const el = map.getContainer()
    const parent = el.parentElement
    const fix = () => {
      mapRef.current.invalidateSize({ animate: false })
    }

    fix()
    const raf = requestAnimationFrame(fix)
    const t0 = window.setTimeout(fix, 100)
    const t1 = window.setTimeout(fix, 400)

    window.addEventListener('resize', fix)
    const ro =
      parent && typeof ResizeObserver !== 'undefined'
        ? new ResizeObserver(() => {
            requestAnimationFrame(fix)
          })
        : null
    if (parent && ro) ro.observe(parent)

    return () => {
      cancelAnimationFrame(raf)
      window.clearTimeout(t0)
      window.clearTimeout(t1)
      window.removeEventListener('resize', fix)
      ro?.disconnect()
    }
  }, [map])

  return null
}

export function MapCanvas() {
  const { data: hub } = useQuery({ queryKey: ['hub'], queryFn: () => api.hub() })
  const center: [number, number] = [hub?.hubLat ?? 18.46, hub?.hubLng ?? 73.83]
  const hubIcon = useMemo(
    () =>
      L.divIcon({
        className: '',
        html: '<div style="width:20px;height:20px;background:#e53935;border:3px solid #fff;border-radius:50%;box-shadow:0 2px 6px rgba(0,0,0,.4)"></div>',
        iconSize: [20, 20],
        iconAnchor: [10, 10],
      }),
    [],
  )

  return (
    <MapContainer
      center={center}
      zoom={13}
      className="lm-map"
      style={{ height: '100%', width: '100%', minHeight: 0 }}
      scrollWheelZoom
    >
      <MapLayoutFix />
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      {hub && (
        <>
          <MapRecenter lat={hub.hubLat} lng={hub.hubLng} />
          <Marker position={[hub.hubLat, hub.hubLng]} icon={hubIcon} title="Hub" />
        </>
      )}
      <HubBoundaryLayers />
      <RegionPolyLayers />
      <GeomanDrawBridge />
      <RoutePolylineOverlay />
      <StopMarkersOverlay />
      <MapToolbar />
    </MapContainer>
  )
}
