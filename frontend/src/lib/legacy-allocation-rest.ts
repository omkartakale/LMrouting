// @ts-nocheck
/* eslint-disable */
/**
 * Legacy allocation runtime — PART 2
 * This module contains the remaining functions from index.html lines 2994-4679
 * that are dynamically imported by legacy-allocation.ts
 */

export function installRest(ctx: any, refs: any) {
  const w = window as any;
  const { updateBoundaryLegend, renderSrList } = refs;

  // Global state for this module
  let lastRegionSuggestions: any[] = [];
  let densityMarkersVisible = true;
  let shipmentDisplayAll = true;
  let rawPlotActive = false;
  let rawPlotLayerGroup: any = null;
  let affinityMode = 'draw';
  let plannedRegionCount = 0;
  let activeDrawRegion: string | null = null;
  let drawingActive = false;
  let editingRegionName: string | null = null;
  
  const REGION_COLORS = ['#4CAF50','#FF9800','#00BCD4','#607D8B','#E91E63','#9C27B0','#2196F3','#f44336','#795548','#009688','#3F51B5','#CDDC39','#FFC107','#9E9E9E','#673AB7','#03A9F4','#8BC34A','#FFEB3B','#FF5722','#607D8B'];

  function esc(s: any) {
    if (s == null) return '';
    const d = document.createElement('div');
    d.appendChild(document.createTextNode(String(s)));
    return d.innerHTML;
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // REGION HEALTH PANEL
  // ══════════════════════════════════════════════════════════════════════════════
  
  w.toggleRegionHealthPanel = function() {
    const body = document.getElementById('region-health-body');
    const arrow = document.getElementById('region-health-arrow');
    if (!body) return;
    if (body.style.display === 'none') {
      body.style.display = 'block';
      if (arrow) arrow.textContent = '▼';
    } else {
      body.style.display = 'none';
      if (arrow) arrow.textContent = '▶';
    }
  };

  w.renderRegionHealth = function(summary: any) {
    const panel = document.getElementById('region-health-panel');
    const list = document.getElementById('region-health-list');
    const badge = document.getElementById('region-health-badge');
    if (!panel || !list || !badge) return;

    if (!summary || summary.allocationMode !== 'time-based' || !summary.regionSummaries || !summary.regionSummaries.length) {
      panel.classList.add('hidden');
      return;
    }

    panel.classList.remove('hidden');

    const regions = summary.regionSummaries;
    const overflowCount = regions.filter((r: any) => r.healthStatus === 'OVERFLOW').length;
    const idleCount = regions.filter((r: any) => r.healthStatus === 'IDLE_SRS').length;
    const healthyCount = regions.filter((r: any) => r.healthStatus === 'HEALTHY').length;

    if (overflowCount > 0) {
      badge.textContent = overflowCount + ' overflow';
      badge.style.background = '#ffebee';
      badge.style.color = '#c62828';
    } else if (idleCount > 0) {
      badge.textContent = idleCount + ' idle SRs';
      badge.style.background = '#fff3e0';
      badge.style.color = '#e65100';
    } else {
      badge.textContent = healthyCount + ' healthy';
      badge.style.background = '#e8f5e9';
      badge.style.color = '#2e7d32';
    }

    let h = '';

    const allSuggestions: any[] = [];
    regions.forEach((r: any) => {
      if (r.suggestions) allSuggestions.push(...r.suggestions);
    });
    lastRegionSuggestions = allSuggestions;

    if (allSuggestions.length > 0) {
      h += '<div style="background:#fff3e0;border:1px solid #ffe082;border-radius:7px;padding:9px 11px;margin-bottom:10px">';
      h += '<div style="font-size:.78rem;font-weight:700;color:#e65100;margin-bottom:5px">💡 ' + allSuggestions.length + ' Rebalancing Suggestion' + (allSuggestions.length > 1 ? 's' : '') + '</div>';
      allSuggestions.forEach((s: any) => {
        const priorityColor = s.priority === 'HIGH' ? '#c62828' : (s.priority === 'MEDIUM' ? '#e65100' : '#1565c0');
        h += '<div style="font-size:.73rem;padding:4px 0;border-bottom:1px solid rgba(0,0,0,.06);display:flex;align-items:flex-start;gap:6px">';
        h += '<span style="background:' + priorityColor + ';color:#fff;border-radius:4px;padding:1px 5px;font-size:.65rem;font-weight:700;flex-shrink:0;margin-top:1px">' + esc(s.priority) + '</span>';
        h += '<span style="color:#495057">' + esc(s.reason) + '</span>';
        h += '</div>';
      });
      h += '</div>';
      h += '<div style="margin-top:6px"><button class="btn btn-primary" style="font-size:.74rem;width:100%" onclick="autoReassignSrs()">🔄 Auto Reassign Idle SRs to Overflow Regions</button></div>';
    }

    regions.forEach((region: any) => {
      const statusClass = region.healthStatus ? region.healthStatus.toLowerCase().replace('_', '-') : 'healthy';
      const statusLabel = {
        'HEALTHY': '✅ Healthy',
        'OVERFLOW': '🔴 Overflow',
        'IDLE_SRS': '⚠️ Idle SRs',
        'UNDERLOADED': '🔵 Underloaded'
      }[region.healthStatus] || region.healthStatus;

      h += '<div class="region-card ' + statusClass + '">';
      h += '<div class="region-card-header">';
      h += '<span class="region-card-name">🗺 ' + esc(region.regionName) + '</span>';
      h += '<span class="region-status-badge ' + statusClass + '">' + statusLabel + '</span>';
      h += '</div>';

      h += '<div class="region-stats-row">';
      h += '<div class="region-stat"><div class="v">' + region.totalShipmentsInRegion + '</div><div class="l">Total</div></div>';
      h += '<div class="region-stat"><div class="v" style="color:#2e7d32">' + region.allocatedShipments + '</div><div class="l">Allocated</div></div>';
      if (region.overflowShipments > 0) {
        h += '<div class="region-stat"><div class="v" style="color:#c62828">' + region.overflowShipments + '</div><div class="l">Overflow</div></div>';
      }
      h += '<div class="region-stat"><div class="v">' + region.activeSrCount + '/' + region.assignedSrCount + '</div><div class="l">SRs Active</div></div>';
      h += '<div class="region-stat"><div class="v">' + region.avgUtilisationPct.toFixed(1) + '%</div><div class="l">Avg Util</div></div>';
      h += '</div>';

      const barPct = Math.min(100, region.avgUtilisationPct);
      const barClass = barPct >= 90 ? 'high' : (barPct >= 60 ? 'mid' : 'low');
      h += '<div style="display:flex;align-items:center;gap:5px;margin-bottom:6px">';
      h += '<div class="util-bar-wrap" style="flex:1"><div class="util-bar ' + barClass + '" style="width:' + barPct.toFixed(1) + '%"></div></div>';
      h += '<span style="font-size:.68rem;color:#495057;white-space:nowrap">' + region.minUtilisationPct.toFixed(0) + '–' + region.maxUtilisationPct.toFixed(0) + '%</span>';
      h += '</div>';

      if (region.assignedSrNames && region.assignedSrNames.length) {
        h += '<div class="region-sr-chips">';
        region.assignedSrNames.forEach((sr: string) => {
          const isIdle = region.idleSrNames && region.idleSrNames.indexOf(sr) >= 0;
          h += '<span class="sr-chip ' + (isIdle ? 'idle' : 'active') + '">' + esc(sr) + (isIdle ? ' (idle)' : '') + '</span>';
        });
        h += '</div>';
      }

      if (region.suggestions && region.suggestions.length) {
        region.suggestions.forEach((s: any) => {
          const cardClass = s.priority === 'HIGH' ? 'high' : (s.priority === 'MEDIUM' ? 'medium' : 'low');
          h += '<div class="suggestion-card ' + cardClass + '">';
          h += '<div class="suggestion-header">💡 Move <strong>' + esc(s.srName) + '</strong> from ' + esc(s.fromRegion) + ' → ' + esc(s.toRegion) + '</div>';
          h += '<div class="suggestion-action">' + esc(s.reason) + '</div>';
          h += '</div>';
        });
      }

      h += '</div>';
    });

    list.innerHTML = h;
  };

  w.autoReassignSrs = function() {
    if (!lastRegionSuggestions || lastRegionSuggestions.length === 0) {
      w.showAllocError('No rebalancing suggestions available. Run allocation first.');
      return;
    }

    let applied = 0;
    lastRegionSuggestions.forEach((s: any) => {
      if (s.priority === 'HIGH' || s.priority === 'MEDIUM') {
        w.srZoneMap[s.srName] = s.toRegion;
        applied++;
      }
    });

    if (applied === 0) {
      w.showAllocInfo('No idle SRs to reassign.');
      return;
    }

    w.saveRegionsToStorage();
    w.showAllocInfo('✅ Auto-reassigned ' + applied + ' SR(s) to overflow regions. Click "Run with Affinity" to re-allocate.');

    const date = (document.getElementById('date-select') as HTMLSelectElement)?.value;
    if (date) w.loadAttendance(date);
  };

  // ══════════════════════════════════════════════════════════════════════════════
  // DENSITY & SHIPMENT DISPLAY TOGGLES
  // ══════════════════════════════════════════════════════════════════════════════

  w.toggleDensityMarkers = function() {
    densityMarkersVisible = !densityMarkersVisible;

    const label = document.getElementById('density-toggle-label');
    const ind = document.getElementById('density-toggle-indicator');
    const dot = ind?.querySelector('span');

    if (densityMarkersVisible) {
      if (label) label.textContent = 'Density ON';
      if (ind) ind.style.background = '#43a047';
      if (dot) dot.style.marginLeft = 'auto';
      Object.keys(w.srLayers).forEach((srName: string) => {
        const layer = w.srLayers[srName];
        if (layer && layer.markers) {
          layer.markers.forEach((m: any) => { if (!w.map.hasLayer(m)) m.addTo(w.map); });
        }
      });
    } else {
      if (label) label.textContent = 'Density OFF';
      if (ind) ind.style.background = '#90a4ae';
      if (dot) dot.style.marginLeft = '2px';
      Object.keys(w.srLayers).forEach((srName: string) => {
        const layer = w.srLayers[srName];
        if (layer && layer.markers) {
          layer.markers.forEach((m: any) => { if (w.map.hasLayer(m)) w.map.removeLayer(m); });
        }
      });
    }

    if (w.activeMapProvider === 'google' && w.gmapMarkers.length > 0) {
      if (densityMarkersVisible) {
        if (w.gmapMarkerClusterer) {
          w.gmapMarkerClusterer.addMarkers(w.gmapMarkers);
        } else {
          w.gmapMarkers.forEach((m: any) => m.setMap(w.gmap));
        }
      } else {
        if (w.gmapMarkerClusterer) {
          w.gmapMarkerClusterer.clearMarkers();
        } else {
          w.gmapMarkers.forEach((m: any) => m.setMap(null));
        }
      }
    }
  };

  w.toggleShipmentDisplay = function() {
    shipmentDisplayAll = !shipmentDisplayAll;

    const icon = document.getElementById('shipment-toggle-icon');
    const label = document.getElementById('shipment-toggle-label');
    const ind = document.getElementById('shipment-toggle-indicator');
    const dot = ind?.querySelector('span');

    if (shipmentDisplayAll) {
      if (icon) icon.textContent = '🗺';
      if (label) label.textContent = 'All Shipments';
      if (ind) ind.style.background = '#43a047';
      if (dot) dot.style.marginLeft = 'auto';
      Object.keys(w.srLayers).forEach((srName: string) => {
        const layer = w.srLayers[srName];
        if (layer && layer.markers) {
          layer.markers.forEach((m: any) => { if (!w.map.hasLayer(m)) m.addTo(w.map); });
        }
      });
    } else {
      if (icon) icon.textContent = '📍';
      if (label) label.textContent = 'Affinity Only';
      if (ind) ind.style.background = '#90a4ae';
      if (dot) dot.style.marginLeft = '2px';

      const affinitySrs = new Set(Object.keys(w.srAffinityMap || {}).filter((sr: string) => {
        return w.srAffinityMap[sr] && w.srAffinityMap[sr].length > 0;
      }));

      Object.keys(w.srLayers).forEach((srName: string) => {
        const layer = w.srLayers[srName];
        if (!layer || !layer.markers) return;
        if (affinitySrs.has(srName)) {
          layer.markers.forEach((m: any) => { if (!w.map.hasLayer(m)) m.addTo(w.map); });
        } else {
          layer.markers.forEach((m: any) => { if (w.map.hasLayer(m)) w.map.removeLayer(m); });
        }
      });
    }
  };

  w.toggleRawShipmentPlot = function() {
    const btn = document.getElementById('raw-plot-btn');
    const label = document.getElementById('raw-plot-label');

    if (rawPlotActive) {
      if (rawPlotLayerGroup) {
        w.map.removeLayer(rawPlotLayerGroup);
        rawPlotLayerGroup = null;
      }
      rawPlotActive = false;
      if (btn) btn.style.background = '#fff';
      if (label) label.textContent = 'Plot All Points';
      return;
    }

    const date = (document.getElementById('date-select') as HTMLSelectElement)?.value;
    if (!date) {
      w.showAllocError('Select a date first to plot shipments.');
      return;
    }

    if (btn) btn.style.background = '#e3f2fd';
    if (label) label.textContent = 'Loading...';

    fetch('/api/shipments/' + encodeURIComponent(date))
      .then((r) => r.json())
      .then((shipments: any[]) => {
        if (!shipments || shipments.length === 0) {
          w.showAllocError('No shipments found for this date.');
          if (btn) btn.style.background = '#fff';
          if (label) label.textContent = 'Plot All Points';
          return;
        }

        if (rawPlotLayerGroup) w.map.removeLayer(rawPlotLayerGroup);
        rawPlotLayerGroup = w.L.layerGroup();

        shipments.forEach((s: any) => {
          if (s.lat && s.lng) {
            w.L.circleMarker([s.lat, s.lng], {
              radius: 4,
              fillColor: '#2196F3',
              fillOpacity: 0.6,
              weight: 1,
              color: '#fff'
            }).bindPopup('<strong>' + esc(s.shippingId || s.pincode || 'Shipment') + '</strong><br>' + esc(s.pincode))
              .addTo(rawPlotLayerGroup);
          }
        });

        rawPlotLayerGroup.addTo(w.map);
        rawPlotActive = true;
        if (label) label.textContent = 'Hide Plot (' + shipments.length + ')';
      })
      .catch(() => {
        w.showAllocError('Failed to load shipments.');
        if (btn) btn.style.background = '#fff';
        if (label) label.textContent = 'Plot All Points';
      });
  };

  // ══════════════════════════════════════════════════════════════════════════════
  // AFFINITY MODE & CUSTOM REGIONS
  // ══════════════════════════════════════════════════════════════════════════════

  w.setAffinityMode = function(mode: string) {
    affinityMode = mode;
    const drawDiv = document.getElementById('affinity-draw-mode');
    if (drawDiv) drawDiv.style.display = 'block';
    if (mode === 'draw') w.renderDrawList();
    w.updateAffinityBadge();
  };

  w.onRegionCountChange = function() {};

  w.applyRegionCount = function() {
    const input = document.getElementById('region-count-input') as HTMLInputElement;
    if (!input) return;
    let n = parseInt(input.value, 10);
    if (isNaN(n) || n < 1) { n = 1; input.value = '1'; }
    if (n > 20) { n = 20; input.value = '20'; }
    plannedRegionCount = n;

    w.stopDrawing();
    Object.keys(w.customRegionLayers || {}).forEach((r: string) => {
      if (w.customRegionLayers[r]) w.map.removeLayer(w.customRegionLayers[r]);
    });
    Object.keys(w.gmapCustomRegionPolygons || {}).forEach((r: string) => {
      if (w.gmapCustomRegionPolygons[r]) w.gmapCustomRegionPolygons[r].setMap(null);
    });
    w.customRegionMap = {};
    w.customRegionLayers = {};
    w.gmapCustomRegionPolygons = {};
    w.regionSrCounts = {};
    w.setDrawStatus('');
    w.renderDrawList();
    w.updateAffinityBadge();
  };

  w.renderDrawList = function() {
    const container = document.getElementById('affinity-draw-list');
    if (!container) return;
    if (plannedRegionCount === 0) {
      container.innerHTML = '<div class="empty-text">Set zone count above to start drawing</div>';
      return;
    }

    let h = '';

    for (let i = 1; i <= plannedRegionCount; i++) {
      const regionName = 'Region ' + i;
      const color = REGION_COLORS[(i - 1) % REGION_COLORS.length];
      const hasRegion = w.customRegionMap[regionName] && w.customRegionMap[regionName].latlngs && w.customRegionMap[regionName].latlngs.length >= 3;
      const isActive = activeDrawRegion === regionName;

      const srCount = Object.keys(w.srZoneMap || {}).filter((sr: string) => w.srZoneMap[sr] === regionName).length;

      h += '<div style="display:flex;align-items:center;gap:5px;padding:5px 0;border-bottom:1px solid #f1f3f5;font-size:.76rem">';
      h += '<div style="width:9px;height:9px;border-radius:50%;background:' + color + ';flex-shrink:0"></div>';
      h += '<div style="min-width:52px;font-weight:600;color:#212529">' + esc(regionName) + '</div>';

      if (isActive) {
        h += '<button onclick="stopDrawing()" style="flex:1;padding:3px 6px;font-size:.7rem;font-weight:600;border:none;border-radius:4px;cursor:pointer;background:#e53935;color:#fff">⏹ Stop</button>';
      } else {
        h += '<button onclick="startDrawingFor(\'' + esc(regionName) + '\',' + (i-1) + ')" style="padding:3px 6px;font-size:.7rem;font-weight:600;border:none;border-radius:4px;cursor:pointer;background:' + (hasRegion ? '#43a047' : '#1976d2') + ';color:#fff">' + (hasRegion ? '✏️ Redraw' : '✏️ Draw') + '</button>';
      }

      if (hasRegion && !isActive) {
        h += '<span style="background:#e8f5e9;color:#2e7d32;border-radius:10px;padding:1px 7px;font-size:.68rem;font-weight:700;flex-shrink:0">' + srCount + ' SR' + (srCount !== 1 ? 's' : '') + '</span>';
        h += '<button onclick="startEditRegion(\'' + esc(regionName) + '\')" title="Edit vertices" style="padding:3px 5px;font-size:.7rem;border:none;border-radius:4px;cursor:pointer;background:#e3f2fd;color:#1565c0">✏</button>';
        h += '<button onclick="clearCustomRegion(\'' + esc(regionName) + '\')" title="Remove zone" style="padding:3px 5px;font-size:.7rem;border:none;border-radius:4px;cursor:pointer;background:#ffebee;color:#c62828">✕</button>';
      } else if (!isActive) {
        h += '<span style="flex:1;font-size:.68rem;color:#adb5bd;font-style:italic">draw first</span>';
      }

      h += '</div>';
    }
    container.innerHTML = h;
    w.updateAffinityBadge();
  };

  w.updateRegionSrCount = function(input: HTMLInputElement) {
    const regionName = input.getAttribute('data-region');
    let val = parseInt(input.value, 10);
    if (isNaN(val) || val < 1) { val = 1; input.value = '1'; }
    if (regionName) w.regionSrCounts[regionName] = val;
    w.renderDrawList();
  };

  w.updateSrZone = function(select: HTMLSelectElement) {
    const srName = select.getAttribute('data-sr-zone');
    const zone = select.value;
    if (!srName) return;
    if (zone) {
      w.srZoneMap[srName] = zone;
    } else {
      delete w.srZoneMap[srName];
    }
    w.saveRegionsToStorage();
  };

  w.showSrZoneSaved = function() {
    const msg = document.getElementById('sr-zone-saved-msg');
    if (msg) {
      msg.style.display = 'block';
      setTimeout(() => { msg.style.display = 'none'; }, 3000);
    }
  };

  w.startEditRegion = function(regionName: string) {
    if (editingRegionName) w.stopEditRegion(editingRegionName, false);

    const layer = w.customRegionLayers[regionName];
    if (!layer) return;

    editingRegionName = regionName;

    layer.pm.enable({
      allowSelfIntersection: false,
      draggable: true,
      addVertexOn: 'click',
      removeVertexOn: 'contextmenu'
    });

    w.setDrawStatus(
      '✏️ Editing <strong>' + esc(regionName) + '</strong> — drag vertices to reshape. ' +
      '<button onclick="stopEditRegion(\'' + esc(regionName) + '\', true)" ' +
      'style="padding:2px 8px;font-size:.72rem;font-weight:600;border:none;border-radius:4px;cursor:pointer;background:#43a047;color:#fff;margin-left:6px">✅ Done</button>'
    );

    w.map.fitBounds(layer.getBounds(), { padding: [40, 40] });
  };

  w.stopEditRegion = function(regionName: string, save: boolean) {
    const layer = w.customRegionLayers[regionName];
    if (!layer) { editingRegionName = null; return; }

    layer.pm.disable();
    editingRegionName = null;

    if (save) {
      let latlngs = layer.getLatLngs();
      const flat = Array.isArray(latlngs[0]) ? latlngs[0] : latlngs;
      const coords = flat.map((ll: any) => [ll.lat, ll.lng]);

      if (coords.length >= 3) {
        w.customRegionMap[regionName] = { latlngs: coords, color: w.customRegionMap[regionName].color };
        w.map.removeLayer(layer);
        const color = w.customRegionMap[regionName].color;
        const newLayer = w.L.polygon(coords, {
          color: color, weight: 2.5, opacity: 0.9,
          fillColor: color, fillOpacity: 0.15, dashArray: null
        }).addTo(w.map);
        newLayer.bindTooltip('<span style="font-size:.75rem;font-weight:600;color:' + color + '">' + esc(regionName) + '</span>', { sticky: true });
        w.customRegionLayers[regionName] = newLayer;

        w.saveRegionsToStorage();
        w.setDrawStatus('✅ <strong>' + esc(regionName) + '</strong> updated and saved');
      }
    } else {
      w.setDrawStatus('');
    }

    w.renderDrawList();
  };

  w.simplifyPolygon = function(points: any[], tolerance: number) {
    if (points.length <= 4) return points;
    return dpSimplify(points, tolerance);
  };

  function dpSimplify(pts: any[], tol: number): any[] {
    if (pts.length <= 2) return pts;
    let maxDist = 0, maxIdx = 0;
    const start = pts[0], end = pts[pts.length - 1];
    for (let i = 1; i < pts.length - 1; i++) {
      const d = perpendicularDist(pts[i], start, end);
      if (d > maxDist) { maxDist = d; maxIdx = i; }
    }
    if (maxDist > tol) {
      const left = dpSimplify(pts.slice(0, maxIdx + 1), tol);
      const right = dpSimplify(pts.slice(maxIdx), tol);
      return left.slice(0, left.length - 1).concat(right);
    }
    return [start, end];
  }

  function perpendicularDist(pt: any, lineStart: any, lineEnd: any) {
    const dx = lineEnd[0] - lineStart[0];
    const dy = lineEnd[1] - lineStart[1];
    if (dx === 0 && dy === 0) {
      return Math.sqrt(Math.pow(pt[0] - lineStart[0], 2) + Math.pow(pt[1] - lineStart[1], 2));
    }
    let t = ((pt[0] - lineStart[0]) * dx + (pt[1] - lineStart[1]) * dy) / (dx * dx + dy * dy);
    t = Math.max(0, Math.min(1, t));
    return Math.sqrt(Math.pow(pt[0] - (lineStart[0] + t * dx), 2) + Math.pow(pt[1] - (lineStart[1] + t * dy), 2));
  }

  w.startDrawingFor = function(regionName: string, colorIndex: number) {
    w.stopDrawing();
    activeDrawRegion = regionName;
    drawingActive = true;
    const color = REGION_COLORS[colorIndex % REGION_COLORS.length];

    if (w.activeMapProvider === 'google') {
      if (!w.gmapDrawingManager) {
        w.showAllocError('Google Maps drawing not available. Please wait for the map to fully load.');
        activeDrawRegion = null;
        drawingActive = false;
        return;
      }
      w.setDrawStatus('✏️ Drawing <strong>' + esc(regionName) + '</strong> on Google Maps — click to place vertices, click first point to close polygon');
      w.gmapDrawingManager.setOptions({
        polygonOptions: {
          strokeColor: color,
          fillColor: color,
          strokeWeight: 2.5,
          fillOpacity: 0.18,
          editable: false,
          zIndex: 10
        }
      });
      w.gmapDrawingManager.setDrawingMode(google.maps.drawing.OverlayType.POLYGON);
      w.renderDrawList();
      return;
    }

    if (!w.map.pm) {
      w.showAllocError('Drawing plugin not loaded. Please refresh the page.');
      activeDrawRegion = null;
      drawingActive = false;
      return;
    }

    w.setDrawStatus('✏️ Drawing <strong>' + esc(regionName) + '</strong> — hold and drag to draw freehand, or click to place vertices, double-click to finish');

    w.map.pm.enableDraw('Polygon', {
      snappable: false,
      cursorMarker: true,
      allowSelfIntersection: false,
      continueDrawing: false,
      freehand: true,
      freehandInterval: 50,
      templineStyle: { color: color, weight: 2, dashArray: '5,5' },
      hintlineStyle: { color: color, weight: 1, dashArray: '5,5' },
      pathOptions: { color: color, weight: 2.5, fillColor: color, fillOpacity: 0.18 }
    });

    w.map.once('pm:create', (e: any) => {
      drawingActive = false;
      w.map.pm.disableDraw();
      const layer = e.layer;
      const latlngs = layer.getLatLngs()[0];

      const validation = w.validateCustomRegion(latlngs);
      if (!validation.valid) {
        w.map.removeLayer(layer);
        w.setDrawStatus('❌ ' + validation.reason + ' — please try again');
        activeDrawRegion = null;
        w.renderDrawList();
        return;
      }

      if (w.customRegionLayers[regionName]) w.map.removeLayer(w.customRegionLayers[regionName]);

      const rawCoords = latlngs.map((ll: any) => [ll.lat, ll.lng]);
      const coords = w.simplifyPolygon(rawCoords, 0.00008);
      const finalCoords = coords.length >= 3 ? coords : rawCoords;
      w.customRegionMap[regionName] = { latlngs: finalCoords, color: color };

      w.map.removeLayer(layer);
      const permLayer = w.L.polygon(finalCoords, {
        color: color, weight: 2.5, opacity: 0.9,
        fillColor: color, fillOpacity: 0.15, dashArray: null
      }).addTo(w.map);
      permLayer.bindTooltip('<span style="font-size:.75rem;font-weight:600;color:' + color + '">' + esc(regionName) + '</span>', { sticky: true });
      w.customRegionLayers[regionName] = permLayer;

      activeDrawRegion = null;
      w.setDrawStatus('✅ <strong>' + esc(regionName) + '</strong> saved (' + finalCoords.length + ' points, simplified from ' + rawCoords.length + ')');
      w.saveRegionsToStorage();
      w.renderDrawList();
    });

    w.renderDrawList();
  };

  w.stopDrawing = function() {
    if (editingRegionName) w.stopEditRegion(editingRegionName, false);
    if (w.map.pm && w.map.pm.globalDrawModeEnabled()) {
      w.map.pm.disableDraw();
    }
    if (w.gmapDrawingManager) {
      w.gmapDrawingManager.setDrawingMode(null);
    }
    drawingActive = false;
    if (activeDrawRegion) {
      w.setDrawStatus('Drawing cancelled for <strong>' + esc(activeDrawRegion) + '</strong>');
      activeDrawRegion = null;
      w.renderDrawList();
    }
  };

  w.clearCustomRegion = function(regionName: string) {
    if (w.customRegionLayers[regionName]) {
      w.map.removeLayer(w.customRegionLayers[regionName]);
      delete w.customRegionLayers[regionName];
    }
    if (w.gmapCustomRegionPolygons[regionName]) {
      w.gmapCustomRegionPolygons[regionName].setMap(null);
      delete w.gmapCustomRegionPolygons[regionName];
    }
    delete w.customRegionMap[regionName];
    delete w.regionSrCounts[regionName];
    w.renderDrawList();
    w.setDrawStatus('Region cleared: <strong>' + esc(regionName) + '</strong>');
  };

  w.validateCustomRegion = function(latlngs: any[]) {
    if (!latlngs || latlngs.length < 3) {
      return { valid: false, reason: 'Polygon needs at least 3 points' };
    }

    let area = 0;
    const n = latlngs.length;
    for (let i = 0; i < n; i++) {
      const j = (i + 1) % n;
      area += latlngs[i].lat * latlngs[j].lng;
      area -= latlngs[j].lat * latlngs[i].lng;
    }
    area = Math.abs(area) / 2;
    const areaKm2 = area * 111 * 111;
    if (areaKm2 < 0.01) {
      return { valid: false, reason: 'Region is too small (min 0.01 km²)' };
    }

    if (w.hubBoundaryLayer) {
      const hubLatLngs = w.hubBoundaryLayer.getLatLngs()[0];
      if (hubLatLngs && hubLatLngs.length > 0) {
        const anyInside = latlngs.some((pt: any) => {
          return pointInLeafletPolygon(pt.lat, pt.lng, hubLatLngs);
        });
        if (!anyInside) {
          const hubCenter = w.hubBoundaryLayer.getBounds().getCenter();
          const hubInDrawn = pointInLeafletPolygon(hubCenter.lat, hubCenter.lng, latlngs);
          if (!hubInDrawn) {
            return { valid: false, reason: 'Region must overlap with the hub service boundary' };
          }
        }
      }
    }

    return { valid: true };
  };

  function pointInLeafletPolygon(lat: number, lng: number, ring: any[]) {
    let inside = false;
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      const xi = ring[i].lng, yi = ring[i].lat;
      const xj = ring[j].lng, yj = ring[j].lat;
      const intersect = ((yi > lat) !== (yj > lat)) && (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi);
      if (intersect) inside = !inside;
    }
    return inside;
  }

  w.validateCustomRegionGmap = function(latlngs: any[]) {
    if (!latlngs || latlngs.length < 3) {
      return { valid: false, reason: 'Polygon needs at least 3 points' };
    }

    let area = 0;
    const n = latlngs.length;
    for (let i = 0; i < n; i++) {
      const j = (i + 1) % n;
      area += latlngs[i].lat * latlngs[j].lng;
      area -= latlngs[j].lat * latlngs[i].lng;
    }
    area = Math.abs(area) / 2;
    const areaKm2 = area * 111 * 111;
    if (areaKm2 < 0.01) {
      return { valid: false, reason: 'Region is too small (min 0.01 km²)' };
    }

    return { valid: true };
  };

  w.highlightPincodeOnMap = function(pincode: string) {
    // Placeholder - would highlight pincode boundary if available
    console.log('Highlight pincode:', pincode);
  };

  w.showAffinityPanel = function() {
    // Placeholder for showing affinity panel
  };

  w.loadAvailablePincodes = function() {
    // Placeholder for loading pincodes
  };

  w.renderAffinityList = function() {
    // Placeholder for affinity list
  };

  w.updateAffinityBadge = function() {
    const badge = document.getElementById('affinity-match-badge');
    if (!badge) return;
    const drawnCount = Object.keys(w.customRegionMap || {}).length;
    badge.textContent = drawnCount + ' drawn';
  };

  w.clearAllAffinities = function() {
    Object.keys(w.customRegionLayers || {}).forEach((r: string) => {
      if (w.customRegionLayers[r]) w.map.removeLayer(w.customRegionLayers[r]);
    });
    Object.keys(w.gmapCustomRegionPolygons || {}).forEach((r: string) => {
      if (w.gmapCustomRegionPolygons[r]) w.gmapCustomRegionPolygons[r].setMap(null);
    });
    w.customRegionMap = {};
    w.customRegionLayers = {};
    w.gmapCustomRegionPolygons = {};
    w.regionSrCounts = {};
    w.srZoneMap = {};
    w.srAffinityMap = {};
    plannedRegionCount = 0;
    const input = document.getElementById('region-count-input') as HTMLInputElement;
    if (input) input.value = '0';
    w.setDrawStatus('All affinity zones cleared');
    w.renderDrawList();
    w.updateAffinityBadge();
    w.saveRegionsToStorage();
  };

  w.saveRegionsToStorage = function() {
    const payload = {
      customRegionMap: w.customRegionMap || {},
      srZoneMap: w.srZoneMap || {},
      regionSrCounts: w.regionSrCounts || {}
    };
    fetch('/api/affinity-config/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
      .then((r) => r.json())
      .then(() => {
        w.showAllocInfo('✅ Affinity configuration saved');
      })
      .catch(() => {
        w.showAllocError('Failed to save affinity configuration');
      });
  };

  w.loadRegionsFromStorage = function() {
    fetch('/api/affinity-config/load')
      .then((r) => r.json())
      .then((data: any) => {
        if (data && data.customRegionMap) {
          w.customRegionMap = data.customRegionMap;
          w.srZoneMap = data.srZoneMap || {};
          w.regionSrCounts = data.regionSrCounts || {};

          Object.keys(w.customRegionLayers || {}).forEach((r: string) => {
            if (w.customRegionLayers[r]) w.map.removeLayer(w.customRegionLayers[r]);
          });
          w.customRegionLayers = {};

          Object.keys(w.customRegionMap).forEach((regionName: string) => {
            const region = w.customRegionMap[regionName];
            if (region && region.latlngs && region.latlngs.length >= 3) {
              const layer = w.L.polygon(region.latlngs, {
                color: region.color,
                weight: 2.5,
                opacity: 0.9,
                fillColor: region.color,
                fillOpacity: 0.15,
                dashArray: null
              }).addTo(w.map);
              layer.bindTooltip('<span style="font-size:.75rem;font-weight:600;color:' + region.color + '">' + esc(regionName) + '</span>', { sticky: true });
              w.customRegionLayers[regionName] = layer;
            }
          });

          plannedRegionCount = Object.keys(w.customRegionMap).length;
          const input = document.getElementById('region-count-input') as HTMLInputElement;
          if (input) input.value = String(plannedRegionCount);

          w.renderDrawList();
          w.updateAffinityBadge();
          w.showAllocInfo('✅ Affinity configuration loaded');
        }
      })
      .catch(() => {
        w.showAllocError('Failed to load affinity configuration');
      });
  };

  // ══════════════════════════════════════════════════════════════════════════════
  // ALLOCATION MODES
  // ══════════════════════════════════════════════════════════════════════════════

  w.getAllocationMode = function() {
    return localStorage.getItem('allocationMode') || 'count-based';
  };

  w.setAllocationMode = function(mode: string) {
    localStorage.setItem('allocationMode', mode);
    w.applyAllocationModeUI(mode);
  };

  w.applyAllocationModeUI = function(mode: string) {
    const countBtn = document.getElementById('mode-count-btn');
    const timeBtn = document.getElementById('mode-timebased-btn');
    const capacityInputs = document.getElementById('capacity-inputs');
    const shiftIndicator = document.getElementById('shift-duration-indicator');

    if (mode === 'count-based') {
      if (countBtn) {
        countBtn.style.background = 'var(--primary)';
        countBtn.style.color = '#fff';
      }
      if (timeBtn) {
        timeBtn.style.background = 'transparent';
        timeBtn.style.color = 'var(--text-sub)';
      }
      if (capacityInputs) capacityInputs.style.display = 'block';
      if (shiftIndicator) shiftIndicator.style.display = 'none';
    } else {
      if (countBtn) {
        countBtn.style.background = 'transparent';
        countBtn.style.color = 'var(--text-sub)';
      }
      if (timeBtn) {
        timeBtn.style.background = 'var(--primary)';
        timeBtn.style.color = '#fff';
      }
      if (capacityInputs) capacityInputs.style.display = 'none';
      if (shiftIndicator) shiftIndicator.style.display = 'block';
    }
  };

  w.updateShiftDurationLabel = function(shiftDurationMinutes: number) {
    const label = document.getElementById('shift-duration-label');
    if (!label) return;
    const hours = Math.floor(shiftDurationMinutes / 60);
    const mins = shiftDurationMinutes % 60;
    let text = hours + ' h';
    if (mins > 0) text += ' ' + mins + ' min';
    text += ' shift';
    label.textContent = text;
  };

  w.runAffinityAllocation = function() {
    const date = (document.getElementById('date-select') as HTMLSelectElement)?.value;
    if (!date) {
      w.showAllocError('Select a date first.');
      return;
    }

    const drawnCount = Object.keys(w.customRegionMap || {}).length;
    if (drawnCount === 0) {
      w.showAllocError('Draw at least one affinity zone first.');
      return;
    }

    w.showAllocLoading('Affinity Allocation', { showProgress: true });

    const payload = {
      date: date,
      customRegions: Object.keys(w.customRegionMap).map((name: string) => ({
        name: name,
        polygon: w.customRegionMap[name].latlngs,
        srCount: w.regionSrCounts[name] || 1
      })),
      srZoneMap: w.srZoneMap || {}
    };

    fetch('/api/affinity-match/allocate-custom', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
      .then((r) => {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then((data: any) => {
        w.hideAllocLoading();
        w._handleAffinityResult(data, date);
      })
      .catch((err: Error) => {
        w.hideAllocLoading();
        w.showAllocError('Affinity allocation failed: ' + err.message);
      });
  };

  w._handleAffinityResult = function(data: any, date: string) {
    if (!data || !data.srSummaries) {
      w.showAllocError('Invalid allocation result');
      return;
    }

    w.renderSummary(data);
    w.renderSrList(data.srSummaries);
    if (data.regionSummaries) w.renderRegionHealth(data);
    
    w.lastAllocationMode = 'affinity';
    w.hasOsmAllocation = false;
    w.hasGoogleAllocation = false;
    
    w.clearMarkers();
    w.renderAllSrsOnMap(data.srSummaries, date, 'affinity', null);
    
    w.showAllocInfo('✅ Affinity allocation complete');
    w.unlockSidebarTab('monitor');
    w.switchSidebarTab('monitor');
  };

  // ══════════════════════════════════════════════════════════════════════════════
  // PREVIOUS ALLOCATION
  // ══════════════════════════════════════════════════════════════════════════════

  w.loadPreviousAllocation = function() {
    const date = (document.getElementById('date-select') as HTMLSelectElement)?.value;
    if (!date) {
      w.showAllocError('Select a date first.');
      return;
    }

    w.showAllocLoading('Loading Previous Allocation', { showProgress: false });

    fetch('/api/previous/' + encodeURIComponent(date) + '/summary')
      .then((r) => {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then((data: any) => {
        w.hideAllocLoading();
        if (!data || !data.srSummaries) {
          w.showAllocError('No previous allocation found for this date');
          return;
        }

        w.renderSummary(data);
        w.renderSrList(data.srSummaries);
        w.clearMarkers();
        w.renderPreviousOnMap(data.srSummaries, date, null);
        w.showAllocInfo('✅ Previous allocation loaded');
        w.unlockSidebarTab('monitor');
        w.switchSidebarTab('monitor');
      })
      .catch((err: Error) => {
        w.hideAllocLoading();
        w.showAllocError('Failed to load previous allocation: ' + err.message);
      });
  };

  w.renderPreviousOnMap = function(srSummaries: any[], date: string, onComplete: any) {
    // Placeholder implementation
    console.log('Render previous allocation:', srSummaries.length, 'SRs');
    if (onComplete) onComplete();
  };

  w.selectPreviousSr = function(srName: string, index: number) {
    console.log('Select previous SR:', srName, index);
  };

  // ══════════════════════════════════════════════════════════════════════════════
  // LM INTEGRATION
  // ══════════════════════════════════════════════════════════════════════════════

  w.lmShowPanel = function(srSummaries: any[]) {
    const panel = document.getElementById('lm-panel');
    if (!panel) return;
    panel.classList.remove('hidden');

    const mapping = document.getElementById('lm-sr-mapping');
    if (!mapping) return;

    let h = '';
    srSummaries.forEach((sr: any, idx: number) => {
      const color = w.SR_COLORS[idx % w.SR_COLORS.length];
      h += '<div class="lm-sr-row">';
      h += '<div class="lm-sr-dot" style="background:' + color + '"></div>';
      h += '<span class="lm-sr-name">' + esc(sr.srName) + '</span>';
      h += '<span class="lm-sr-count">(' + (sr.shipmentCount || 0) + ')</span>';
      h += '<input type="text" class="lm-sr-input" placeholder="LM delivery user ID" data-sr="' + esc(sr.srName) + '"/>';
      h += '<span class="lm-sr-status"></span>';
      h += '</div>';
    });
    mapping.innerHTML = h;
  };

  w.lmConnect = function() {
    w.showAllocInfo('Connecting to LM system...');
    fetch('/api/lm/refresh-token')
      .then((r) => r.json())
      .then((data: any) => {
        const tokenInfo = document.getElementById('lm-token-info');
        const statusBadge = document.getElementById('lm-status-badge');
        if (data.valid) {
          if (tokenInfo) tokenInfo.textContent = '✅ Token valid';
          if (statusBadge) {
            statusBadge.textContent = 'Connected';
            statusBadge.style.background = '#4caf50';
          }
          w.showAllocInfo('✅ Connected to LM system');
          w.lmFetchDashboard();
        } else {
          if (tokenInfo) tokenInfo.textContent = '❌ Token invalid - re-auth required';
          w.showAllocError('LM token invalid');
        }
      })
      .catch(() => {
        w.showAllocError('Failed to connect to LM system');
      });
  };

  w.lmFetchDashboard = function() {
    fetch('/api/lm/dashboard')
      .then((r) => r.json())
      .then((data: any) => {
        const pendingEl = document.getElementById('lm-pending-count');
        const allocatedEl = document.getElementById('lm-allocated-count');
        if (pendingEl) pendingEl.textContent = String(data.pending || 0);
        if (allocatedEl) allocatedEl.textContent = String(data.allocated || 0);
      })
      .catch(() => {
        w.showAllocError('Failed to fetch LM dashboard');
      });
  };

  w.lmLoadDeliveryUsers = function() {
    fetch('/api/lm/delivery-users')
      .then((r) => r.json())
      .then((users: any[]) => {
        console.log('LM delivery users loaded:', users.length);
        w.showAllocInfo('✅ Loaded ' + users.length + ' delivery users');
      })
      .catch(() => {
        w.showAllocError('Failed to load delivery users');
      });
  };

  w.lmPushAll = function() {
    w.showAllocInfo('Pushing all routes to LM system...');
    const mappings: any = {};
    document.querySelectorAll('.lm-sr-input').forEach((input: any) => {
      const srName = input.getAttribute('data-sr');
      const userId = input.value.trim();
      if (srName && userId) mappings[srName] = userId;
    });

    fetch('/api/lm/push-all', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ srMappings: mappings })
    })
      .then((r) => r.json())
      .then((results: any[]) => {
        let success = 0;
        results.forEach((r: any) => {
          if (r.code === 200) success++;
        });
        w.showAllocInfo('✅ Pushed ' + success + '/' + results.length + ' routes');
      })
      .catch(() => {
        w.showAllocError('Failed to push routes to LM');
      });
  };

  w.lmConfirmAll = function() {
    w.showAllocInfo('Confirming all trips in LM system...');
    fetch('/api/lm/confirm', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({})
    })
      .then((r) => r.json())
      .then((results: any[]) => {
        let success = 0;
        results.forEach((r: any) => {
          if (r.code === 200) success++;
        });
        w.showAllocInfo('✅ Confirmed ' + success + '/' + results.length + ' trips');
      })
      .catch(() => {
        w.showAllocError('Failed to confirm trips in LM');
      });
  };

  w.setDrawStatus = function(html: string) {
    const el = document.getElementById('affinity-draw-status');
    if (el) el.innerHTML = html;
  };

  // Initialize - load affinity config on startup
  setTimeout(() => {
    w.loadRegionsFromStorage();
  }, 1000);
}
