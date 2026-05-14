import { useEffect, useRef } from 'react'
import { useMap } from 'react-leaflet'
import { useQuery } from '@tanstack/react-query'
import L from 'leaflet'
import 'leaflet.markercluster'
import 'leaflet.markercluster/dist/MarkerCluster.css'
import 'leaflet.markercluster/dist/MarkerCluster.Default.css'

import { api } from '../api/endpoints'
import { useRunSnapshotStore } from '../stores/runSnapshotStore'
import { useSessionStore } from '../stores/sessionStore'
import { useUiStore } from '../stores/uiStore'
import { REGION_COLORS } from '../stores/planStore'
import type { AllocationSummary } from '../types/domain'

const PANE = 'lmStopMarkersPane'

function markerClusterGroup(options?: Record<string, unknown>): L.Layer | null {
  const fn = (L as unknown as { markerClusterGroup?: (o?: Record<string, unknown>) => L.Layer }).markerClusterGroup
  return fn ? fn(options) : null
}

function srColor(summary: AllocationSummary, srName: string) {
  const origIdx = summary.srSummaries.findIndex((s) => s.srName === srName)
  return REGION_COLORS[origIdx >= 0 ? origIdx % REGION_COLORS.length : 0]
}

/** Shipment stop markers from `srRoute` — selected SR by default; optional clustered all-SRs mode. */
export function StopMarkersOverlay() {
  const map = useMap()
  const summary = useRunSnapshotStore((s) => s.summary)
  const date = useSessionStore((s) => s.selectedDate)
  const mapLayerStops = useUiStore((s) => s.mapLayerStops)
  const mapLayerStopsAll = useUiStore((s) => s.mapLayerStopsAll)
  const selectedSrName = useUiStore((s) => s.selectedSrName)

  const layerRef = useRef<L.Layer | null>(null)

  const selectedRouteQ = useQuery({
    queryKey: ['srRoute', date, selectedSrName],
    queryFn: () => api.srRoute(date!, selectedSrName!),
    enabled: Boolean(mapLayerStops && !mapLayerStopsAll && date && selectedSrName && summary?.srSummaries?.length),
  })

  useEffect(() => {
    if (!map.getPane(PANE)) {
      map.createPane(PANE)
      const el = map.getPane(PANE)
      if (el) el.style.zIndex = '500'
    }
  }, [map])

  useEffect(() => {
    if (layerRef.current) {
      map.removeLayer(layerRef.current)
      layerRef.current = null
    }

    if (!mapLayerStops || !summary?.date || !summary.srSummaries?.length) return

    let cancelled = false

    if (mapLayerStopsAll) {
      const cluster = markerClusterGroup({
        chunkedLoading: true,
        maxClusterRadius: 52,
        spiderfyOnMaxZoom: true,
        showCoverageOnHover: false,
      })
      if (!cluster) return

      const names = summary.srSummaries.map((s) => s.srName)
      const batchSize = 5

      ;(async () => {
        for (let i = 0; i < names.length; i += batchSize) {
          if (cancelled) return
          const chunk = names.slice(i, i + batchSize)
          const routes = await Promise.all(
            chunk.map((n) => api.srRoute(summary.date, n).catch(() => null)),
          )
          if (cancelled) return
          for (let j = 0; j < chunk.length; j++) {
            const srName = chunk[j]
            const route = routes[j]
            const col = srColor(summary, srName)
            for (const st of route?.stops ?? []) {
              const m = L.circleMarker([st.latitude, st.longitude], {
                pane: PANE,
                radius: 4,
                stroke: true,
                color: col,
                weight: 2,
                fillColor: col,
                fillOpacity: 0.28,
              })
              m.bindPopup(
                `<strong>${srName}</strong><br/>#${st.sequence} ${st.pincode ?? ''} ${st.shipmentId ?? ''}`.trim(),
              )
              ;(cluster as L.FeatureGroup).addLayer(m)
            }
          }
        }
        if (cancelled) return
        cluster.addTo(map)
        layerRef.current = cluster
      })()

      return () => {
        cancelled = true
        if (layerRef.current) {
          map.removeLayer(layerRef.current)
          layerRef.current = null
        }
      }
    }

    if (!selectedSrName) return

    const route = selectedRouteQ.data
    if (!route?.stops?.length) return

    const g = L.layerGroup()
    const col = srColor(summary, selectedSrName)
    for (const st of route.stops) {
      L.circleMarker([st.latitude, st.longitude], {
        pane: PANE,
        radius: 5,
        stroke: true,
        color: col,
        weight: 2,
        fillColor: col,
        fillOpacity: 0.35,
      })
        .bindPopup(`#${st.sequence} ${st.pincode ?? ''} ${st.shipmentId ?? ''}`.trim())
        .addTo(g)
    }
    g.addTo(map)
    layerRef.current = g

    return () => {
      cancelled = true
      if (layerRef.current) {
        map.removeLayer(layerRef.current)
        layerRef.current = null
      }
    }
  }, [map, mapLayerStops, mapLayerStopsAll, summary, selectedSrName, selectedRouteQ.data])

  return null
}
