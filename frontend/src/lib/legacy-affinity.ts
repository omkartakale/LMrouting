// @ts-nocheck
/* eslint-disable */
/**
 * Affinity Region Setup - Standalone page for drawing and managing delivery zones.
 * Ported from affinity-setup.html script (lines 137-423)
 */

export function bootAffinityApp() {
  const L = (window as any).L;
  const w = window as any;

  const HUB = { lat: 18.4600561, lng: 73.8884305, name: 'PNQ HDP' };
  const COLORS = ['#4CAF50','#FF9800','#00BCD4','#607D8B','#E91E63','#9C27B0','#2196F3','#f44336'];
  const SR_LIST = Array.from({length:15},(_,i)=>`SR-${String(i+1).padStart(3,'0')}`);

  let regions: any[] = [];
  let regionLayers: any = {};
  let pendingLayer: any = null;
  let editingRegionId: string | null = null;
  let drawControl: any = null;
  let editedColor: string | null = null;
  let zoneCounter = 0;

  function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
      const r = Math.random()*16|0, v = c==='x'?r:(r&0x3|0x8);
      return v.toString(16);
    });
  }

  function escHtml(s: string) {
    return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  function setStatus(msg: string) {
    const el = document.getElementById('status');
    if (el) el.textContent = msg;
  }

  function showToast(msg: string) {
    const toast = document.getElementById('toast');
    if (!toast) return;
    toast.textContent = msg;
    toast.classList.add('show');
    setTimeout(() => toast.classList.remove('show'), 3000);
  }

  // Initialize map
  const map = L.map('map').setView([HUB.lat, HUB.lng], 12);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '© OpenStreetMap contributors', maxZoom: 19
  }).addTo(map);

  // Hub marker
  const hubIcon = L.divIcon({
    html: '<div style="width:16px;height:16px;background:#f44336;border-radius:50%;border:3px solid white;box-shadow:0 0 4px rgba(0,0,0,0.5)"></div>',
    iconSize: [16,16], iconAnchor: [8,8], className: ''
  });
  L.marker([HUB.lat, HUB.lng], {icon: hubIcon}).addTo(map)
    .bindTooltip(HUB.name, {permanent: true, direction: 'right', offset: [10,0]});

  // Drawing controls
  const drawnItems = new L.FeatureGroup().addTo(map);
  drawControl = new L.Control.Draw({
    draw: {
      polygon: { shapeOptions: { color: '#2196F3', fillOpacity: 0.3 }, allowIntersection: false },
      polyline: false, rectangle: false, circle: false, circlemarker: false, marker: false
    },
    edit: { featureGroup: drawnItems, remove: false }
  });
  map.addControl(drawControl);

  // Draw event handlers
  map.on(L.Draw.Event.CREATED, function(e: any) {
    pendingLayer = e.layer;
    zoneCounter++;
    const input = document.getElementById('region-name-input') as HTMLInputElement;
    if (input) {
      input.value = 'Zone ' + zoneCounter;
      input.focus();
      input.select();
    }
    const modal = document.getElementById('name-modal');
    if (modal) modal.style.display = 'block';
  });

  map.on(L.Draw.Event.EDITED, function(e: any) {
    e.layers.eachLayer(function(layer: any) {
      const id = layer._regionId;
      if (id) {
        const region = regions.find(r => r.id === id);
        if (region) {
          region.polygon = layer.getLatLngs()[0].map((ll: any) => [ll.lat, ll.lng]);
        }
      }
    });
    setStatus('Edits saved. Click "Save All" to persist.');
  });

  function addRegionToMap(region: any) {
    const layer = L.polygon(region.polygon, {
      color: region.color,
      fillColor: region.color,
      fillOpacity: 0.25,
      weight: 2
    });
    layer._regionId = region.id;
    layer.addTo(drawnItems);
    regionLayers[region.id] = layer;
    layer.on('click', function() { showRegionPopup(region.id, layer); });
  }

  function showRegionPopup(regionId: string, layer: any) {
    const region = regions.find(r => r.id === regionId);
    if (!region) return;
    const chips = region.assignedSRs.map((sr: string) => `<span class="sr-chip">${sr}</span>`).join('');
    const content = `
      <div class="popup-header">${escHtml(region.name)}</div>
      <div class="popup-riders">Riders (${region.assignedSRs.length})</div>
      <div class="popup-sr-chips">${chips || '<span style="color:#999;font-size:12px">No SRs assigned</span>'}</div>
      <div class="popup-toggle"><label><input type="checkbox"> Show SR Id</label></div>
      <div class="popup-actions">
        <button class="popup-btn btn-edit" onclick="openEditModal('${regionId}')">✏ Edit</button>
        <button class="popup-btn btn-delete" onclick="deleteRegion('${regionId}')">🗑 Delete</button>
      </div>`;
    layer.bindPopup(content, {maxWidth: 300}).openPopup();
  }

  function renderSidebar() {
    const list = document.getElementById('region-list');
    if (!list) return;
    if (regions.length === 0) {
      list.innerHTML = '<div style="color:#999;font-size:13px;text-align:center;padding:20px">No regions yet. Click "Draw Region" to start.</div>';
      return;
    }
    list.innerHTML = regions.map(r => `
      <div class="region-item" style="border-left-color:${r.color}" onclick="zoomToRegion('${r.id}')">
        <div class="region-info">
          <div class="region-dot" style="background:${r.color}"></div>
          <span class="region-name">${escHtml(r.name)}</span>
        </div>
        <span class="sr-badge">${r.assignedSRs.length} SR</span>
      </div>`).join('');
  }

  w.zoomToRegion = function(regionId: string) {
    const layer = regionLayers[regionId];
    if (layer) map.fitBounds(layer.getBounds(), {padding: [30,30]});
  };

  w.saveNameModal = function() {
    const input = document.getElementById('region-name-input') as HTMLInputElement;
    const name = input?.value.trim() || ('Zone ' + zoneCounter);
    const modal = document.getElementById('name-modal');
    if (modal) modal.style.display = 'none';
    if (!pendingLayer) return;
    const colorIdx = regions.length % COLORS.length;
    const region = {
      id: uuid(),
      name,
      color: COLORS[colorIdx],
      assignedSRs: [],
      polygon: pendingLayer.getLatLngs()[0].map((ll: any) => [ll.lat, ll.lng])
    };
    regions.push(region);
    addRegionToMap(region);
    pendingLayer = null;
    renderSidebar();
  };

  w.cancelNameModal = function() {
    const modal = document.getElementById('name-modal');
    if (modal) modal.style.display = 'none';
    pendingLayer = null;
  };

  w.deleteRegion = function(regionId: string) {
    map.closePopup();
    const layer = regionLayers[regionId];
    if (layer) {
      drawnItems.removeLayer(layer);
      delete regionLayers[regionId];
    }
    regions = regions.filter(r => r.id !== regionId);
    renderSidebar();
    setStatus('Region deleted.');
  };

  w.openEditModal = function(regionId: string) {
    map.closePopup();
    const region = regions.find(r => r.id === regionId);
    if (!region) return;
    editingRegionId = regionId;
    editedColor = region.color;
    const nameInput = document.getElementById('edit-region-name') as HTMLInputElement;
    if (nameInput) nameInput.value = region.name;
    
    const cp = document.getElementById('color-picker');
    if (cp) {
      cp.innerHTML = '';
      COLORS.forEach(c => {
        const sw = document.createElement('div');
        sw.className = 'color-swatch' + (c === region.color ? ' selected' : '');
        sw.style.background = c;
        sw.title = c;
        sw.onclick = () => {
          editedColor = c;
          cp.querySelectorAll('.color-swatch').forEach(s => s.classList.remove('selected'));
          sw.classList.add('selected');
        };
        cp.appendChild(sw);
      });
    }

    const sc = document.getElementById('sr-checkboxes');
    if (sc) {
      sc.innerHTML = '';
      SR_LIST.forEach(sr => {
        const item = document.createElement('div');
        item.className = 'sr-checkbox-item';
        const checked = region.assignedSRs.includes(sr) ? 'checked' : '';
        item.innerHTML = `<label><input type="checkbox" value="${sr}" ${checked}> ${sr}</label>`;
        sc.appendChild(item);
      });
    }

    const modal = document.getElementById('edit-modal');
    if (modal) modal.style.display = 'block';
  };

  w.saveEditModal = function() {
    const region = regions.find(r => r.id === editingRegionId);
    if (!region) return;
    const nameInput = document.getElementById('edit-region-name') as HTMLInputElement;
    if (nameInput) region.name = nameInput.value.trim() || region.name;
    region.color = editedColor || region.color;
    region.assignedSRs = Array.from(document.querySelectorAll('#sr-checkboxes input:checked'))
      .map((i: any) => i.value);
    const layer = regionLayers[region.id];
    if (layer) layer.setStyle({ color: region.color, fillColor: region.color });
    const modal = document.getElementById('edit-modal');
    if (modal) modal.style.display = 'none';
    renderSidebar();
    setStatus('Region updated.');
  };

  w.closeEditModal = function() {
    const modal = document.getElementById('edit-modal');
    if (modal) modal.style.display = 'none';
    editingRegionId = null;
  };

  // Button handlers
  const drawBtn = document.getElementById('draw-btn');
  if (drawBtn) {
    drawBtn.addEventListener('click', () => {
      const draw = new L.Draw.Polygon(map);
      draw.enable();
    });
  }

  const saveBtn = document.getElementById('save-btn');
  if (saveBtn) {
    saveBtn.addEventListener('click', () => {
      const payload = { regions: regions };
      fetch('/api/affinity-config/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
        .then(r => r.json())
        .then(() => {
          showToast('✅ Configuration saved successfully');
          setStatus('Saved to backend.');
        })
        .catch(() => {
          showToast('❌ Failed to save configuration');
          setStatus('Save failed.');
        });
    });
  }

  const loadBtn = document.getElementById('load-btn');
  if (loadBtn) {
    loadBtn.addEventListener('click', () => {
      fetch('/api/affinity-config/load')
        .then(r => r.json())
        .then((data: any) => {
          if (data && data.regions) {
            // Clear existing
            Object.keys(regionLayers).forEach(id => {
              drawnItems.removeLayer(regionLayers[id]);
            });
            regions = [];
            regionLayers = {};

            // Load saved regions
            regions = data.regions;
            regions.forEach(addRegionToMap);
            renderSidebar();
            showToast('✅ Configuration loaded successfully');
            setStatus('Loaded from backend.');
          } else {
            showToast('No saved configuration found');
            setStatus('No saved data.');
          }
        })
        .catch(() => {
          showToast('❌ Failed to load configuration');
          setStatus('Load failed.');
        });
    });
  }

  const clearBtn = document.getElementById('clear-btn');
  if (clearBtn) {
    clearBtn.addEventListener('click', () => {
      if (!confirm('Delete all regions? This cannot be undone.')) return;
      Object.keys(regionLayers).forEach(id => {
        drawnItems.removeLayer(regionLayers[id]);
      });
      regions = [];
      regionLayers = {};
      renderSidebar();
      fetch('/api/affinity-config/clear', { method: 'DELETE' })
        .then(() => {
          showToast('✅ All regions cleared');
          setStatus('Cleared.');
        })
        .catch(() => {
          showToast('❌ Failed to clear backend configuration');
        });
    });
  }

  const cancelNameBtn = document.getElementById('cancel-name-modal-btn');
  if (cancelNameBtn) cancelNameBtn.addEventListener('click', w.cancelNameModal);

  const saveNameBtn = document.getElementById('save-name-modal-btn');
  if (saveNameBtn) saveNameBtn.addEventListener('click', w.saveNameModal);

  const closeEditBtn = document.getElementById('close-edit-modal-btn');
  if (closeEditBtn) closeEditBtn.addEventListener('click', w.closeEditModal);

  const saveEditBtn = document.getElementById('save-edit-modal-btn');
  if (saveEditBtn) saveEditBtn.addEventListener('click', w.saveEditModal);

  // Initialize
  renderSidebar();
  setStatus('Ready');

  // Expose global function for boot check
  w.bootAffinityApp = () => {};
}

// Boot immediately if on affinity page
if (typeof window !== 'undefined') {
  (window as any).bootAffinityApp = bootAffinityApp;
}
