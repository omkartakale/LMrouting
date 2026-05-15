// @ts-nocheck
/* eslint-disable */
/**
 * Legacy allocation runtime — the original index.html JS, ported verbatim into
 * a TypeScript module. Behaviour, fetch URLs, DOM IDs, global state and call
 * graph are all preserved so the React layer is a faithful 1:1 port.
 *
 * Boot lifecycle:
 *   bootAllocationApp() is called once from the React AllocationPage component
 *   after the JSX skeleton has mounted. It mirrors the original
 *   `window.addEventListener('load', ...)` block.
 *
 * All functions are exposed on `window` so legacy inline-onclick handlers in
 * the JSX (e.g. onClick={() => loadPreviousAllocation()}) continue to resolve.
 */

let booted = false;

export function bootAllocationApp() {
  if (booted) return;
  booted = true;
  runLegacyAllocationModule();
}

function runLegacyAllocationModule() {
  const L: any = (window as any).L;

  var HUB_LAT = 18.4600561;
  var HUB_LNG = 73.8884305;
  var SR_COLORS = ['#e53935','#1e88e5','#43a047','#fb8c00','#8e24aa','#00acc1','#f4511e','#3949ab','#7cb342','#c0ca33','#6d4c41','#546e7a','#d81b60','#039be5','#00897b','#ffb300','#5e35b1','#1565c0','#2e7d32','#ef6c00'];

  var map, hubMarker;
  var srLayers: any = {};
  var currentSrName = null;
  var currentSrIndex = 0;
  var allocationFinalized = false;
  var presentSrNames: string[] = [];
  var pendingReassign: any = null;
  var selectedFile: any = null;
  var lastAllocationMode = null;
  var hasOsmAllocation = false;
  var hasGoogleAllocation = false;
  var googleMapsConfigured = false;
  var orsConfigured = false;

  var hubBoundaryLayer = null;
  var hubOriginalBoundaryLayer = null;
  var lastHubName = null;

  var pincodeBoundaryLayers: any[] = [];
  var pincodeBoundaryVisible = false;

  var gmap: any = null;
  var gmapMarkers: any[] = [];
  var gmapPolylines: any[] = [];
  var gmapHubMarker: any = null;
  var gmapDrawingManager: any = null;
  var gmapMarkerClusterer: any = null;
  var gmapCustomRegionPolygons: any = {};
  var activeMapProvider = 'leaflet';

  function esc(s) {
    if (s == null) return '';
    var d = document.createElement('div');
    d.appendChild(document.createTextNode(String(s)));
    return d.innerHTML;
  }

  function showAllocError(msg) {
    var el = document.getElementById('alloc-error');
    if (!el) return;
    el.textContent = msg;
    (el as HTMLElement).style.display = 'block';
    try { console.error('Allocation error:', msg); } catch(e){}
    setTimeout(function() { (el as HTMLElement).style.display = 'none'; }, 20000);
  }

  function showAllocInfo(msg) {
    var el = document.getElementById('alloc-info');
    if (!el) return;
    el.textContent = msg;
    (el as HTMLElement).style.display = 'block';
    setTimeout(function() { (el as HTMLElement).style.display = 'none'; }, 6000);
  }

  function hideAllocError() {
    var el = document.getElementById('alloc-error');
    if (el) (el as HTMLElement).style.display = 'none';
  }

  function toggleMapFullscreen() {
    var body = document.body;
    var btn = document.getElementById('fullscreen-btn');
    body.classList.toggle('map-fullscreen');
    if (body.classList.contains('map-fullscreen')) {
      if (btn) { btn.textContent = '✕'; (btn as HTMLElement).title = 'Exit fullscreen'; }
    } else {
      if (btn) { btn.textContent = '⛶'; (btn as HTMLElement).title = 'Toggle fullscreen map'; }
    }
    setTimeout(function() {
      if (activeMapProvider === 'leaflet') map.invalidateSize();
      else if (gmap) (window as any).google.maps.event.trigger(gmap, 'resize');
    }, 200);
  }

  function toggleMapProvider() {
    var btn = document.getElementById('map-toggle-btn');
    if (activeMapProvider === 'leaflet') {
      if (!(window as any).gmapReady) {
        showAllocError('Google Maps is still loading. Try again in a moment.');
        return;
      }
      activeMapProvider = 'google';
      (document.getElementById('map') as HTMLElement).style.display = 'none';
      (document.getElementById('gmap') as HTMLElement).style.display = 'block';
      if (btn) { btn.textContent = '🗺 Leaflet'; (btn as HTMLElement).title = 'Switch to Leaflet/OSM'; }
      initGoogleMap();
      syncToGoogleMap();
    } else {
      activeMapProvider = 'leaflet';
      (document.getElementById('gmap') as HTMLElement).style.display = 'none';
      (document.getElementById('map') as HTMLElement).style.display = 'block';
      if (btn) { btn.textContent = '🗺 Google'; (btn as HTMLElement).title = 'Switch to Google Maps'; }
      setTimeout(function() {
        map.invalidateSize();
        if (hubBoundaryLayer && !map.hasLayer(hubBoundaryLayer)) hubBoundaryLayer.addTo(map);
        if (hubOriginalBoundaryLayer && !map.hasLayer(hubOriginalBoundaryLayer)) hubOriginalBoundaryLayer.addTo(map);
        if (!hubBoundaryLayer && lastHubName) fetchHubBoundary(lastHubName);
        Object.keys(customRegionLayers).forEach(function(r) {
          if (customRegionLayers[r] && !map.hasLayer(customRegionLayers[r])) customRegionLayers[r].addTo(map);
        });
      }, 100);
    }
  }

  function initGoogleMap() {
    var google = (window as any).google;
    if (gmap) {
      gmap.setCenter({ lat: HUB_LAT, lng: HUB_LNG });
      google.maps.event.trigger(gmap, 'resize');
      return;
    }
    gmap = new google.maps.Map(document.getElementById('gmap'), {
      center: { lat: HUB_LAT, lng: HUB_LNG },
      zoom: 13, mapTypeId: 'roadmap',
      mapTypeControl: true,
      mapTypeControlOptions: { position: google.maps.ControlPosition.TOP_RIGHT },
      streetViewControl: false, fullscreenControl: false,
      gestureHandling: 'greedy', clickableIcons: false
    });
    gmapHubMarker = new google.maps.Marker({
      position: { lat: HUB_LAT, lng: HUB_LNG }, map: gmap, title: 'Hub',
      icon: { path: google.maps.SymbolPath.CIRCLE, scale: 10, fillColor: '#e53935', fillOpacity: 1, strokeColor: '#fff', strokeWeight: 3 },
      zIndex: 1000
    });
    var infoWindow = new google.maps.InfoWindow({ content: '<b>Hub</b>' });
    gmapHubMarker.addListener('click', function() { infoWindow.open(gmap, gmapHubMarker); });
    if (google.maps.drawing) {
      gmapDrawingManager = new google.maps.drawing.DrawingManager({
        drawingMode: null, drawingControl: false,
        polygonOptions: { strokeWeight: 2.5, fillOpacity: 0.15, editable: false, zIndex: 10 }
      });
      gmapDrawingManager.setMap(gmap);
      google.maps.event.addListener(gmapDrawingManager, 'polygoncomplete', function(polygon) {
        gmapDrawingManager.setDrawingMode(null);
        if (!activeDrawRegion) { polygon.setMap(null); return; }
        var regionName = activeDrawRegion;
        var colorIndex = parseInt(regionName.replace('Region ', ''), 10) - 1;
        var color = REGION_COLORS[colorIndex % REGION_COLORS.length];
        var path = polygon.getPath();
        var latlngs: any[] = [];
        for (var i = 0; i < path.getLength(); i++) {
          var pt = path.getAt(i);
          latlngs.push({ lat: pt.lat(), lng: pt.lng() });
        }
        var validation = validateCustomRegionGmap(latlngs);
        if (!validation.valid) {
          polygon.setMap(null);
          setDrawStatus('❌ ' + validation.reason + ' — please try again');
          activeDrawRegion = null; drawingActive = false; renderDrawList(); return;
        }
        if (gmapCustomRegionPolygons[regionName]) gmapCustomRegionPolygons[regionName].setMap(null);
        polygon.setOptions({ strokeColor: color, fillColor: color, strokeOpacity: 0.9, fillOpacity: 0.15 });
        gmapCustomRegionPolygons[regionName] = polygon;
        var rawCoords = latlngs.map(function(ll) { return [ll.lat, ll.lng]; });
        var coords = simplifyPolygon(rawCoords, 0.00008);
        if (coords.length < 3) coords = rawCoords;
        customRegionMap[regionName] = { latlngs: coords, color: color };
        if (customRegionLayers[regionName]) map.removeLayer(customRegionLayers[regionName]);
        var permLayer = L.polygon(coords, { color: color, weight: 2.5, opacity: 0.9, fillColor: color, fillOpacity: 0.15, dashArray: null });
        if (activeMapProvider === 'leaflet') permLayer.addTo(map);
        permLayer.bindTooltip('<span style="font-size:.75rem;font-weight:600;color:' + color + '">' + esc(regionName) + '</span>', { sticky: true });
        customRegionLayers[regionName] = permLayer;
        activeDrawRegion = null; drawingActive = false;
        setDrawStatus('✅ <strong>' + esc(regionName) + '</strong> saved (' + coords.length + ' points, simplified from ' + rawCoords.length + ')');
        saveRegionsToStorage();
        renderDrawList();
      });
    }
  }

  function syncToGoogleMap() {
    if (!gmap) return;
    var google = (window as any).google;
    if (gmapMarkerClusterer) { gmapMarkerClusterer.clearMarkers(); gmapMarkerClusterer = null; }
    gmapMarkers.forEach(function(m) { m.setMap(null); });
    gmapPolylines.forEach(function(p) { p.setMap(null); });
    gmapMarkers = []; gmapPolylines = [];
    if (hubBoundaryLayer) {
      try {
        var hubCoords = hubBoundaryLayer.getLatLngs()[0];
        if (hubCoords && hubCoords.length) {
          var hubPath = hubCoords.map(function(ll) { return { lat: ll.lat, lng: ll.lng }; });
          var hubPoly = new google.maps.Polygon({ paths: hubPath, strokeColor: '#b71c1c', strokeOpacity: 1.0, strokeWeight: 4, fillColor: '#e53935', fillOpacity: 0.04, map: gmap, zIndex: 1, clickable: false });
          gmapPolylines.push(hubPoly);
        }
      } catch(e) {}
    }
    Object.keys(customRegionMap).forEach(function(regionName) {
      var region = customRegionMap[regionName];
      if (!region || !region.latlngs || region.latlngs.length < 3) return;
      if (gmapCustomRegionPolygons[regionName]) return;
      var path = region.latlngs.map(function(c) { return { lat: c[0], lng: c[1] }; });
      var poly = new google.maps.Polygon({ paths: path, strokeColor: region.color, strokeOpacity: 0.9, strokeWeight: 2.5, fillColor: region.color, fillOpacity: 0.15, map: gmap, zIndex: 5, clickable: false });
      gmapCustomRegionPolygons[regionName] = poly;
    });
    Object.keys(srLayers).forEach(function(srName) {
      var layer = srLayers[srName];
      var color = layer.color || '#1976d2';
      if (layer.osmPolyline) {
        var latlngs = layer.osmPolyline.getLatLngs();
        var raw = latlngs.map(function(ll) { return [ll.lat, ll.lng]; });
        var stitched = stitchPolyline(raw, HUB_LAT, HUB_LNG, 250);
        var path = stitched.map(function(p) { return { lat: p[0], lng: p[1] }; });
        gmapPolylines.push(new google.maps.Polyline({ path: path, geodesic: true, strokeColor: color, strokeOpacity: 0.9, strokeWeight: 3.5, map: gmap, zIndex: 2 }));
      }
      if (layer.googlePolyline) {
        var latlngs2 = layer.googlePolyline.getLatLngs();
        var raw2 = latlngs2.map(function(ll) { return [ll.lat, ll.lng]; });
        var stitched2 = stitchPolyline(raw2, HUB_LAT, HUB_LNG, 250);
        var path2 = stitched2.map(function(p) { return { lat: p[0], lng: p[1] }; });
        gmapPolylines.push(new google.maps.Polyline({ path: path2, geodesic: true, strokeColor: color, strokeOpacity: 0.95, strokeWeight: 4, icons: [{ icon: { path: 'M 0,-1 0,1', strokeOpacity: 1, scale: 3 }, offset: '0', repeat: '12px' }], map: gmap, zIndex: 2 }));
      }
    });
    var allMarkers: any[] = [];
    Object.keys(srLayers).forEach(function(srName) {
      var layer = srLayers[srName];
      var color = layer.color || '#1976d2';
      if (!layer.markers) return;
      layer.markers.forEach(function(leafletMarker) {
        var ll = leafletMarker.getLatLng();
        var gMarker = new google.maps.Marker({
          position: { lat: ll.lat, lng: ll.lng },
          title: leafletMarker.options.title || srName,
          icon: { path: google.maps.SymbolPath.CIRCLE, scale: 6, fillColor: color, fillOpacity: 1, strokeColor: '#fff', strokeWeight: 2 },
          optimized: true
        });
        var popupContent = leafletMarker.getPopup() ? leafletMarker.getPopup().getContent() : '';
        if (popupContent) {
          (function(m, content) {
            var iw = new google.maps.InfoWindow({ content: content, maxWidth: 250 });
            m.addListener('click', function() { iw.open(gmap, m); });
          })(gMarker, popupContent);
        }
        allMarkers.push(gMarker);
        gmapMarkers.push(gMarker);
      });
    });
    if (allMarkers.length > 0) {
      var mc = (window as any).markerClusterer;
      if (mc && mc.MarkerClusterer) {
        gmapMarkerClusterer = new mc.MarkerClusterer({ map: gmap, markers: allMarkers, algorithm: new mc.SuperClusterAlgorithm({ maxZoom: 15, radius: 60 }) });
      } else {
        allMarkers.forEach(function(m) { m.setMap(gmap); });
      }
    }
    if (gmapMarkers.length > 0) {
      var bounds = new google.maps.LatLngBounds();
      bounds.extend({ lat: HUB_LAT, lng: HUB_LNG });
      var step = Math.max(1, Math.floor(gmapMarkers.length / 200));
      for (var i = 0; i < gmapMarkers.length; i += step) bounds.extend(gmapMarkers[i].getPosition());
      gmap.fitBounds(bounds, 40);
    }
  }

  function initMap() {
    map = L.map('map').setView([HUB_LAT, HUB_LNG], 13);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '&copy; OpenStreetMap contributors' }).addTo(map);
    ensureBoundaryPane();
    hubMarker = L.marker([HUB_LAT, HUB_LNG], {
      icon: L.divIcon({ className: '', html: '<div style="width:20px;height:20px;background:#e53935;border:3px solid #fff;border-radius:50%;box-shadow:0 2px 6px rgba(0,0,0,.4)"></div>', iconSize: [20, 20], iconAnchor: [10, 10] }),
      zIndexOffset: 1000, title: 'Hub'
    }).addTo(map).bindPopup('<b>Hub</b>');
    loadConfig();
  }

  function loadConfig() {
    fetch('/api/hub').then(function(r) { return r.json(); }).then(function(hub) {
      HUB_LAT = hub.hubLat; HUB_LNG = hub.hubLng; lastHubName = hub.hubName || null;
      if (cachedBoundaryData && cachedBoundaryData.hubName !== lastHubName) cachedBoundaryData = null;
      (document.getElementById('hub-info') as HTMLElement).textContent = hub.hubName + ' (' + hub.hubLat.toFixed(4) + ', ' + hub.hubLng.toFixed(4) + ')';
      map.setView([HUB_LAT, HUB_LNG], 13);
      hubMarker.setLatLng([HUB_LAT, HUB_LNG]);
      if (lastHubName) fetchHubBoundary(lastHubName);
      loadRegionsFromStorage();
    }).catch(function() {});
    fetch('/api/config').then(function(r) { return r.json(); }).then(function(cfg) {
      googleMapsConfigured = cfg.googleMapsConfigured === true;
      orsConfigured = cfg.orsConfigured === true;
      var osmBadge = document.getElementById('badge-osm');
      var googleBadge = document.getElementById('badge-google');
      if (osmBadge) { if (orsConfigured) { osmBadge.textContent = 'OSM Active'; osmBadge.className = 'api-badge active'; } else { osmBadge.textContent = 'OSM (straight lines)'; osmBadge.className = 'api-badge inactive'; } }
      if (googleBadge) { if (googleMapsConfigured) { googleBadge.textContent = 'Google Maps Active'; googleBadge.className = 'api-badge active'; } else { googleBadge.textContent = 'Google Maps (not configured)'; googleBadge.className = 'api-badge inactive'; } }
      if (!googleMapsConfigured) {
        var gb = document.getElementById('run-google-btn') as HTMLButtonElement;
        if (gb) { gb.disabled = true; gb.title = 'Google Maps API key not configured. Add it to application.properties.'; }
      }
      if (cfg.googleMapsApiKey) {
        (window as any).loadGoogleMapsScript(cfg.googleMapsApiKey);
      } else {
        var mt = document.getElementById('map-toggle-btn') as HTMLButtonElement;
        if (mt) { mt.disabled = true; mt.title = 'Google Maps API key not configured'; mt.style.opacity = '0.5'; }
      }
    }).catch(function() {
      var ob = document.getElementById('badge-osm'); if (ob) ob.textContent = 'OSM';
      var gb = document.getElementById('badge-google'); if (gb) gb.textContent = 'Google Maps';
    });
  }

  function togglePincodeBoundaries() {
    if (pincodeBoundaryVisible) {
      pincodeBoundaryLayers.forEach(function(l) { map.removeLayer(l); });
      pincodeBoundaryVisible = false;
      var btn = document.getElementById('pincode-boundary-btn');
      if (btn) { btn.textContent = '📍 Show Pincodes'; (btn as HTMLElement).style.opacity = '0.6'; }
      return;
    }
    if (pincodeBoundaryLayers.length > 0) {
      pincodeBoundaryLayers.forEach(function(l) { l.addTo(map); });
      pincodeBoundaryVisible = true;
      var btn = document.getElementById('pincode-boundary-btn');
      if (btn) { btn.textContent = '📍 Hide Pincodes'; (btn as HTMLElement).style.opacity = '1'; }
      return;
    }
    fetch('/api/pincode-boundary').then(function(r) { return r.json(); }).then(function(data) {
      if (!data.loaded || !data.polygons || !data.polygons.length) { showAllocInfo('No pincode boundaries loaded'); return; }
      var colors = ['#1565c0','#2e7d32','#e65100','#6a1b9a','#00838f','#c62828','#4527a0','#00695c','#bf360c','#283593'];
      data.polygons.forEach(function(p, i) {
        var color = colors[i % colors.length];
        p.rings.forEach(function(ring) {
          if (ring.length <= 5) return;
          var poly = L.polygon(ring, { color: color, weight: 1.5, opacity: 0.6, fillColor: color, fillOpacity: 0.08, interactive: true }).addTo(map);
          poly.bindTooltip('<span style="font-size:.75rem;font-weight:600">' + p.pincode + '</span>', { sticky: true, direction: 'center', className: '' });
          pincodeBoundaryLayers.push(poly);
        });
      });
      pincodeBoundaryVisible = true;
      var btn = document.getElementById('pincode-boundary-btn');
      if (btn) { btn.textContent = '📍 Hide Pincodes (' + data.pincodeCount + ')'; (btn as HTMLElement).style.opacity = '1'; }
      showAllocInfo('Loaded ' + data.pincodeCount + ' pincode boundaries');
    }).catch(function(e) { showAllocError('Failed to load pincode boundaries: ' + e.message); });
  }

  var cachedBoundaryData: any = null;

  function fetchHubBoundary(hubName) {
    if (!hubName) return;
    lastHubName = hubName;
    if (cachedBoundaryData && cachedBoundaryData.hubName === hubName) {
      drawHubBoundary(cachedBoundaryData.coordinates, cachedBoundaryData.originalBoundary || null, cachedBoundaryData.hubName, cachedBoundaryData.facilityName || hubName);
      return;
    }
    fetch('/api/hub/boundary?hubName=' + encodeURIComponent(hubName))
      .then(function(r) { if (r.status === 204 || !r.ok) { showBoundaryWarning(hubName); return null; } return r.json(); })
      .then(function(data) {
        if (!data) return;
        if (!data.coordinates || !data.coordinates.length) { showBoundaryWarning(hubName); return; }
        cachedBoundaryData = data;
        drawHubBoundary(data.coordinates, data.originalBoundary || null, data.hubName || hubName, data.facilityName || hubName);
      }).catch(function() { showBoundaryWarning(hubName); });
  }

  function showBoundaryWarning(hubName) {
    if (document.getElementById('boundary-warn')) return;
    var el = document.createElement('div');
    el.id = 'boundary-warn';
    el.style.cssText = 'position:absolute;bottom:50px;left:50%;transform:translateX(-50%);z-index:10002;background:rgba(255,248,225,.97);border:1px solid #ffe082;border-radius:6px;padding:7px 12px;font-size:.75rem;color:#e65100;font-weight:600;box-shadow:0 2px 8px rgba(0,0,0,.15);display:flex;align-items:center;gap:8px;white-space:nowrap';
    el.innerHTML = '⚠️ Hub boundary unavailable for <em>' + esc(hubName) + '</em> &nbsp;<button onclick="retryBoundary()" style="padding:2px 8px;font-size:.72rem;border:1px solid #e65100;border-radius:4px;background:#fff;color:#e65100;cursor:pointer;font-weight:600">Retry</button> <button onclick="document.getElementById(\'boundary-warn\').remove()" style="padding:2px 6px;font-size:.72rem;border:none;background:none;color:#999;cursor:pointer">✕</button>';
    var mc = document.getElementById('map-container');
    if (mc) mc.appendChild(el);
    setTimeout(function() { if (el.parentNode) el.remove(); }, 10000);
  }

  function retryBoundary() {
    var el = document.getElementById('boundary-warn'); if (el) el.remove();
    if (lastHubName) fetchHubBoundary(lastHubName);
  }

  function ensureBoundaryPane() {
    if (!map.getPane('hubBoundaryPane')) {
      map.createPane('hubBoundaryPane');
      map.getPane('hubBoundaryPane').style.zIndex = 200;
      map.getPane('hubBoundaryPane').style.pointerEvents = 'none';
    }
  }

  function drawHubBoundary(coords, originalCoords, hubName, facilityName) {
    ensureBoundaryPane();
    if (hubBoundaryLayer) { map.removeLayer(hubBoundaryLayer); hubBoundaryLayer = null; }
    if (hubOriginalBoundaryLayer) { map.removeLayer(hubOriginalBoundaryLayer); hubOriginalBoundaryLayer = null; }
    if (coords && coords.length) {
      var latLngs = coords.map(function(p) { return [p[0], p[1]]; });
      hubBoundaryLayer = L.polygon(latLngs, { pane: 'hubBoundaryPane', color: '#b71c1c', weight: 4, opacity: 1.0, dashArray: null, lineCap: 'round', lineJoin: 'round', fillColor: '#e53935', fillOpacity: 0.04, interactive: false }).addTo(map);
      hubBoundaryLayer.bindTooltip('<span style="font-size:.75rem;font-weight:600;color:#b71c1c">📍 ' + esc(facilityName) + ' — Service Area</span>', { sticky: false, direction: 'top', opacity: 0.95 });
    }
    if (originalCoords && originalCoords.length) {
      var origLatLngs = originalCoords.map(function(p) { return [p[0], p[1]]; });
      hubOriginalBoundaryLayer = L.polygon(origLatLngs, { pane: 'hubBoundaryPane', color: '#00838f', weight: 2, opacity: 0.75, dashArray: '6, 8', lineCap: 'round', lineJoin: 'round', fillColor: '#00bcd4', fillOpacity: 0.04, interactive: false }).addTo(map);
      hubOriginalBoundaryLayer.bindTooltip('<span style="font-size:.75rem;color:#00838f;font-weight:600">🗺 ' + esc(facilityName) + ' — Original Boundary</span>', { sticky: false, direction: 'top', opacity: 0.95 });
    }
    updateBoundaryLegend(hubName, facilityName, true);
    if (hubBoundaryLayer) { try { map.fitBounds(hubBoundaryLayer.getBounds().pad(0.05)); } catch(e) {} }
  }

  function updateBoundaryLegend(hubName, facilityName, visible) {
    var existing = document.getElementById('legend-boundary-item');
    if (!visible) { if (existing) existing.remove(); return; }
    var legendItems = document.getElementById('legend-items');
    if (!legendItems) return;
    var item: any = existing || document.createElement('div');
    item.id = 'legend-boundary-item';
    item.className = 'legend-item';
    item.style.cssText = 'margin-top:5px;padding-top:5px;border-top:1px solid #e9ecef;cursor:pointer;flex-direction:column;align-items:flex-start;gap:2px';
    item.title = 'Click to toggle hub boundary';
    var matchNote = (facilityName && facilityName !== hubName) ? '<div style="font-size:.67rem;color:#78909c;margin-top:1px">matched: ' + esc(facilityName) + '</div>' : '';
    item.innerHTML =
      '<div style="display:flex;align-items:center;gap:5px">' +
        '<div style="width:22px;height:0;border-top:4px solid #b71c1c;flex-shrink:0"></div>' +
        '<span style="color:#b71c1c;font-weight:600;font-size:.73rem">' + esc(hubName) + ' boundary</span>' +
      '</div>' +
      (hubOriginalBoundaryLayer ? '<div style="display:flex;align-items:center;gap:5px;margin-top:2px"><div style="width:22px;height:0;border-top:2px dashed #00838f;flex-shrink:0"></div><span style="color:#00838f;font-size:.7rem">original boundary</span></div>' : '') +
      matchNote;
    item.onclick = function() {
      var anyVisible = (hubBoundaryLayer && map.hasLayer(hubBoundaryLayer)) || (hubOriginalBoundaryLayer && map.hasLayer(hubOriginalBoundaryLayer));
      if (anyVisible) {
        if (hubBoundaryLayer) map.removeLayer(hubBoundaryLayer);
        if (hubOriginalBoundaryLayer) map.removeLayer(hubOriginalBoundaryLayer);
        item.style.opacity = '0.4'; item.title = 'Click to show hub boundary';
      } else {
        if (hubBoundaryLayer) hubBoundaryLayer.addTo(map);
        if (hubOriginalBoundaryLayer) hubOriginalBoundaryLayer.addTo(map);
        item.style.opacity = '1'; item.title = 'Click to hide hub boundary';
      }
    };
    if (!existing) legendItems.appendChild(item);
  }

  function initUpload() {
    var dz = document.getElementById('drop-zone');
    var fi = document.getElementById('csv-file-input') as HTMLInputElement;
    var ub = document.getElementById('upload-btn');
    if (!dz || !fi || !ub) return;
    dz.addEventListener('click', function() { fi.value = ''; fi.click(); });
    dz.addEventListener('dragover', function(e) { e.preventDefault(); dz!.classList.add('drag-over'); });
    dz.addEventListener('dragleave', function() { dz!.classList.remove('drag-over'); });
    dz.addEventListener('drop', function(e) {
      e.preventDefault(); dz!.classList.remove('drag-over');
      var f = (e as DragEvent).dataTransfer!.files[0]; if (f) handleFileSelected(f);
    });
    fi.addEventListener('change', function() { if (fi.files && fi.files[0]) handleFileSelected(fi.files[0]); });
    ub.addEventListener('click', function() { doUpload(); });
  }

  function handleFileSelected(f) {
    selectedFile = null;
    var info = document.getElementById('file-info') as HTMLElement;
    var btn = document.getElementById('upload-btn') as HTMLButtonElement;
    var res = document.getElementById('upload-result') as HTMLElement;
    res.innerHTML = ''; info.style.display = 'none'; btn.style.display = 'none';
    var ext = f.name.split('.').pop().toLowerCase();
    if (['csv','txt','xlsx','xls'].indexOf(ext) === -1) {
      info.innerHTML = '<span style="color:#c62828">Invalid type: .' + esc(ext) + '. Allowed: .csv .xlsx .xls</span>';
      info.style.display = 'block'; return;
    }
    if (f.size > 20 * 1024 * 1024) {
      info.innerHTML = '<span style="color:#c62828">File too large (' + (f.size/1024/1024).toFixed(1) + ' MB). Max 20 MB.</span>';
      info.style.display = 'block'; return;
    }
    selectedFile = f;
    info.innerHTML = 'File: <strong>' + esc(f.name) + '</strong> (' + (f.size/1024/1024).toFixed(2) + ' MB) — Ready';
    info.style.display = 'block';
    btn.style.display = 'block'; btn.disabled = false; btn.textContent = 'Upload';
  }

  function doUpload() {
    if (!selectedFile) return;
    var btn = document.getElementById('upload-btn') as HTMLButtonElement;
    var res = document.getElementById('upload-result') as HTMLElement;
    btn.disabled = true; btn.innerHTML = '<span class="spinner"></span> Uploading...';
    res.innerHTML = '<div class="result-card info">Parsing file, please wait...</div>';
    var fd = new FormData(); fd.append('file', selectedFile);
    fetch('/api/csv/upload', { method: 'POST', body: fd })
      .then(function(r) { return r.json().then(function(d) { return { ok: r.ok, status: r.status, data: d }; }); })
      .then(function(r) {
        btn.disabled = false; btn.textContent = 'Re-upload';
        if (!r.ok) { res.innerHTML = '<div class="result-card error">Error: ' + esc((r.data && r.data.error) ? r.data.error : 'Upload failed (HTTP ' + r.status + ')') + '</div>'; return; }
        (window as any).renderUploadResult(r.data);
      })
      .catch(function(e) { btn.disabled = false; btn.textContent = 'Retry'; res.innerHTML = '<div class="result-card error">Network error: ' + esc(e.message) + '</div>'; });
  }

  function renderUploadResult(data) {
    var res = document.getElementById('upload-result') as HTMLElement;
    var h = '<div class="result-card success"><div style="font-weight:700;margin-bottom:6px">File uploaded successfully</div>';
    h += '<div class="stat-row">';
    h += statBox(data.totalRows,'Total Rows') + statBox(data.validCount,'Valid') + statBox(data.skippedCount,'Skipped') + statBox(data.outOfRangeCount,'Out-of-Range');
    h += '</div>';
    if (data.datesFound && data.datesFound.length) {
      h += '<div style="font-size:.76rem;font-weight:600;margin-bottom:3px;margin-top:3px">Dates found:</div><div class="date-chips">';
      data.datesFound.forEach(function(d) { h += '<button class="date-chip" onclick="selectDate(\'' + esc(d) + '\')">' + esc(d) + '</button>'; });
      h += '</div>';
    }
    if (data.warnings && data.warnings.length) {
      h += '<div class="warn-section"><div class="collapsible-header" onclick="toggleCollapsible(this)">' + data.warnings.length + ' warning(s) <span class="arrow">v</span></div><div class="collapsible-body">';
      data.warnings.forEach(function(w) { h += '<div class="warn-item">' + esc(w) + '</div>'; });
      h += '</div></div>';
    }
    if (data.errors && data.errors.length) {
      h += '<div class="err-section"><div class="collapsible-header" onclick="toggleCollapsible(this)">' + data.errors.length + ' row error(s) <span class="arrow">v</span></div><div class="collapsible-body">';
      data.errors.forEach(function(e) { h += '<div class="err-item">' + esc(e) + '</div>'; });
      h += '</div></div>';
    }
    h += '</div>';
    res.innerHTML = h;
    populateDateSelector(data.datesFound || []);
    if (data.primaryDate) selectDate(data.primaryDate);
    var hubNameForBoundary = data.hubName || lastHubName;
    if (hubNameForBoundary) { lastHubName = hubNameForBoundary; fetchHubBoundary(hubNameForBoundary); }
  }

  function statBox(v, l) { return '<div class="stat"><div class="val">' + v + '</div><div class="lbl">' + l + '</div></div>'; }
  function sb(v, l) { return '<div class="stat-box"><div class="val">' + v + '</div><div class="lbl">' + l + '</div></div>'; }
  function toggleCollapsible(h) { h.classList.toggle('open'); h.nextElementSibling.classList.toggle('open'); }

  function populateDateSelector(dates) {
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    if (!sel) return;
    var cur = sel.value;
    sel.innerHTML = '<option value="">-- Select date --</option>';
    dates.forEach(function(d) { var o = document.createElement('option'); o.value = d; o.textContent = d; if (d === cur) o.selected = true; sel.appendChild(o); });
  }

  function selectDate(date) {
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    if (!sel) return;
    var found = false;
    for (var i = 0; i < sel.options.length; i++) { if (sel.options[i].value === date) { found = true; break; } }
    if (!found) { var o = document.createElement('option'); o.value = date; o.textContent = date; sel.appendChild(o); }
    sel.value = date;
    onDateChange();
  }

  function onDateChange() {
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    var date = sel.value; if (!date) return;
    allocationFinalized = false;
    (document.getElementById('finalized-banner') as HTMLElement).style.display = 'none';
    (document.getElementById('undo-btn') as HTMLButtonElement).disabled = true;
    (document.getElementById('finalize-btn') as HTMLButtonElement).disabled = true;
    (document.getElementById('compare-btn') as HTMLButtonElement).disabled = true;
    hasOsmAllocation = false; hasGoogleAllocation = false;
    clearAllocationUI(); loadAttendance(date);
  }

  function loadAttendance(date) {
    var list = document.getElementById('attendance-list') as HTMLElement;
    list.innerHTML = '<div class="loading-text">Loading attendance...</div>';
    fetch('/api/attendance/' + encodeURIComponent(date))
      .then(function(r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function(data) { renderAttendance(data); showAffinityPanel(); })
      .catch(function(e) { list.innerHTML = '<div class="loading-text" style="color:#c62828">Failed: ' + esc(e.message) + '</div>'; });
  }

  function renderAttendance(attendees) {
    var list = document.getElementById('attendance-list') as HTMLElement;
    presentSrNames = attendees.filter(function(a) { return a.present; }).map(function(a) { return a.srName; });
    updatePresentBadge(attendees);
    if (!attendees.length) { list.innerHTML = '<div class="empty-text">No SRs registered</div>'; return; }
    var zoneOptions = '<option value="">— No Zone —</option>';
    for (var i = 1; i <= plannedRegionCount; i++) {
      var rName = 'Region ' + i;
      var hasRegion = customRegionMap[rName] && customRegionMap[rName].latlngs && customRegionMap[rName].latlngs.length >= 3;
      if (hasRegion) { zoneOptions += '<option value="' + esc(rName) + '">' + esc(rName) + '</option>'; }
    }
    var hasZones = Object.keys(customRegionMap).some(function(k) { return customRegionMap[k] && customRegionMap[k].latlngs && customRegionMap[k].latlngs.length >= 3; });
    var h = '';
    var isTimeBased = getAllocationMode() === 'time-based';
    attendees.forEach(function(a) {
      var shiftVal = srShiftDurations[a.srName] || 600;
      h += '<div class="sr-attendance-item" style="flex-wrap:wrap;gap:4px">';
      h += '<span style="min-width:60px;font-weight:600">' + esc(a.srName) + '</span>';
      if (hasZones) {
        h += '<select data-sr-zone="' + esc(a.srName) + '" onchange="updateSrZone(this)" style="flex:1;min-width:90px;padding:2px 4px;border:1px solid #ced4da;border-radius:4px;font-size:.72rem;color:#343a40">';
        h += zoneOptions;
        h += '</select>';
      }
      h += '<input type="number" class="sr-shift-input" data-sr-shift="' + esc(a.srName) + '" min="120" max="720" step="30" value="' + shiftVal + '" style="width:55px;padding:2px 4px;border:1px solid #ced4da;border-radius:4px;font-size:.72rem;text-align:center' + (isTimeBased ? '' : ';display:none') + '" title="Shift duration (min)"/>';
      h += '<label class="toggle-switch" style="flex-shrink:0"><input type="checkbox" ' + (a.present ? 'checked' : '') + ' ' + (allocationFinalized ? 'disabled' : '') + ' data-sr="' + esc(a.srName) + '" onchange="toggleAttendance(this)"><span class="toggle-slider"></span></label>';
      h += '</div>';
    });
    list.innerHTML = h;
    if (hasZones) {
      document.querySelectorAll('[data-sr-zone]').forEach(function(sel: any) {
        var sr = sel.getAttribute('data-sr-zone');
        if (srZoneMap[sr]) sel.value = srZoneMap[sr];
      });
      var saveRow = document.getElementById('sr-zone-save-row');
      if (saveRow) (saveRow as HTMLElement).style.display = 'block';
    }
  }

  function updatePresentBadge(attendees) {
    var n = attendees.filter(function(a) { return a.present; }).length;
    var b = document.getElementById('present-count-badge'); if (b) b.textContent = n + ' / ' + attendees.length + ' present';
  }

  function toggleAttendance(cb) {
    var srName = cb.getAttribute('data-sr'), present = cb.checked;
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    var date = sel.value;
    fetch('/api/attendance/' + encodeURIComponent(date) + '/' + encodeURIComponent(srName) + '?present=' + present, { method: 'PUT' })
      .then(function() {
        if (present) { if (presentSrNames.indexOf(srName) === -1) presentSrNames.push(srName); }
        else { presentSrNames = presentSrNames.filter(function(n) { return n !== srName; }); }
        var c = document.querySelectorAll('#attendance-list input:checked').length;
        var t = document.querySelectorAll('#attendance-list input').length;
        (document.getElementById('present-count-badge') as HTMLElement).textContent = c + ' / ' + t + ' present';
      })
      .catch(function(e) { showAllocError('Attendance update failed: ' + e.message); });
  }

  function markAllPresent() {
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    var date = sel.value; if (!date) { showAllocError('Select a date first.'); return; }
    var cbs = document.querySelectorAll('#attendance-list input[type="checkbox"]'); var ps: any[] = [];
    cbs.forEach(function(cb: any) { if (!cb.checked) { cb.checked = true; ps.push(fetch('/api/attendance/' + encodeURIComponent(date) + '/' + encodeURIComponent(cb.getAttribute('data-sr')) + '?present=true', { method: 'PUT' })); } });
    Promise.all(ps).then(function() { loadAttendance(date); });
  }

  function markNonePresent() {
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    var date = sel.value; if (!date) { showAllocError('Select a date first.'); return; }
    var cbs = document.querySelectorAll('#attendance-list input[type="checkbox"]'); var ps: any[] = [];
    cbs.forEach(function(cb: any) { if (cb.checked) { cb.checked = false; ps.push(fetch('/api/attendance/' + encodeURIComponent(date) + '/' + encodeURIComponent(cb.getAttribute('data-sr')) + '?present=false', { method: 'PUT' })); } });
    Promise.all(ps).then(function() { loadAttendance(date); });
  }

  function loadSrShiftDurations() {
    fetch('/api/attendance/sr-shift-durations').then(function(r) { return r.json(); }).then(function(data) {
      srShiftDurations = data || {};
      document.querySelectorAll('[data-sr-shift]').forEach(function(inp: any) {
        var sr = inp.getAttribute('data-sr-shift'); inp.value = srShiftDurations[sr] || 600;
      });
    }).catch(function() {});
  }

  function saveSrShiftDurations() {
    var inputs = document.querySelectorAll('[data-sr-shift]');
    var durations: any = {};
    inputs.forEach(function(inp: any) {
      var sr = inp.getAttribute('data-sr-shift');
      var val = parseInt(inp.value, 10);
      if (!isNaN(val) && val >= 120 && val <= 720) durations[sr] = val;
    });
    fetch('/api/attendance/sr-shift-durations', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(durations) })
      .then(function(r) { if (!r.ok) return r.json().then(function(d) { throw new Error(d.error || 'Save failed'); }); return r.json(); })
      .then(function(saved) { srShiftDurations = saved; var btn = document.getElementById('save-shift-durations-btn'); if (btn) { btn.textContent = '✅ Saved!'; setTimeout(function() { btn!.textContent = '💾 Save Shift Durations'; }, 2000); } })
      .catch(function(e) { showAllocError('Failed to save shift durations: ' + e.message); });
  }

  function addNewSr() {
    var input = document.getElementById('new-sr-input') as HTMLInputElement;
    var name = input.value.trim();
    if (!name) { showAllocError('Enter an SR name.'); return; }
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    var date = sel.value; if (!date) { showAllocError('Select a date first.'); return; }
    fetch('/api/attendance/sr', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ srName: name }) })
      .then(function(r) { return r.json().then(function(d) { return { ok: r.ok, data: d }; }); })
      .then(function(r) { if (!r.ok) { showAllocError(r.data.error || 'Failed to add SR'); return; } input.value = ''; loadAttendance(date); })
      .catch(function(e) { showAllocError('Add SR failed: ' + e.message); });
  }

  var allocLoadingStages = [
    'Filtering shipments by hub & pincode boundary', 'Sorting by priority + effective payout',
    'Clustering shipments to SR territories', 'Rebalancing earnings across SRs',
    'Co-locating Forward & Reverse shipments', 'Sequencing routes (nearest-neighbour + 2-opt)',
    'Fetching road geometry from routing provider'
  ];
  var allocLoadingTimer: any = null;

  function showAllocLoading(modeLabel, opts?) {
    var ov = document.getElementById('alloc-loading-overlay'); if (!ov) return;
    opts = opts || {};
    var title = opts.title || ('Running allocation (' + modeLabel + ')…');
    var subtitle = opts.subtitle || 'Optimising routes and balancing earnings across SRs. This may take a few seconds for large datasets.';
    (document.getElementById('alloc-loading-title') as HTMLElement).textContent = title;
    (document.getElementById('alloc-loading-sub') as HTMLElement).textContent = subtitle;
    var stages = (opts.stages && opts.stages.length) ? opts.stages : allocLoadingStages;
    var stageEl = document.getElementById('alloc-loading-stage') as HTMLElement;
    var i = 0; stageEl.textContent = stages[0];
    if (allocLoadingTimer) clearInterval(allocLoadingTimer);
    allocLoadingTimer = setInterval(function() { i = (i + 1) % stages.length; stageEl.textContent = stages[i]; }, 1400);
    ov.classList.add('show');
  }

  function hideAllocLoading() {
    var ov = document.getElementById('alloc-loading-overlay'); if (!ov) return;
    ov.classList.remove('show');
    if (allocLoadingTimer) { clearInterval(allocLoadingTimer); allocLoadingTimer = null; }
  }

  function runAllocation(mode) {
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    var date = sel.value;
    if (!date) { showAllocError('Please select a date first.'); return; }
    if (mode === 'google' && !googleMapsConfigured) { showAllocError('Google Maps API key not configured. Add google.maps.api.key to application.properties.'); return; }
    var btnId = mode === 'google' ? 'run-google-btn' : 'run-osm-btn';
    var btn = document.getElementById(btnId) as HTMLButtonElement;
    var label = mode === 'google' ? 'Google Maps' : 'OSM';
    btn.disabled = true; btn.innerHTML = '<span class="spinner"></span> Running (' + label + ')...';
    hideAllocError(); showAllocLoading(label);
    clearPolylinesByMode(mode);
    fetch('/api/allocate', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ date: date, allocationMode: getAllocationMode() }) })
      .then(function(r) { return r.json().then(function(d) { return { ok: r.ok, status: r.status, data: d }; }); })
      .then(function(res) {
        btn.disabled = false; btn.innerHTML = (mode === 'google' ? '🗺 Run Allocation (Google Maps)' : '🗺 Run Allocation (OSM)');
        if (!res.ok) {
          hideAllocLoading();
          var detail = (res.data && (res.data.message || res.data.error || res.data.detail)) || '';
          var fallback = (getAllocationMode() === 'time-based') ? 'Allocation failed. Please check your affinity configuration.' : 'Allocation failed.';
          showAllocError((detail || fallback) + ' (HTTP ' + res.status + ')');
          return;
        }
        lastAllocationMode = mode;
        if (mode === 'osm') hasOsmAllocation = true; else hasGoogleAllocation = true;
        allocationFinalized = false;
        (document.getElementById('undo-btn') as HTMLButtonElement).disabled = false;
        (document.getElementById('finalize-btn') as HTMLButtonElement).disabled = false;
        (document.getElementById('finalized-banner') as HTMLElement).style.display = 'none';
        if (hasOsmAllocation && hasGoogleAllocation) (document.getElementById('compare-btn') as HTMLButtonElement).disabled = false;
        (document.getElementById('summary-mode-badge') as HTMLElement).textContent = label;
        (window as any).renderSummary(res.data);
        renderSrList(res.data.srSummaries);
        renderRegionHealth(res.data);
        lmShowPanel(res.data.srSummaries);
        var stageEl = document.getElementById('alloc-loading-stage'); if (stageEl) stageEl.textContent = 'Fetching road geometry for each SR…';
        renderAllSrsOnMap(res.data.srSummaries, date, mode, function() { hideAllocLoading(); });
        showAllocInfo('Allocation complete using ' + label + '. ' + (mode === 'osm' && googleMapsConfigured ? 'You can also run Google Maps allocation to compare.' : ''));
      })
      .catch(function(e) {
        btn.disabled = false; btn.innerHTML = (mode === 'google' ? '🗺 Run Allocation (Google Maps)' : '🗺 Run Allocation (OSM)');
        hideAllocLoading(); showAllocError('Allocation request failed: ' + (e && e.message ? e.message : e));
      });
  }

  // Expose every callable function on window for legacy inline onclick handlers
  // and for the React layer to reach in.
  var w = window as any;
  w.toggleMapFullscreen = toggleMapFullscreen;
  w.toggleMapProvider = toggleMapProvider;
  w.togglePincodeBoundaries = togglePincodeBoundaries;
  w.retryBoundary = retryBoundary;
  w.selectDate = selectDate;
  w.toggleCollapsible = toggleCollapsible;
  w.toggleAttendance = toggleAttendance;
  w.markAllPresent = markAllPresent;
  w.markNonePresent = markNonePresent;
  w.saveSrShiftDurations = saveSrShiftDurations;
  w.addNewSr = addNewSr;
  w.runAllocation = runAllocation;
  w.renderUploadResult = renderUploadResult;

  // The remaining behaviour (allocation results, route panel, override modal,
  // affinity zones, LM integration, previous allocation, density/raw toggles)
  // is supplied by legacy-allocation-2.ts which boots off the same closure.
  (window as any).__legacyAllocCtx = {
    L, map: () => map, gmap: () => gmap, activeMapProvider: () => activeMapProvider,
    setActiveMapProvider: (v: string) => { activeMapProvider = v; },
    setGmap: (g: any) => { gmap = g; },
    srLayers, gmapMarkers, gmapPolylines, gmapCustomRegionPolygons,
    getGmapMarkerClusterer: () => gmapMarkerClusterer,
    setGmapMarkerClusterer: (v: any) => { gmapMarkerClusterer = v; },
    SR_COLORS, esc, showAllocError, showAllocInfo, hideAllocError,
    showAllocLoading, hideAllocLoading,
    statBox, sb, getHubLat: () => HUB_LAT, getHubLng: () => HUB_LNG,
    getAllocationFinalized: () => allocationFinalized,
    setAllocationFinalized: (v: boolean) => { allocationFinalized = v; },
    presentSrNames, getPendingReassign: () => pendingReassign, setPendingReassign: (v: any) => { pendingReassign = v; },
    getLastAllocationMode: () => lastAllocationMode, setLastAllocationMode: (v: any) => { lastAllocationMode = v; },
    getCurrentSrName: () => currentSrName, setCurrentSrName: (v: any) => { currentSrName = v; },
    getCurrentSrIndex: () => currentSrIndex, setCurrentSrIndex: (v: number) => { currentSrIndex = v; },
    getHubBoundaryLayer: () => hubBoundaryLayer,
    getLastHubName: () => lastHubName,
    loadAttendance,
    renderSrList: (s: any) => renderSrList(s),
    renderRegionHealth: (s: any) => renderRegionHealth(s),
    lmShowPanel: (s: any) => lmShowPanel(s),
    renderAllSrsOnMap: (s: any, d: string, m: any, cb?: any) => renderAllSrsOnMap(s, d, m, cb),
    clearMarkers, clearPolylinesByMode, clearAllocationUI,
    selectSr, openReassignModal, confirmOverride, undoOverride, finalizeAllocation, refreshAllocation,
    toggleRegionHealthPanel, autoReassignSrs,
    toggleDensityMarkers, toggleShipmentDisplay, toggleRawShipmentPlot,
    setAffinityMode, onRegionCountChange, applyRegionCount, renderDrawList,
    updateRegionSrCount, updateSrZone, showSrZoneSaved, startEditRegion, stopEditRegion,
    simplifyPolygon, startDrawingFor, stopDrawing, clearCustomRegion, validateCustomRegion,
    setDrawStatus, validateCustomRegionGmap, highlightPincodeOnMap, showAffinityPanel,
    loadAvailablePincodes, renderAffinityList, updateAffinityBadge, clearAllAffinities,
    saveRegionsToStorage, loadRegionsFromStorage,
    getAllocationMode, setAllocationMode, applyAllocationModeUI, updateShiftDurationLabel,
    runAffinityAllocation, loadPreviousAllocation, renderPreviousOnMap, selectPreviousSr,
    lmConnect, lmFetchDashboard, lmLoadDeliveryUsers, lmPushAll, lmConfirmAll,
    toggleTimeline, renderTimeline, focusTimelineStop, renderRouteDetails,
    stitchPolyline, addMarkersForSr, addPolylineForSr, buildPopup, compareRoutes,
    customRegionMap, customRegionLayers,
    plannedRegionCount, regionSrCounts, srZoneMap, srShiftDurations, srAffinityMap,
    REGION_COLORS, getPlannedRegionCount: () => plannedRegionCount, setPlannedRegionCount: (v: number) => { plannedRegionCount = v; },
    lmSrMappings, lmLastSrSummaries, lmDeliveryUsers, lmConnected: () => lmConnected,
  };

  // ── Variables that are mutated below ──
  var customRegionMap: any = {};
  var customRegionLayers: any = {};
  var affinityMode = 'draw';
  var activeDrawRegion: any = null;
  var drawingActive = false;
  var plannedRegionCount = 0;
  var regionSrCounts: any = {};
  var srZoneMap: any = {};
  var srShiftDurations: any = {};
  var REGION_COLORS = ['#e53935','#1e88e5','#43a047','#fb8c00','#8e24aa','#00acc1','#f4511e','#3949ab','#7cb342','#c0ca33','#6d4c41','#546e7a','#d81b60','#039be5','#00897b','#ffb300','#5e35b1','#1565c0','#2e7d32','#ef6c00'];
  var srAffinityMap: any = {};
  var availablePincodes: any[] = [];
  var pincodeHighlightLayer: any = null;
  var timelineCache: any = {};
  var shipmentDisplayAll = true;
  var lastRegionSuggestions: any[] = [];
  var densityMarkersVisible = true;
  var rawPlotActive = false;
  var rawPlotLayerGroup: any = null;
  var editingRegionName: any = null;
  var lmSrMappings: any = {};
  var lmLastSrSummaries: any[] = [];
  var lmDeliveryUsers: any[] = [];
  var lmConnected = false;

  // === Map rendering / route helpers ===
  function renderSrList(srSummaries) {
    var panel = document.getElementById('sr-list-panel'); if (panel) panel.classList.remove('hidden');
    var list = document.getElementById('sr-list') as HTMLElement;
    var legendItems = document.getElementById('legend-items') as HTMLElement;
    legendItems.innerHTML = '<div class="legend-item"><div class="legend-dot" style="background:#e53935"></div>Hub</div>';
    if (hasOsmAllocation || lastAllocationMode === 'osm') legendItems.innerHTML += '<div class="legend-item"><div class="legend-line" style="background:#555;height:3px;width:22px"></div><span style="color:#555">OSM route (solid)</span></div>';
    if (hasGoogleAllocation || lastAllocationMode === 'google') legendItems.innerHTML += '<div class="legend-item"><div style="width:22px;height:3px;background:repeating-linear-gradient(90deg,#555 0,#555 4px,transparent 4px,transparent 8px)"></div><span style="color:#555">Google Maps route (dashed, same color)</span></div>';
    var isTimeBased = srSummaries.length > 0 && srSummaries.some(function(sr) { return sr.shiftUtilisationPct != null || sr.affinityStatus != null; });
    if (isTimeBased) {
      var activeSrs = srSummaries.filter(function(sr) { return (sr.shiftUtilisationPct || 0) > 0; }).length;
      var zeroSrs = srSummaries.length - activeSrs;
      var summaryDiv = document.getElementById('time-based-sr-summary');
      if (!summaryDiv) {
        summaryDiv = document.createElement('div');
        summaryDiv.id = 'time-based-sr-summary';
        (summaryDiv as HTMLElement).style.cssText = 'margin-bottom:8px;padding:7px 10px;background:#e8f5e9;border:1px solid #a5d6a7;border-radius:6px;font-size:.76rem;color:#1b5e20;font-weight:600';
        list.parentNode!.insertBefore(summaryDiv, list);
      }
      summaryDiv.innerHTML = '⏱ Time-Based: <strong>' + activeSrs + '</strong> active SR' + (activeSrs !== 1 ? 's' : '') + ' (utilisation &gt; 0%) &nbsp;·&nbsp; <strong>' + zeroSrs + '</strong> zero-utilisation';
      (summaryDiv as HTMLElement).style.display = 'block';
    } else {
      var existing = document.getElementById('time-based-sr-summary'); if (existing) (existing as HTMLElement).style.display = 'none';
    }
    var h = '';
    srSummaries.forEach(function(sr, i) {
      var color = SR_COLORS[i % SR_COLORS.length];
      legendItems.innerHTML += '<div class="legend-item"><div class="legend-dot" style="background:' + color + '"></div>' + esc(sr.srName) + ' (' + sr.shipmentCount + ')</div>';
      var dist = sr.estimatedDistanceKm ? sr.estimatedDistanceKm.toFixed(1) + ' km' : '';
      var earningsSub = '';
      if (sr.netEarnings !== undefined && sr.netEarnings !== 0) earningsSub = ' &nbsp;|&nbsp; Net: <strong style="color:#1b5e20">₹' + sr.netEarnings.toFixed(2) + '</strong>';
      var utilisationHtml = '';
      if (isTimeBased && sr.shiftUtilisationPct != null) {
        var pct = Math.min(100, Math.max(0, sr.shiftUtilisationPct));
        var barClass = pct >= 90 ? 'high' : (pct >= 60 ? 'mid' : 'low');
        utilisationHtml = '<div style="display:flex;align-items:center;gap:5px;margin-top:3px"><div class="util-bar-wrap"><div class="util-bar ' + barClass + '" style="width:' + pct.toFixed(1) + '%"></div></div><span style="font-size:.68rem;color:#495057;white-space:nowrap;min-width:36px">' + pct.toFixed(1) + '%</span></div>';
      }
      var affinityBadgeHtml = '';
      if (isTimeBased && sr.affinityStatus != null) {
        var isAssigned = sr.affinityStatus === 'AFFINITY_ASSIGNED';
        affinityBadgeHtml = '<span class="affinity-badge ' + (isAssigned ? 'assigned' : 'non-affinity') + '">' + (isAssigned ? '✅ Affinity' : '➖ Non-Affinity') + '</span>';
      }
      var srId = 'sr-item-' + i, tlId = 'sr-tl-' + i;
      h += '<div id="' + srId + '" style="margin-bottom:0">';
      h += '<div class="sr-item" data-sr="' + esc(sr.srName) + '" data-idx="' + i + '" style="border-radius:7px 7px 0 0;margin-bottom:0" onclick="selectSr(\'' + esc(sr.srName) + '\',' + i + ')">';
      h += '<div class="sr-dot" style="background:' + color + '"></div>';
      h += '<div class="sr-item-info">';
      h += '<div class="sr-item-name" style="display:flex;align-items:center;gap:5px;flex-wrap:wrap">' + esc(sr.srName) + (srAffinityMap[sr.srName] && srAffinityMap[sr.srName].length ? ' <span style="font-size:.65rem;color:#8e24aa;font-weight:400">(' + srAffinityMap[sr.srName].join(',') + ')</span>' : '') + (affinityBadgeHtml ? ' ' + affinityBadgeHtml : '') + '</div>';
      h += '<div class="sr-item-sub">' + (dist ? dist : '') + earningsSub + '</div>';
      h += utilisationHtml;
      h += '</div>';
      h += '<div class="sr-item-badge">' + sr.shipmentCount + '</div>';
      h += '<button class="tl-expand-btn" id="tl-btn-' + i + '" onclick="event.stopPropagation();toggleTimeline(\'' + esc(sr.srName) + '\',' + i + ')" title="View delivery timeline">⏱ Timeline</button>';
      h += '</div>';
      h += '<div class="sr-timeline" id="' + tlId + '"><div class="tl-loading">Click ⏱ Timeline to load route schedule</div></div>';
      h += '</div>';
    });
    list.innerHTML = h;
    if (hubBoundaryLayer && lastHubName) updateBoundaryLegend(lastHubName, lastHubName, true);
  }

  function renderAllSrsOnMap(srSummaries, date, mode, onComplete?) {
    if (!hasOsmAllocation || !hasGoogleAllocation) clearMarkers();
    srSummaries.forEach(function(sr, idx) {
      if (sr.territoryBoundary && sr.territoryBoundary.length >= 3) {
        var color = SR_COLORS[idx % SR_COLORS.length];
        var latlngs = sr.territoryBoundary.map(function(p) { return [p[0], p[1]]; });
        var polygon = L.polygon(latlngs, { color: color, weight: 1, opacity: 0.5, fillColor: color, fillOpacity: 0.08, interactive: false }).addTo(map);
        if (!srLayers[sr.srName]) srLayers[sr.srName] = { markers: [], osmPolyline: null, googlePolyline: null, territoryPolygon: polygon, color: color, index: idx, srData: sr };
        else { if (srLayers[sr.srName].territoryPolygon) map.removeLayer(srLayers[sr.srName].territoryPolygon); srLayers[sr.srName].territoryPolygon = polygon; }
      }
    });
    var i = 0;
    function next() {
      if (i >= srSummaries.length) {
        if (activeMapProvider === 'google') syncToGoogleMap();
        if (typeof onComplete === 'function') { try { onComplete(); } catch (e) {} }
        return;
      }
      var sr = srSummaries[i], color = SR_COLORS[i % SR_COLORS.length], idx = i; i++;
      fetch('/api/allocate/' + encodeURIComponent(date) + '/sr/' + encodeURIComponent(sr.srName))
        .then(function(r) { return r.ok ? r.json() : null; })
        .then(function(route) {
          if (!route) { next(); return; }
          if (!srLayers[sr.srName]) srLayers[sr.srName] = { markers: [], osmPolyline: null, googlePolyline: null, color: color, index: idx, srData: sr };
          else srLayers[sr.srName].srData = sr;
          if (!srLayers[sr.srName].markers.length) addMarkersForSr(sr.srName, route, color, idx, date);
          var polylineEndpoint = (mode === 'osm') ? 'ors' : mode;
          var polylineUrl = '/api/allocate/' + encodeURIComponent(date) + '/sr/' + encodeURIComponent(sr.srName) + '/polyline/' + polylineEndpoint;
          fetch(polylineUrl).then(function(r) { return r.ok ? r.json() : null; })
            .then(function(poly) { addPolylineForSr(sr.srName, poly, color, mode); next(); })
            .catch(function() { addPolylineForSr(sr.srName, null, color, mode); next(); });
        }).catch(function() { next(); });
    }
    next();
  }

  function addMarkersForSr(srName, route, color, index, date) {
    if (!route || !route.stops || !route.stops.length) return;
    if (!srLayers[srName]) srLayers[srName] = { markers: [], osmPolyline: null, googlePolyline: null, color: color, index: index };
    var stops = route.stops.slice().sort(function(a, b) { return a.sequence - b.sequence; });
    var coordCount: any = {};
    var JITTER_DEG = 0.00006;
    stops.forEach(function(stop) {
      var key = stop.latitude.toFixed(6) + ',' + stop.longitude.toFixed(6);
      var n = coordCount[key] || 0; coordCount[key] = n + 1;
      var lat = stop.latitude, lng = stop.longitude;
      if (n > 0) {
        var angle = (n - 1) * (2 * Math.PI / 8);
        var radius = JITTER_DEG * (1 + Math.floor((n - 1) / 8));
        lat = stop.latitude + radius * Math.cos(angle);
        lng = stop.longitude + radius * Math.sin(angle);
      }
      var isOvr = stop.isOverride === true, isOor = stop.outOfRange === true;
      var icon = L.divIcon({ className: '', html: '<div style="width:13px;height:13px;background:' + color + ';' + (isOvr ? 'border:2px dashed #fff' : 'border:2px solid #fff') + ';' + (isOor ? 'outline:2px solid #e53935;outline-offset:1px;' : '') + 'border-radius:50%;box-shadow:0 1px 3px rgba(0,0,0,.35)"></div>', iconSize: [13,13], iconAnchor: [6,6] });
      var marker = L.marker([lat, lng], { icon: icon, title: srName + ' #' + stop.sequence, origLat: stop.latitude, origLng: stop.longitude });
      marker.bindPopup(buildPopup(srName, stop, color, date), { maxWidth: 230 });
      marker.addTo(map);
      srLayers[srName].markers.push(marker);
    });
  }

  function _haversineM(lat1, lng1, lat2, lng2) {
    var R = 6371000;
    var toRad = function(d) { return d * Math.PI / 180; };
    var dLat = toRad(lat2 - lat1), dLng = toRad(lng2 - lng1);
    var a = Math.sin(dLat/2)*Math.sin(dLat/2) + Math.cos(toRad(lat1))*Math.cos(toRad(lat2))*Math.sin(dLng/2)*Math.sin(dLng/2);
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  function stitchPolyline(rawCoords, hubLat, hubLng, maxGapM) {
    if (!rawCoords || !rawCoords.length) return [];
    maxGapM = maxGapM || 250;
    var clean: any[] = [];
    for (var i = 0; i < rawCoords.length; i++) {
      var p = rawCoords[i]; if (!p || p.length < 2) continue;
      var lat = +p[0], lng = +p[1];
      if (!isFinite(lat) || !isFinite(lng)) continue;
      if (Math.abs(lat) < 0.0001 && Math.abs(lng) < 0.0001) continue;
      if (clean.length) { var last = clean[clean.length - 1]; if (_haversineM(last[0], last[1], lat, lng) < 0.5) continue; }
      clean.push([lat, lng]);
    }
    if (clean.length < 2) { if (clean.length === 1 && hubLat != null && hubLng != null) return [[hubLat, hubLng], clean[0], [hubLat, hubLng]]; return clean; }
    if (hubLat != null && hubLng != null) {
      var firstD = _haversineM(clean[0][0], clean[0][1], hubLat, hubLng);
      var lastD = _haversineM(clean[clean.length - 1][0], clean[clean.length - 1][1], hubLat, hubLng);
      if (firstD < 150) clean[0] = [hubLat, hubLng]; else clean.unshift([hubLat, hubLng]);
      if (lastD < 150) clean[clean.length - 1] = [hubLat, hubLng]; else clean.push([hubLat, hubLng]);
    }
    var stitched: any[] = [clean[0]];
    for (var j = 1; j < clean.length; j++) {
      var a = stitched[stitched.length - 1], b = clean[j], d = _haversineM(a[0], a[1], b[0], b[1]);
      if (d > maxGapM) {
        var steps = Math.ceil(d / maxGapM);
        for (var k = 1; k < steps; k++) {
          var t = k / steps;
          stitched.push([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]);
        }
      }
      stitched.push(b);
    }
    return stitched;
  }

  function addPolylineForSr(srName, polyCoords, color, mode) {
    if (!srLayers[srName]) srLayers[srName] = { markers: [], osmPolyline: null, googlePolyline: null, color: color, index: 0 };
    if (mode === 'osm' && srLayers[srName].osmPolyline) { map.removeLayer(srLayers[srName].osmPolyline); srLayers[srName].osmPolyline = null; }
    if (mode === 'google' && srLayers[srName].googlePolyline) { map.removeLayer(srLayers[srName].googlePolyline); srLayers[srName].googlePolyline = null; }
    var osmOpts = { color: color, weight: 4, opacity: 0.9, smoothFactor: 1.2, lineCap: 'round', lineJoin: 'round' };
    var googleOpts = { color: color, weight: 4, opacity: 0.9, dashArray: '10,6', smoothFactor: 1.2, lineCap: 'round', lineJoin: 'round' };
    var fallbackOsmOpts = { color: color, weight: 3, opacity: 0.65, dashArray: '4,3', lineCap: 'round', lineJoin: 'round' };
    var fallbackGoogleOpts = { color: color, weight: 3, opacity: 0.65, dashArray: '10,6', lineCap: 'round', lineJoin: 'round' };
    var polyline;
    if (polyCoords && polyCoords.length > 1) {
      var clean = stitchPolyline(polyCoords, HUB_LAT, HUB_LNG, 250);
      if (clean.length > 1) { var opts = mode === 'google' ? googleOpts : osmOpts; polyline = L.polyline(clean, opts).addTo(map); }
    }
    if (!polyline) {
      var stops = srLayers[srName].markers.map(function(m) { return [m.options.origLat != null ? m.options.origLat : m.getLatLng().lat, m.options.origLng != null ? m.options.origLng : m.getLatLng().lng]; });
      if (stops.length) {
        var rawPath = [[HUB_LAT, HUB_LNG]].concat(stops).concat([[HUB_LAT, HUB_LNG]]);
        var stitched = stitchPolyline(rawPath, HUB_LAT, HUB_LNG, 800);
        var fbOpts = mode === 'google' ? fallbackGoogleOpts : fallbackOsmOpts;
        polyline = L.polyline(stitched, fbOpts).addTo(map);
      }
    }
    if (polyline) { if (mode === 'osm') srLayers[srName].osmPolyline = polyline; else srLayers[srName].googlePolyline = polyline; }
  }

  function buildPopup(srName, stop, color, date) {
    var oor = stop.outOfRange ? '<div class="popup-oor">Out-of-range</div>' : '';
    var reassign = !allocationFinalized ? '<button class="popup-reassign" onclick="openReassignModal(\'' + esc(stop.shippingId) + '\',\'' + esc(srName) + '\')">Reassign</button>' : '';
    return '<div><div class="popup-title" style="color:' + color + '">' + esc(srName) + ' - Stop #' + stop.sequence + '</div>' + oor +
      '<div class="popup-row"><span class="popup-label">ID</span><span class="popup-val">' + esc(stop.shippingId) + '</span></div>' +
      '<div class="popup-row"><span class="popup-label">Pincode</span><span class="popup-val">' + esc(stop.dropPincode) + '</span></div>' +
      '<div class="popup-row"><span class="popup-label">Lat,Lng</span><span class="popup-val">' + (stop.latitude||0).toFixed(5) + ',' + (stop.longitude||0).toFixed(5) + '</span></div>' +
      '<div class="popup-row"><span class="popup-label">Type</span><span class="popup-val">' + esc(stop.orderType) + '</span></div>' +
      '<div class="popup-row"><span class="popup-label">Weight</span><span class="popup-val">' + stop.phyWeight + ' kg' + (stop.isHeavy ? ' (Heavy)' : '') + '</span></div>' +
      '<div class="popup-row"><span class="popup-label">Flow</span><span class="popup-val">' + esc(stop.shipmentFlow) + '</span></div>' +
      (stop.isOverride ? '<div class="popup-row"><span class="popup-label">Override</span><span class="popup-val" style="color:#e65100">Yes</span></div>' : '') +
      reassign + '</div>';
  }

  function compareRoutes() {
    if (!hasOsmAllocation || !hasGoogleAllocation) { showAllocError('Run both OSM and Google Maps allocation first.'); return; }
    (document.getElementById('compare-banner') as HTMLElement).style.display = 'block';
    Object.keys(srLayers).forEach(function(srName) { var layer = srLayers[srName]; if (layer.osmPolyline) layer.osmPolyline.addTo(map); if (layer.googlePolyline) layer.googlePolyline.addTo(map); });
    showAllocInfo('Comparison mode: solid lines = OSM, dashed green lines = Google Maps');
  }

  function toggleTimeline(srName, index) {
    var tlEl = document.getElementById('sr-tl-' + index);
    var btnEl = document.getElementById('tl-btn-' + index);
    if (!tlEl) return;
    var isOpen = tlEl.classList.contains('open');
    if (isOpen) { tlEl.classList.remove('open'); if (btnEl) { btnEl.classList.remove('open'); btnEl.textContent = '⏱ Timeline'; } return; }
    tlEl.classList.add('open'); if (btnEl) { btnEl.classList.add('open'); btnEl.textContent = '▲ Timeline'; }
    if (timelineCache[srName]) { renderTimeline(tlEl, timelineCache[srName], srName); return; }
    tlEl.innerHTML = '<div class="tl-loading"><span class="spinner"></span> Calculating route timeline…</div>';
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    var date = sel.value; if (!date) { tlEl.innerHTML = '<div class="tl-loading" style="color:#c62828">Select a date first.</div>'; return; }
    fetch('/api/allocate/' + encodeURIComponent(date) + '/sr/' + encodeURIComponent(srName) + '/timeline')
      .then(function(r) { return r.ok ? r.json() : r.json().then(function(e) { throw new Error(e.message || 'Failed'); }); })
      .then(function(data) { timelineCache[srName] = data; renderTimeline(tlEl, data, srName); })
      .catch(function(e) { tlEl!.innerHTML = '<div class="tl-loading" style="color:#c62828">⚠️ ' + esc(e.message) + '</div>'; });
  }

  function renderTimeline(container, data, srName) {
    if (!data || !data.stops || data.stops.length === 0) { container.innerHTML = '<div class="tl-loading">No stops assigned to this SR.</div>'; return; }
    var totalH = Math.floor(data.totalDurationMinutes / 60);
    var totalM = Math.round(data.totalDurationMinutes % 60);
    var durationStr = totalH > 0 ? totalH + 'h ' + totalM + 'm' : totalM + 'm';
    var h = '';
    h += '<div class="sr-timeline-header"><span>🕐 ' + esc(srName) + ' — Delivery Timeline</span><span style="font-weight:400;font-size:.7rem">' + esc(data.startTime) + ' → ' + esc(data.endTime) + '</span></div>';
    h += '<div class="sr-timeline-stats">';
    h += '<div class="sr-tl-stat"><div class="v">' + durationStr + '</div><div class="l">Total Duration</div></div>';
    h += '<div class="sr-tl-stat"><div class="v">' + Math.round(data.travelMinutes) + 'm</div><div class="l">Travel Time</div></div>';
    h += '<div class="sr-tl-stat"><div class="v">' + Math.round(data.deliveryMinutes) + 'm</div><div class="l">Delivery Time</div></div>';
    h += '<div class="sr-tl-stat"><div class="v">' + data.stopCount + '</div><div class="l">Stops</div></div>';
    h += '</div>';
    h += '<div class="sr-tl-stops">';
    h += '<div class="tl-row"><div class="tl-spine"><div class="tl-dot" style="color:#e53935;background:#e53935"></div><div class="tl-line"></div></div><div class="tl-content"><div class="tl-time">' + esc(data.startTime) + '</div><div class="tl-label" style="font-weight:600;color:#e53935">🏢 Hub — Departure</div></div></div>';
    var breakAfter = data.breakAfterStop || Math.floor(data.stops.length / 2);
    data.stops.forEach(function(stop, idx) {
      var travelRounded = Math.round(stop.travelFromPrevMin);
      var distKm = stop.distFromPrevKm || 0;
      var distStr = distKm >= 0.1 ? distKm.toFixed(2) + ' km' : (distKm * 1000).toFixed(0) + ' m';
      var isHeavy = stop.isHeavy;
      var travelColor = travelRounded === 0 ? '#ff6f00' : '#1565c0';
      var travelBg = travelRounded === 0 ? '#fff3e0' : '#e3f2fd';
      h += '<div class="tl-row"><div class="tl-spine"><div class="tl-line" style="min-height:18px"></div></div><div class="tl-content" style="padding-bottom:2px"><span class="tl-travel-badge" style="background:' + travelBg + ';color:' + travelColor + '">🚗 ' + travelRounded + ' min &nbsp;·&nbsp; 📍 ' + distStr + (travelRounded === 0 && distKm < 0.1 ? ' <span title="Very close stops — same cluster">⚠️</span>' : '') + '</span></div></div>';
      h += '<div class="tl-row" style="cursor:pointer" onclick="focusTimelineStop(' + stop.lat + ',' + stop.lng + ',\'' + esc(stop.shippingId) + '\',\'' + esc(srName) + '\')" title="Click to focus on map"><div class="tl-spine"><div class="tl-dot" style="color:#1976d2;background:#1976d2"></div><div class="tl-line"></div></div><div class="tl-content"><div class="tl-time">' + esc(stop.arrivalTime) + ' → ' + esc(stop.departureTime) + '</div><div class="tl-label">Stop #' + stop.sequence + ' &nbsp;·&nbsp; ' + esc(stop.pincode || '') + (isHeavy ? ' <span style="color:#6a1b9a;font-size:.65rem">⬛ Heavy</span>' : '') + ' <span style="font-size:.65rem;color:#1976d2">🗺 view</span></div><div class="tl-meta">📦 ' + esc(stop.shippingId || '') + ' &nbsp;·&nbsp; ' + stop.deliveryMin + ' min handling</div></div></div>';
      if (idx + 1 === breakAfter) h += '<div class="tl-break-row">☕ 30-min break buffer</div>';
    });
    var retDist = data.returnToHub.distKm || 0;
    var retDistStr = retDist >= 0.1 ? retDist.toFixed(2) + ' km' : (retDist * 1000).toFixed(0) + ' m';
    var retMin = Math.round(data.returnToHub.travelMin);
    h += '<div class="tl-row"><div class="tl-spine"><div class="tl-line" style="min-height:18px"></div></div><div class="tl-content" style="padding-bottom:2px"><span class="tl-travel-badge">🚗 ' + retMin + ' min &nbsp;·&nbsp; 📍 ' + retDistStr + '</span></div></div>';
    h += '<div class="tl-row"><div class="tl-spine"><div class="tl-dot" style="color:#e53935;background:#e53935"></div></div><div class="tl-content"><div class="tl-time">' + esc(data.returnToHub.arrivalTime) + '</div><div class="tl-label" style="font-weight:600;color:#e53935">🏢 Hub — Return</div></div></div>';
    h += '</div>';
    container.innerHTML = h;
  }

  function focusTimelineStop(lat, lng, shippingId, srName) {
    if (activeMapProvider === 'google') { if (gmap) { gmap.panTo({ lat: lat, lng: lng }); gmap.setZoom(17); } return; }
    map.setView([lat, lng], 17, { animate: true });
    var layer = srLayers[srName];
    if (layer && layer.markers) {
      var matched: any = null;
      for (var i = 0; i < layer.markers.length; i++) {
        var m = layer.markers[i];
        var oLat = m.options.origLat !== undefined ? m.options.origLat : m.getLatLng().lat;
        var oLng = m.options.origLng !== undefined ? m.options.origLng : m.getLatLng().lng;
        if (Math.abs(oLat - lat) < 0.0001 && Math.abs(oLng - lng) < 0.0001) {
          if (!matched || m.options.title.indexOf(shippingId) >= 0) matched = m;
          if (m.options.title.indexOf(shippingId) >= 0) break;
        }
      }
      if (matched) {
        map.setView(matched.getLatLng(), 17, { animate: true });
        matched.openPopup();
        var el = matched.getElement();
        if (el) { el.style.transition = 'transform 0.2s'; el.style.transform = 'scale(2.2)'; setTimeout(function(e) { e.style.transform = 'scale(1)'; }.bind(null, el), 450); }
      }
    }
  }

  function selectSr(srName, index) {
    currentSrName = srName; currentSrIndex = index;
    document.querySelectorAll('.sr-item').forEach(function(el) { el.classList.toggle('active', el.getAttribute('data-sr') === srName); });
    var layer = srLayers[srName];
    if (layer && layer.markers.length) map.fitBounds(L.featureGroup(layer.markers).getBounds().pad(0.15));
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    var date = sel.value;
    (document.getElementById('route-panel') as HTMLElement).classList.remove('hidden');
    (document.getElementById('route-panel-title') as HTMLElement).textContent = 'Route: ' + srName;
    (document.getElementById('route-stats') as HTMLElement).innerHTML = '<div class="loading-text">Loading...</div>';
    (document.getElementById('stop-list') as HTMLElement).innerHTML = '';
    fetch('/api/allocate/' + encodeURIComponent(date) + '/sr/' + encodeURIComponent(srName))
      .then(function(r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function(route) { renderRouteDetails(route, SR_COLORS[index % SR_COLORS.length]); })
      .catch(function(e) { (document.getElementById('route-stats') as HTMLElement).innerHTML = '<div class="loading-text" style="color:#c62828">Error: ' + esc(e.message) + '</div>'; });
  }

  function renderRouteDetails(route, color) {
    var stops = (route.stops || []).slice().sort(function(a,b) { return a.sequence - b.sequence; });
    var pins: any = {}, ovr = 0;
    stops.forEach(function(s) { if (s.dropPincode) pins[s.dropPincode] = 1; if (s.isOverride) ovr++; });
    var earningsHtml = '';
    if (currentSrName) {
      var layer = srLayers[currentSrName];
      if (layer && layer.srData) {
        var d = layer.srData;
        if (d.netEarnings !== undefined && d.netEarnings !== 0) {
          earningsHtml = '<div class="stat-box" style="background:#e8f5e9;border-color:#a5d6a7"><div class="val" style="color:#1b5e20;font-size:.95rem">₹' + d.grossPayout.toFixed(2) + '</div><div class="lbl">Gross Payout</div></div><div class="stat-box" style="background:#ffebee;border-color:#ef9a9a"><div class="val" style="color:#b71c1c;font-size:.95rem">₹' + d.fuelCost.toFixed(2) + '</div><div class="lbl">Fuel Cost</div></div><div class="stat-box" style="background:#e3f2fd;border-color:#90caf9"><div class="val" style="color:#0d47a1;font-size:.95rem">₹' + d.netEarnings.toFixed(2) + '</div><div class="lbl">Net Earnings</div></div>';
        }
      }
    }
    (document.getElementById('route-stats') as HTMLElement).innerHTML = sb(route.totalShipments,'Stops') + sb(route.estimatedDistanceKm ? route.estimatedDistanceKm.toFixed(1) : '-','km') + sb(Object.keys(pins).length,'Pincodes') + sb(ovr,'Overrides') + earningsHtml;
    var h = '';
    stops.forEach(function(stop) {
      var tags = '';
      if (stop.isOverride) tags += '<span class="stop-tag override">Override</span>';
      if (stop.outOfRange) tags += '<span class="stop-tag oor">Out-of-range</span>';
      if (stop.isHeavy) tags += '<span class="stop-tag heavy">Heavy</span>';
      tags += '<span class="stop-tag">' + esc(stop.shipmentFlow) + '</span>';
      tags += '<span class="stop-tag">' + stop.phyWeight + 'kg</span>';
      h += '<div class="stop-item" onclick="map.setView([' + stop.latitude + ',' + stop.longitude + '],17)">';
      h += '<div class="stop-seq" style="background:' + color + '">' + stop.sequence + '</div>';
      h += '<div class="stop-info"><div class="stop-pincode">' + esc(stop.dropPincode) + '</div>';
      h += '<div class="stop-id">' + esc(stop.shippingId) + '</div>';
      h += '<div class="stop-meta">' + tags + '</div></div></div>';
    });
    (document.getElementById('stop-list') as HTMLElement).innerHTML = h;
  }

  function openReassignModal(shippingId, currentSr) {
    if (allocationFinalized) return;
    pendingReassign = { shippingId: shippingId, currentSr: currentSr };
    (document.getElementById('modal-info') as HTMLElement).innerHTML = 'Shipment: <strong>' + esc(shippingId) + '</strong><br>Current SR: <strong>' + esc(currentSr) + '</strong>';
    var radioList = document.getElementById('sr-radio-list') as HTMLElement;
    var targets = presentSrNames.filter(function(n) { return n !== currentSr; });
    if (!targets.length) radioList.innerHTML = '<div class="empty-text">No other present SRs available</div>';
    else radioList.innerHTML = targets.map(function(sr) { return '<label class="sr-radio-item"><input type="radio" name="targetSr" value="' + esc(sr) + '"> ' + esc(sr) + '</label>'; }).join('');
    (document.getElementById('override-modal') as HTMLDialogElement).showModal();
  }

  function confirmOverride() {
    var selected = document.querySelector('input[name="targetSr"]:checked') as HTMLInputElement;
    if (!selected) { alert('Please select a target SR.'); return; }
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    var date = sel.value;
    (document.getElementById('override-modal') as HTMLDialogElement).close();
    showAllocLoading('Reassign', { title: 'Reassigning shipment…', subtitle: 'Moving the shipment to the selected SR and re-sequencing the route.', stages: ['Updating assignment', 'Re-sequencing route', 'Recomputing earnings', 'Refreshing summary'] });
    fetch('/api/allocate/' + encodeURIComponent(date) + '/override', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ shippingId: pendingReassign.shippingId, targetSrName: selected.value }) })
      .then(function(r) { return r.json().then(function(d) { return { ok: r.ok, data: d }; }); })
      .then(function(res) { if (!res.ok) { hideAllocLoading(); showAllocError(res.data.message || 'Override failed'); return; } refreshAllocation(true); })
      .catch(function(e) { hideAllocLoading(); showAllocError('Override error: ' + e.message); });
  }

  function undoOverride() {
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    var date = sel.value;
    showAllocLoading('Undo', { title: 'Undoing last override…', subtitle: 'Reverting the most recent reassignment.', stages: ['Reverting assignment', 'Re-sequencing route', 'Refreshing summary'] });
    fetch('/api/allocate/' + encodeURIComponent(date) + '/override', { method: 'DELETE' })
      .then(function(r) { if (!r.ok) return r.json().then(function(d) { throw new Error(d.message || 'Undo failed'); }); refreshAllocation(true); })
      .catch(function(e) { hideAllocLoading(); showAllocError('Undo error: ' + e.message); });
  }

  function finalizeAllocation() {
    if (!confirm('Finalize allocation? No further overrides will be allowed.')) return;
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    var date = sel.value;
    showAllocLoading('Finalize', { title: 'Finalizing allocation…', subtitle: 'Locking the allocation. No further overrides will be accepted.', stages: ['Validating allocation', 'Persisting final state', 'Locking overrides'] });
    fetch('/api/allocate/' + encodeURIComponent(date) + '/finalize', { method: 'POST' })
      .then(function(r) {
        if (!r.ok) return r.json().then(function(d) { throw new Error(d.message || 'Finalize failed'); });
        allocationFinalized = true;
        (document.getElementById('finalized-banner') as HTMLElement).style.display = 'block';
        (document.getElementById('undo-btn') as HTMLButtonElement).disabled = true;
        (document.getElementById('finalize-btn') as HTMLButtonElement).disabled = true;
        document.querySelectorAll('#attendance-list input').forEach(function(el: any) { el.disabled = true; });
        refreshAllocation(true);
      })
      .catch(function(e) { hideAllocLoading(); showAllocError('Finalize error: ' + e.message); });
  }

  function refreshAllocation(keepLoader) {
    var sel = document.getElementById('date-select') as HTMLSelectElement;
    var date = sel.value;
    var ownsLoader = !keepLoader;
    if (ownsLoader) showAllocLoading('Refresh', { title: 'Refreshing allocation…', subtitle: 'Reloading the latest allocation snapshot.', stages: ['Fetching summary', 'Rebuilding SR list', 'Redrawing routes'] });
    fetch('/api/allocate/' + encodeURIComponent(date) + '/summary')
      .then(function(r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function(summary) {
        clearAllocationUI();
        (window as any).renderSummary(summary);
        renderSrList(summary.srSummaries);
        lmShowPanel(summary.srSummaries);
        if (lastAllocationMode) renderAllSrsOnMap(summary.srSummaries, date, lastAllocationMode, function() { hideAllocLoading(); });
        else hideAllocLoading();
        if (currentSrName) { var idx = summary.srSummaries.findIndex(function(s) { return s.srName === currentSrName; }); if (idx >= 0) selectSr(currentSrName, idx); }
      })
      .catch(function(e) { hideAllocLoading(); console.warn('Refresh error', e); });
  }

  function clearMarkers() {
    Object.keys(srLayers).forEach(function(srName) {
      var layer = srLayers[srName];
      if (layer.markers) layer.markers.forEach(function(m) { map.removeLayer(m); });
      if (layer.osmPolyline) map.removeLayer(layer.osmPolyline);
      if (layer.googlePolyline) map.removeLayer(layer.googlePolyline);
      if (layer.territoryPolygon) map.removeLayer(layer.territoryPolygon);
    });
    srLayers = {};
  }

  function clearPolylinesByMode(mode) {
    Object.keys(srLayers).forEach(function(srName) {
      var layer = srLayers[srName];
      if (mode === 'osm' && layer.osmPolyline) { map.removeLayer(layer.osmPolyline); layer.osmPolyline = null; }
      if (mode === 'google' && layer.googlePolyline) { map.removeLayer(layer.googlePolyline); layer.googlePolyline = null; }
    });
  }

  function clearAllocationUI() {
    clearMarkers();
    timelineCache = {};
    document.getElementById('summary-panel')?.classList.add('hidden');
    document.getElementById('sr-list-panel')?.classList.add('hidden');
    document.getElementById('route-panel')?.classList.add('hidden');
    document.getElementById('lm-panel')?.classList.add('hidden');
    document.getElementById('region-health-panel')?.classList.add('hidden');
    var cb = document.getElementById('compare-banner'); if (cb) (cb as HTMLElement).style.display = 'none';
    var li = document.getElementById('legend-items'); if (li) li.innerHTML = '<div class="legend-item"><div class="legend-dot" style="background:#e53935"></div>Hub</div>';
    if (hubBoundaryLayer && lastHubName) updateBoundaryLegend(lastHubName, lastHubName, true);
  }

  // ── Render summary, region health, etc ──
  // Implemented in legacy-allocation-2.ts which is bootstrapped below via dynamic import.

  // ── Boot the second half synchronously by inlining the remaining functions ──
  // (rest follows in legacy-allocation-rest.ts to keep this file readable)

  // ===== Render summary (priority + earnings fairness) =====
  function renderSummary(s) {
    document.getElementById('summary-panel')?.classList.remove('hidden');
    var us = s.unallocatedShipments || 0;
    if (s.shiftDurationMinutes != null) updateShiftDurationLabel(s.shiftDurationMinutes);
    var coreHtml = sb(s.totalShipments, 'Total') + sb(s.allocatedShipments != null ? s.allocatedShipments : s.totalShipments, 'Allocated') +
      '<div class="stat-box" style="' + (us > 0 ? 'border-color:#ef9a9a' : '') + '"><div class="val" style="' + (us > 0 ? 'color:#c62828' : '') + '">' + us + '</div><div class="lbl">Unallocated</div></div>' +
      sb(s.totalSrs, 'SRs Active') + sb(s.capacityRangeMin != null && s.capacityRangeMax != null ? s.capacityRangeMin + '–' + s.capacityRangeMax : (s.capacityRangeMax || '-'), 'Cap/SR') +
      sb(s.avgShipmentsPerSr ? s.avgShipmentsPerSr.toFixed(1) : '-', 'Avg/SR');
    var earningsHtml = '';
    var nets = (s.srSummaries || []).filter(function(sr) { return sr && sr.netEarnings !== undefined && sr.netEarnings !== null && sr.netEarnings > 0; }).map(function(sr) { return { name: sr.srName, net: sr.netEarnings }; });
    if (nets.length > 0) {
      var idleSrCount = (s.srSummaries ? s.srSummaries.length : 0) - nets.length;
      nets.sort(function(a, b) { return b.net - a.net; });
      var highest = nets[0], lowest = nets[nets.length - 1];
      var mean = nets.reduce(function(acc, x) { return acc + x.net; }, 0) / nets.length;
      var range = highest.net - lowest.net;
      var FAIRNESS_BAND_PCT = 0.20, threshold = mean * FAIRNESS_BAND_PCT;
      var srsWithinBand = nets.filter(function(sr) { return Math.abs(sr.net - mean) <= threshold; }).length;
      var percentageWithinBand = (srsWithinBand / nets.length) * 100;
      var idleNote = idleSrCount > 0 ? ' &nbsp;|&nbsp; <span style="color:#757575">Excludes ' + idleSrCount + ' idle SR' + (idleSrCount === 1 ? '' : 's') + ' (0 earnings)</span>' : '';
      earningsHtml = '<div style="margin-top:10px;border-top:1px solid #e9ecef;padding-top:10px"><div style="font-size:.78rem;font-weight:700;color:#343a40;margin-bottom:7px">💰 Earnings Fairness (Net = Payout − Fuel)</div><div class="summary-stats" style="grid-template-columns:1fr 1fr 1fr 1fr">' +
        earningsBox('⬆ ' + esc(highest.name), '₹' + highest.net.toFixed(2), 'Highest Earner', '#1b5e20', '#e8f5e9', '#a5d6a7') +
        earningsBox('⬇ ' + esc(lowest.name), '₹' + lowest.net.toFixed(2), 'Lowest Earner', '#b71c1c', '#ffebee', '#ef9a9a') +
        earningsBox('≈ Mean', '₹' + mean.toFixed(2), 'Avg Net Earnings', '#0d47a1', '#e3f2fd', '#90caf9') +
        earningsBox('📊 ' + percentageWithinBand.toFixed(1) + '%', srsWithinBand + ' of ' + nets.length + ' SRs', 'Within 20% of Avg', '#6a1b9a', '#f3e5f5', '#ce93d8') +
        '</div><div style="margin-top:7px;background:#fff8e1;border:1px solid #ffe082;border-radius:7px;padding:8px 10px;font-size:.78rem"><span style="font-weight:700;color:#e65100">Earnings Range: ₹' + range.toFixed(2) + '</span> &nbsp;|&nbsp; Spread between highest and lowest earning SR' + idleNote + '</div>' +
        buildRebalancingSuggestionsHtml(s.srSummaries, mean, FAIRNESS_BAND_PCT) + '</div>';
    }
    var priorityHtml = '';
    if (s.priorityCounts) {
      var pc = s.priorityCounts;
      priorityHtml = '<div style="margin-top:10px;border-top:1px solid #e9ecef;padding-top:10px"><div style="font-size:.78rem;font-weight:700;color:#343a40;margin-bottom:7px">🏷 Priority Breakdown (P0 > P1 > P2)</div><div class="summary-stats" style="grid-template-columns:1fr 1fr 1fr">' +
        priorityBox('P0', pc.p0Total, pc.p0Allocated, pc.p0Unallocated, '#b71c1c', '#ffebee', '#ef9a9a') +
        priorityBox('P1', pc.p1Total, pc.p1Allocated, pc.p1Unallocated, '#ef6c00', '#fff3e0', '#ffcc80') +
        priorityBox('P2', pc.p2Total, pc.p2Allocated, pc.p2Unallocated, '#1565c0', '#e3f2fd', '#90caf9') +
        '</div><div style="margin-top:7px;background:#e8f5e9;border:1px solid #a5d6a7;border-radius:7px;padding:8px 10px;font-size:.78rem;color:#1b5e20">✅ Engine retains the <b>maximum P0 shipments</b> first (P0 > P1 > P2), then highest effective payout, when capacity is exceeded.</div></div>';
    }
    (document.getElementById('summary-stats') as HTMLElement).innerHTML = coreHtml;
    var panel = document.getElementById('summary-panel') as HTMLElement;
    var existing = panel.querySelector('.earnings-section'); if (existing) existing.remove();
    if (earningsHtml) { var div = document.createElement('div'); div.className = 'earnings-section'; div.innerHTML = earningsHtml; panel.appendChild(div); }
    var existingPriority = panel.querySelector('.priority-section'); if (existingPriority) existingPriority.remove();
    if (priorityHtml) { var pdiv = document.createElement('div'); pdiv.className = 'priority-section'; pdiv.innerHTML = priorityHtml; panel.appendChild(pdiv); }
  }

  function priorityBox(tier, total, allocated, unallocated, textColor, bgColor, borderColor) {
    var unalSty = unallocated > 0 ? 'color:#c62828;font-weight:700' : 'color:' + textColor + ';font-weight:600';
    return '<div class="stat-box" style="background:' + bgColor + ';border-color:' + borderColor + ';text-align:left;padding:7px 9px"><div style="font-size:.78rem;font-weight:700;color:' + textColor + ';margin-bottom:3px">' + tier + '</div><div style="font-size:.7rem;color:#555;display:flex;justify-content:space-between"><span>Total</span><b style="color:' + textColor + '">' + total + '</b></div><div style="font-size:.7rem;color:#555;display:flex;justify-content:space-between"><span>Allocated</span><b style="color:#1b5e20">' + allocated + '</b></div><div style="font-size:.7rem;color:#555;display:flex;justify-content:space-between"><span>Unallocated</span><b style="' + unalSty + '">' + unallocated + '</b></div></div>';
  }

  function earningsBox(title, value, label, textColor, bgColor, borderColor) {
    return '<div class="stat-box" style="background:' + bgColor + ';border-color:' + borderColor + '"><div style="font-size:.68rem;font-weight:600;color:' + textColor + ';margin-bottom:2px">' + title + '</div><div class="val" style="color:' + textColor + ';font-size:1rem">' + value + '</div><div class="lbl">' + label + '</div></div>';
  }

  function buildRebalancingSuggestionsHtml(srSummaries, mean, bandPct) {
    if (!srSummaries || !srSummaries.length || !mean || mean <= 0) return '';
    var lower = mean * (1 - bandPct), upper = mean * (1 + bandPct);
    var working: any[] = srSummaries.filter(function(sr) { return sr && sr.netEarnings > 0; }).map(function(sr) { var count = sr.shipmentCount || 0; var avgPayout = count > 0 ? (sr.grossPayout || sr.netEarnings) / count : 0; return { name: sr.srName, net: sr.netEarnings, count: count, avgPayout: Math.max(5, avgPayout) }; });
    if (working.length < 2) return '';
    var suggestions: any[] = []; var SUGGESTION_LIMIT = 8;
    while (suggestions.length < SUGGESTION_LIMIT) {
      working.sort(function(a, b) { return b.net - a.net; });
      var donor = working[0], receiver = working[working.length - 1];
      if (!donor || !receiver || donor.name === receiver.name) break;
      if (donor.net <= upper && receiver.net >= lower) break;
      var donorExcess = donor.net - mean, receiverDeficit = mean - receiver.net;
      var deltaRupees = Math.max(0, Math.min(donorExcess, receiverDeficit));
      if (deltaRupees < donor.avgPayout * 0.5) break;
      var shipments = Math.max(1, Math.round(deltaRupees / donor.avgPayout));
      var moneyMoved = shipments * donor.avgPayout;
      suggestions.push({ from: donor.name, to: receiver.name, shipments: shipments, amount: moneyMoved, fromNet: donor.net, toNet: receiver.net });
      donor.net -= moneyMoved; receiver.net += moneyMoved;
      donor.count = Math.max(0, donor.count - shipments); receiver.count = receiver.count + shipments;
    }
    if (suggestions.length === 0) return '<div class="rebalance-suggestions" style="margin-top:10px;border-top:1px solid #e9ecef;padding-top:10px"><div style="font-size:.78rem;font-weight:700;color:#1b5e20;margin-bottom:6px">✅ Suggested Rebalancing</div><div style="background:#e8f5e9;border:1px solid #a5d6a7;border-radius:7px;padding:10px 12px;font-size:.78rem;color:#1b5e20">All earning SRs are already within ±' + Math.round(bandPct * 100) + '% of the mean. No transfers required.</div></div>';
    var rows = suggestions.map(function(sg, i) { return '<div class="rebal-row" style="display:flex;align-items:center;gap:8px;padding:6px 8px;border-bottom:1px solid #f0f0f0;font-size:.78rem"><div style="min-width:18px;height:18px;border-radius:9px;background:#1976d2;color:#fff;font-size:.65rem;font-weight:700;display:flex;align-items:center;justify-content:center">' + (i + 1) + '</div><div style="flex:1">Move <b>' + sg.shipments + '</b> shipment' + (sg.shipments === 1 ? '' : 's') + ' from <b style="color:#b71c1c">' + esc(sg.from) + '</b> → <b style="color:#1b5e20">' + esc(sg.to) + '</b> <span style="color:#757575">(~₹' + sg.amount.toFixed(0) + ')</span></div></div>'; }).join('');
    return '<div class="rebalance-suggestions" style="margin-top:10px;border-top:1px solid #e9ecef;padding-top:10px"><div style="font-size:.78rem;font-weight:700;color:#343a40;margin-bottom:6px">💡 Suggested Rebalancing (target: all SRs within ±' + Math.round(bandPct * 100) + '% of mean)</div><div style="background:#fff;border:1px solid #e9ecef;border-radius:7px;overflow:hidden">' + rows + '</div><div style="margin-top:6px;font-size:.72rem;color:#757575;line-height:1.4">Estimates use each donor SR\'s average payout per shipment. Apply them via the Reassign button on individual stops, or adjust SR attendance and re-run allocation.</div></div>';
  }

  w.renderSummary = renderSummary;
  w.selectSr = selectSr;
  w.openReassignModal = openReassignModal;
  w.toggleTimeline = toggleTimeline;
  w.focusTimelineStop = focusTimelineStop;

  // The remaining helpers (region health, density toggles, raw plot, affinity,
  // previous allocation, LM integration) live in the second half module:
  import('./legacy-allocation-rest').then((m) => {
    m.installRest((window as any).__legacyAllocCtx, {
      updateBoundaryLegend, renderSrList, renderRegionHealth: undefined,
      // injected references mutated below
    });
    // Boot the original window.load equivalent now that everything is wired up.
    initMap();
    initUpload();
    applyAllocationModeUI(getAllocationMode());
    loadSrShiftDurations();
    
    // ── Sidebar tab navigation ────────────────────────────────────────────────
    var sbTabUpload = document.getElementById('sb-tab-upload');
    var sbTabAllocate = document.getElementById('sb-tab-allocate');
    var sbTabMonitor = document.getElementById('sb-tab-monitor');
    if (sbTabUpload) sbTabUpload.addEventListener('click', function() { switchSidebarTab('upload'); });
    if (sbTabAllocate) sbTabAllocate.addEventListener('click', function() { switchSidebarTab('allocate'); });
    if (sbTabMonitor) sbTabMonitor.addEventListener('click', function() { switchSidebarTab('monitor'); });
    
    // ── Allocation buttons ─────────────────────────────────────────────────────
    var runOsm = document.getElementById('run-osm-btn'); if (runOsm) runOsm.addEventListener('click', function() { runAllocation('osm'); });
    var runG = document.getElementById('run-google-btn'); if (runG) runG.addEventListener('click', function() { runAllocation('google'); });
    var cmp = document.getElementById('compare-btn'); if (cmp) cmp.addEventListener('click', compareRoutes);
    var undo = document.getElementById('undo-btn'); if (undo) undo.addEventListener('click', undoOverride);
    var fin = document.getElementById('finalize-btn'); if (fin) fin.addEventListener('click', finalizeAllocation);
    var dateSel = document.getElementById('date-select'); if (dateSel) dateSel.addEventListener('change', onDateChange);
    
    // ── Previous allocation mode button ────────────────────────────────────────
    var prevBtn = document.getElementById('mode-previous-btn');
    if (prevBtn) prevBtn.addEventListener('click', function() { loadPreviousAllocation(); });
    
    // ── Attendance buttons ─────────────────────────────────────────────────────
    var markAllBtn = document.getElementById('mark-all-present-btn');
    var markNoneBtn = document.getElementById('mark-none-present-btn');
    var addSrBtn = document.getElementById('add-new-sr-btn');
    if (markAllBtn) markAllBtn.addEventListener('click', function() { markAllPresent(); });
    if (markNoneBtn) markNoneBtn.addEventListener('click', function() { markNonePresent(); });
    if (addSrBtn) addSrBtn.addEventListener('click', function() { addNewSr(); });
    
    // ── Affinity buttons ───────────────────────────────────────────────────────
    var modeCountBtn = document.getElementById('mode-count-btn');
    var modeTimeBtn = document.getElementById('mode-timebased-btn');
    var applyRegionBtn = document.getElementById('apply-region-count-btn');
    var runAffinityBtn = document.getElementById('run-affinity-allocation-btn');
    var saveRegionsBtn = document.getElementById('save-regions-btn');
    var clearAffinityBtn = document.getElementById('clear-affinity-btn');
    if (modeCountBtn) modeCountBtn.addEventListener('click', function() { setAllocationMode('count-based'); });
    if (modeTimeBtn) modeTimeBtn.addEventListener('click', function() { setAllocationMode('time-based'); });
    if (applyRegionBtn) applyRegionBtn.addEventListener('click', function() { applyRegionCount(); });
    if (runAffinityBtn) runAffinityBtn.addEventListener('click', function() { runAffinityAllocation(); });
    if (saveRegionsBtn) saveRegionsBtn.addEventListener('click', function() { saveRegionsToStorage(); });
    if (clearAffinityBtn) clearAffinityBtn.addEventListener('click', function() { clearAllAffinities(); });
    
    // ── LM Integration buttons ─────────────────────────────────────────────────
    var lmConnectBtn = document.getElementById('lm-connect-btn');
    var lmFetchBtn = document.getElementById('lm-fetch-dashboard-btn');
    var lmLoadUsersBtn = document.getElementById('lm-load-delivery-users-btn');
    var lmPushBtn = document.getElementById('lm-push-all-btn');
    var lmConfirmBtn = document.getElementById('lm-confirm-all-btn');
    if (lmConnectBtn) lmConnectBtn.addEventListener('click', function() { lmConnect(); });
    if (lmFetchBtn) lmFetchBtn.addEventListener('click', function() { lmFetchDashboard(); });
    if (lmLoadUsersBtn) lmLoadUsersBtn.addEventListener('click', function() { lmLoadDeliveryUsers(); });
    if (lmPushBtn) lmPushBtn.addEventListener('click', function() { lmPushAll(); });
    if (lmConfirmBtn) lmConfirmBtn.addEventListener('click', function() { lmConfirmAll(); });
    
    // ── Map control buttons ────────────────────────────────────────────────────
    var fullscreenBtn = document.getElementById('fullscreen-btn');
    var mapToggleBtn = document.getElementById('map-toggle-btn');
    var pincodeBtn = document.getElementById('pincode-boundary-btn');
    var densityBtn = document.getElementById('density-toggle-btn');
    var shipmentBtn = document.getElementById('shipment-toggle-btn');
    var rawPlotBtn = document.getElementById('raw-plot-btn');
    if (fullscreenBtn) fullscreenBtn.addEventListener('click', function() { toggleMapFullscreen(); });
    if (mapToggleBtn) mapToggleBtn.addEventListener('click', function() { toggleMapProvider(); });
    if (pincodeBtn) pincodeBtn.addEventListener('click', function() { togglePincodeBoundaries(); });
    if (densityBtn) densityBtn.addEventListener('click', function() { toggleDensityMarkers(); });
    if (shipmentBtn) shipmentBtn.addEventListener('click', function() { toggleShipmentDisplay(); });
    if (rawPlotBtn) rawPlotBtn.addEventListener('click', function() { toggleRawShipmentPlot(); });
    
    // ── Region health panel toggle ─────────────────────────────────────────────
    var regionHealthToggle = document.getElementById('region-health-toggle');
    if (regionHealthToggle) regionHealthToggle.addEventListener('click', function() { toggleRegionHealthPanel(); });
    
    // ── Map legend toggle ──────────────────────────────────────────────────────
    var mapLegend = document.getElementById('map-legend');
    if (mapLegend) {
      var legendH4 = mapLegend.querySelector('h4');
      if (legendH4) legendH4.addEventListener('click', function() { mapLegend.classList.toggle('collapsed'); });
    }
    
    // ── Modal handlers ─────────────────────────────────────────────────────────
    var mc = document.getElementById('modal-cancel-btn'); if (mc) mc.addEventListener('click', function() { (document.getElementById('override-modal') as HTMLDialogElement).close(); });
    var mcf = document.getElementById('modal-confirm-btn'); if (mcf) mcf.addEventListener('click', confirmOverride);
    
    // ── Fetch available dates ──────────────────────────────────────────────────
    fetch('/api/csv/dates').then(function(r) { return r.json(); }).then(function(dates) { if (dates.length) populateDateSelector(dates); }).catch(function() {});
  });

  // ══════════════════════════════════════════════════════════════════════════════
  // SIDEBAR TAB NAVIGATION
  // ══════════════════════════════════════════════════════════════════════════════
  
  /**
   * Switch the visible sidebar tab.
   * @param {'upload'|'allocate'|'monitor'} name
   */
  function switchSidebarTab(name: string) {
    var tabs = ['upload', 'allocate', 'monitor'];
    tabs.forEach(function(t) {
      var btn  = document.getElementById('sb-tab-' + t);
      var pane = document.getElementById('tab-pane-' + t);
      if (!btn || !pane) return;
      var active = (t === name);
      btn.classList.toggle('active', active);
      btn.setAttribute('aria-selected', active ? 'true' : 'false');
      pane.classList.toggle('active', active);
    });
  }
  w.switchSidebarTab = switchSidebarTab;

  /** Enable a tab button (clears its disabled state and marks it as available). */
  function unlockSidebarTab(name: string) {
    var btn = document.getElementById('sb-tab-' + name);
    if (!btn) return;
    (btn as HTMLButtonElement).disabled = false;
    btn.classList.add('has-progress');
    btn.removeAttribute('title');
  }
  w.unlockSidebarTab = unlockSidebarTab;

  // ══════════════════════════════════════════════════════════════════════════════
  // AUTO-ADVANCE WRAPPERS
  // ══════════════════════════════════════════════════════════════════════════════
  
  // Wrap renderUploadResult: on a successful upload, unlock Allocate and switch to it.
  (function() {
    var _origRender = renderUploadResult;
    renderUploadResult = function(data: any) {
      var result = _origRender.apply(this, arguments as any);
      try {
        // Treat any result that produced a primaryDate or non-zero validCount
        // as a successful upload.
        var ok = data && (data.primaryDate || (data.validCount && data.validCount > 0));
        if (ok) {
          unlockSidebarTab('allocate');
          switchSidebarTab('allocate');
        }
      } catch (e) { /* never break the underlying flow */ }
      return result;
    };
  })();

  // Wrap renderSummary: after a successful allocation, unlock Monitor and switch to it.
  (function() {
    var _origRender = renderSummary;
    renderSummary = function(summary: any) {
      var result = _origRender.apply(this, arguments as any);
      try {
        // Any summary with SR summaries is a successful allocation.
        if (summary && summary.srSummaries && summary.srSummaries.length > 0) {
          unlockSidebarTab('monitor');
          switchSidebarTab('monitor');
        }
      } catch (e) { /* never break */ }
      return result;
    };
  })();

  // Forward declared (filled in by rest module via __legacyAllocCtx)
  function renderRegionHealth(_summary) { /* injected */ }
  function autoReassignSrs() { /* injected */ }
  function toggleRegionHealthPanel() { /* injected */ }
  function toggleDensityMarkers() { /* injected */ }
  function toggleShipmentDisplay() { /* injected */ }
  function toggleRawShipmentPlot() { /* injected */ }
  function setAffinityMode(_m) { /* injected */ }
  function onRegionCountChange() { /* injected */ }
  function applyRegionCount() { /* injected */ }
  function renderDrawList() { /* injected */ }
  function updateRegionSrCount(_i) { /* injected */ }
  function updateSrZone(_s) { /* injected */ }
  function showSrZoneSaved() { /* injected */ }
  function startEditRegion(_n) { /* injected */ }
  function stopEditRegion(_n, _s) { /* injected */ }
  function simplifyPolygon(pts: any[], _t: number) { return pts; }
  function startDrawingFor(_n, _c) { /* injected */ }
  function stopDrawing() { /* injected */ }
  function clearCustomRegion(_n) { /* injected */ }
  function validateCustomRegion(_l): any { return { valid: true }; }
  function setDrawStatus(_h) { /* injected */ }
  function validateCustomRegionGmap(_l): any { return { valid: true }; }
  function highlightPincodeOnMap(_p) { /* injected */ }
  function showAffinityPanel() { /* injected */ }
  function loadAvailablePincodes() { /* injected */ }
  function renderAffinityList() { /* injected */ }
  function updateAffinityBadge() { /* injected */ }
  function clearAllAffinities() { /* injected */ }
  function saveRegionsToStorage() { /* injected */ }
  function loadRegionsFromStorage() { /* injected */ }
  function getAllocationMode() { return localStorage.getItem('allocationMode') || 'count-based'; }
  function setAllocationMode(mode) { localStorage.setItem('allocationMode', mode); applyAllocationModeUI(mode); }
  function applyAllocationModeUI(_m) { /* injected */ }
  function updateShiftDurationLabel(_m) { /* injected */ }
  function runAffinityAllocation() { /* injected */ }
  function loadPreviousAllocation() { /* injected */ }
  function renderPreviousOnMap(_s: any, _d: any, _c?: any) { /* injected */ }
  function selectPreviousSr(_n: any, _i: any) { /* injected */ }
  function lmShowPanel(_s) { /* injected */ }
  function lmConnect() { /* injected */ }
  function lmFetchDashboard() { /* injected */ }
  function lmLoadDeliveryUsers() { /* injected */ }
  function lmPushAll() { /* injected */ }
  function lmConfirmAll() { /* injected */ }
}
