import { useEffect } from 'react';
import { bootAllocationApp } from '../lib/legacy-allocation';

export default function AllocationPage() {
  useEffect(() => {
    bootAllocationApp();
  }, []);

  return (
    <>
      {/* Full-screen loading overlay shown while allocation API is in flight */}
      <div id="alloc-loading-overlay" role="dialog" aria-modal="true" aria-live="polite" aria-label="Allocation in progress">
        <div className="alloc-loading-card">
          <div className="ring"></div>
          <div className="title" id="alloc-loading-title">Running allocation…</div>
          <div className="sub" id="alloc-loading-sub">Optimising routes and balancing earnings across SRs.</div>
          <div className="stage" id="alloc-loading-stage">Initialising</div>
          <div className="bar"></div>
        </div>
      </div>

      <header id="header">
        <h1>🚚 Shipment Allocation Optimizer v3</h1>
        <div id="header-right">
          <span id="hub-info">Hub</span>
          <span className="api-badge inactive" id="badge-osm">OSM</span>
          <span className="api-badge inactive" id="badge-google">Google Maps</span>
        </div>
      </header>

      <div id="main">
        <aside id="sidebar">
          {/* Progressive workflow tabs */}
          <div id="sidebar-tabs" role="tablist">
            <button type="button" className="sidebar-tab active" id="sb-tab-upload"
                    role="tab" aria-selected="true">
              <span className="tab-dot"></span> Upload
            </button>
            <button type="button" className="sidebar-tab" id="sb-tab-allocate"
                    role="tab" aria-selected="false" disabled
                    title="Upload a shipment file first to unlock allocation">
              <span className="tab-dot"></span> Allocate
            </button>
            <button type="button" className="sidebar-tab" id="sb-tab-monitor"
                    role="tab" aria-selected="false" disabled
                    title="Run an allocation first to unlock monitoring">
              <span className="tab-dot"></span> Monitor
            </button>
          </div>

          {/* Tab 1: Upload */}
          <div className="tab-pane active" id="tab-pane-upload" role="tabpanel">
            <div className="panel">
              <div className="panel-title">Upload Shipment File</div>
              <div id="drop-zone">
                <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#adb5bd" strokeWidth="1.5">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                  <polyline points="17 8 12 3 7 8"/>
                  <line x1="12" y1="3" x2="12" y2="15"/>
                </svg>
                Drag and drop CSV / XLSX here, or click to browse
              </div>
              <input type="file" id="csv-file-input" accept=".csv,.txt,.xlsx,.xls" style={{display:'none'}}/>
              <div id="file-info"></div>
              <button id="upload-btn">Upload</button>
              <div id="upload-result"></div>
            </div>
          </div>

          {/* Tab 2: Allocate */}
          <div className="tab-pane" id="tab-pane-allocate" role="tabpanel">
            <div className="panel">
              <div className="panel-title">Allocation</div>
              
              {/* Mode Toggle */}
              <div style={{display:'flex',gap:'4px',marginBottom:'12px',background:'var(--bg-soft)',border:'1px solid var(--border)',borderRadius:'var(--radius)',padding:'4px'}}>
                <button className="btn" id="mode-standard-btn" style={{flex:1,padding:'7px 12px',fontSize:'.8rem',background:'var(--primary)',color:'#fff',border:'none'}}>
                  🛣 Standard Routing
                </button>
                <button className="btn" id="mode-previous-btn" style={{flex:1,padding:'7px 12px',fontSize:'.8rem',background:'transparent',color:'var(--text-sub)',border:'none'}}>
                  📊 Previous
                </button>
              </div>
              
              {/* Standard Mode Workflow */}
              <div id="standard-workflow">
                <select id="date-select">
                  <option value="">-- Select date --</option>
                </select>
                <div className="btn-row" style={{marginBottom:'6px'}}>
                  <button className="btn btn-primary" id="run-osm-btn" title="Allocate routes using OpenStreetMap (ORS) road data">
                    🗺 Run Allocation (OSM)
                  </button>
                  <button className="btn btn-google" id="run-google-btn" title="Allocate routes using Google Maps road data — requires Google Maps API key">
                    🗺 Run Allocation (Google Maps)
                  </button>
                </div>
                <div className="btn-row">
                  <button className="btn btn-compare" id="compare-btn" disabled title="Show both OSM and Google Maps routes side by side for comparison">
                    🔍 Compare Routes
                  </button>
                  <button className="btn btn-secondary" id="undo-btn" disabled>↩ Undo</button>
                  <button className="btn btn-secondary" id="finalize-btn" disabled>✅ Finalize</button>
                </div>
                <div id="alloc-error" className="banner error"></div>
                <div id="alloc-info" className="banner info"></div>
                <div id="finalized-banner" className="banner success">✅ Allocation finalized - overrides locked</div>
              </div>
            </div>

            {/* SR Affinity Panel */}
            <div className="panel" id="affinity-match-panel">
              <div className="panel-title">
                🗺 Affinity Zones
                <span className="badge" id="affinity-match-badge" style={{background:'#8e24aa'}}>0 drawn</span>
              </div>

              {/* Allocation Mode Toggle */}
              <div style={{display:'flex',gap:'4px',marginBottom:'12px',background:'var(--bg-soft)',border:'1px solid var(--border)',borderRadius:'var(--radius)',padding:'4px'}} id="allocation-mode-toggle">
                <button className="btn" id="mode-count-btn"
                        style={{flex:1,padding:'7px 10px',fontSize:'.78rem',background:'transparent',color:'var(--text-sub)',border:'none'}}>
                  📊 Count-Based
                </button>
                <button className="btn" id="mode-timebased-btn"
                        style={{flex:1,padding:'7px 10px',fontSize:'.78rem',background:'transparent',color:'var(--text-sub)',border:'none'}}>
                  ⏱ Time-Based Config
                </button>
              </div>

              {/* Shift duration indicator */}
              <div id="shift-duration-indicator" style={{display:'none',background:'var(--primary-50)',border:'1px solid #bfdbfe',borderRadius:'var(--radius)',padding:'8px 12px',marginBottom:'10px',fontSize:'.8rem',color:'var(--primary-dark)',fontWeight:600,textAlign:'center'}}>
                ⏱ <span id="shift-duration-label">8 h shift</span>
              </div>

              {/* srCapacityMin / srCapacityMax inputs */}
              <div id="capacity-inputs" style={{display:'none',marginBottom:'8px'}}>
                <div style={{display:'flex',gap:'6px',alignItems:'center'}}>
                  <label style={{fontSize:'.76rem',fontWeight:600,color:'#343a40',whiteSpace:'nowrap'}}>Cap Min:</label>
                  <input type="number" id="srCapacityMin" min="1" max="200" defaultValue="20"
                         style={{width:'60px',padding:'4px 6px',border:'1px solid #ced4da',borderRadius:'4px',fontSize:'.8rem'}}/>
                  <label style={{fontSize:'.76rem',fontWeight:600,color:'#343a40',whiteSpace:'nowrap'}}>Max:</label>
                  <input type="number" id="srCapacityMax" min="1" max="200" defaultValue="40"
                         style={{width:'60px',padding:'4px 6px',border:'1px solid #ced4da',borderRadius:'4px',fontSize:'.8rem'}}/>
                </div>
              </div>

              {/* Draw Region mode */}
              <div id="affinity-draw-mode">
                <div style={{fontSize:'.74rem',color:'#6c757d',marginBottom:'8px'}}>
                  Draw delivery zones on the map. Each SR is assigned to one zone.
                </div>
                <div style={{display:'flex',alignItems:'center',gap:'6px',marginBottom:'8px'}}>
                  <label style={{fontSize:'.76rem',fontWeight:600,color:'#343a40',whiteSpace:'nowrap'}}>Zones to draw:</label>
                  <input type="number" id="region-count-input" min="1" max="20" defaultValue="3"
                         style={{width:'60px',padding:'4px 6px',border:'1px solid #ced4da',borderRadius:'4px',fontSize:'.8rem',fontWeight:600}}/>
                  <button id="apply-region-count-btn"
                          style={{padding:'4px 10px',fontSize:'.74rem',fontWeight:600,border:'none',borderRadius:'4px',cursor:'pointer',background:'#1976d2',color:'#fff'}}>
                    Set
                  </button>
                </div>
                <div id="affinity-draw-list" style={{maxHeight:'200px',overflowY:'auto',marginBottom:'6px'}}>
                  <div className="empty-text">Set zone count above to start drawing</div>
                </div>
                <div id="affinity-draw-status" style={{fontSize:'.72rem',color:'#6c757d',marginBottom:'6px',minHeight:'18px'}}></div>
              </div>

              <div style={{display:'flex',gap:'5px'}}>
                <button className="btn btn-primary" style={{flex:1,fontSize:'.76rem'}} id="run-affinity-allocation-btn">🎯 Run with Affinity</button>
                <button className="btn btn-secondary" style={{fontSize:'.76rem'}} id="save-regions-btn" title="Save zones to disk">💾 Save</button>
                <button className="btn btn-secondary" style={{fontSize:'.76rem'}} id="clear-affinity-btn">Clear</button>
              </div>
              <div id="affinity-match-result" style={{marginTop:'6px'}}></div>
            </div>

            {/* Attendance */}
            <div className="panel">
              <div className="panel-title">
                SR Attendance
                <span className="badge" id="present-count-badge">0 present</span>
              </div>
              <div style={{display:'flex',gap:'5px',marginBottom:'8px'}}>
                <button className="btn btn-secondary" style={{flex:1,fontSize:'.76rem'}} id="mark-all-present-btn">All Present</button>
                <button className="btn btn-secondary" style={{flex:1,fontSize:'.76rem'}} id="mark-none-present-btn">All Absent</button>
              </div>
              <div style={{display:'flex',gap:'5px',marginBottom:'8px'}}>
                <input type="text" id="new-sr-input" placeholder="New SR name (e.g. SR-011)" style={{flex:1,padding:'5px 7px',border:'1px solid #ced4da',borderRadius:'6px',fontSize:'.8rem'}}/>
                <button className="btn btn-primary" style={{fontSize:'.76rem',whiteSpace:'nowrap'}} id="add-new-sr-btn">+ Add SR</button>
              </div>
              <div id="attendance-list"><div className="empty-text">Select a date to load attendance</div></div>
              <button id="save-shift-durations-btn">💾 Save Shift Durations</button>
              <div id="sr-zone-save-row" style={{display:'none',marginTop:'8px'}}>
                <button className="btn btn-primary" style={{width:'100%',fontSize:'.76rem'}} id="save-sr-zone-btn">💾 Save SR Zone Assignments</button>
                <div id="sr-zone-saved-msg" style={{display:'none',fontSize:'.72rem',color:'#2e7d32',textAlign:'center',marginTop:'4px'}}>✅ SR zone assignments saved</div>
              </div>
            </div>
          </div>

          {/* Tab 3: Monitor */}
          <div className="tab-pane" id="tab-pane-monitor" role="tabpanel">
            {/* Summary */}
            <div className="panel hidden" id="summary-panel">
              <div className="panel-title">Summary <span id="summary-mode-badge" style={{fontSize:'.7rem',background:'#e9ecef',color:'#495057',borderRadius:'10px',padding:'1px 7px',fontWeight:600}}></span></div>
              <div className="summary-stats" id="summary-stats"></div>
            </div>

            {/* SR List */}
            <div className="panel hidden" id="sr-list-panel">
              <div className="panel-title">Service Representatives</div>
              <div id="sr-list"></div>
            </div>

            {/* Region Health Panel */}
            <div className="panel hidden" id="region-health-panel">
              <div className="panel-title" style={{cursor:'pointer'}} id="region-health-toggle">
                🗺 Region Health
                <span id="region-health-badge" style={{fontSize:'.7rem',borderRadius:'10px',padding:'2px 8px',fontWeight:600,background:'#e9ecef',color:'#495057'}}></span>
                <span id="region-health-arrow" style={{fontSize:'.7rem',marginLeft:'auto'}}>▼</span>
              </div>
              <div id="region-health-body">
                <div id="region-health-list"></div>
              </div>
            </div>

            {/* Route Details */}
            <div className="panel hidden" id="route-panel">
              <div className="panel-title" id="route-panel-title">Route</div>
              <div className="route-stats" id="route-stats"></div>
              <div className="stop-list" id="stop-list"></div>
            </div>

            {/* LM Integration Panel */}
            <div className="panel hidden" id="lm-panel">
              <div className="panel-title">
                Push to LM System
                <span className="badge" id="lm-status-badge" style={{background:'#6c757d'}}>Not Connected</span>
              </div>

              <div style={{display:'flex',gap:'5px',marginBottom:'8px',alignItems:'center'}}>
                <button className="btn btn-secondary" style={{fontSize:'.72rem',padding:'4px 8px'}} id="lm-connect-btn">🔑 Connect</button>
                <span id="lm-token-info" style={{fontSize:'.72rem',color:'#6c757d'}}>Click Connect to auth with Keycloak</span>
              </div>

              <div style={{display:'flex',gap:'6px',marginBottom:'8px'}}>
                <div style={{flex:1,background:'#e3f2fd',border:'1px solid #90caf9',borderRadius:'6px',padding:'6px',textAlign:'center'}}>
                  <div style={{fontSize:'.72rem',color:'#0d47a1',fontWeight:600}}>Pending</div>
                  <div id="lm-pending-count" style={{fontSize:'.9rem',fontWeight:700,color:'#1565c0'}}>-</div>
                </div>
                <div style={{flex:1,background:'#e8f5e9',border:'1px solid #a5d6a7',borderRadius:'6px',padding:'6px',textAlign:'center'}}>
                  <div style={{fontSize:'.72rem',color:'#1b5e20',fontWeight:600}}>Allocated</div>
                  <div id="lm-allocated-count" style={{fontSize:'.9rem',fontWeight:700,color:'#2e7d32'}}>-</div>
                </div>
                <button className="btn btn-secondary" style={{fontSize:'.72rem',padding:'4px 8px',alignSelf:'center'}} id="lm-fetch-dashboard-btn">↻</button>
              </div>

              <div style={{display:'flex',alignItems:'center',justifyContent:'space-between',marginBottom:'5px'}}>
                <span style={{fontSize:'.78rem',fontWeight:600,color:'#343a40'}}>Map Routes → LM Delivery Users</span>
                <button className="btn btn-secondary" style={{fontSize:'.68rem',padding:'2px 6px'}} id="lm-load-delivery-users-btn">Load SRs</button>
              </div>
              <div id="lm-sr-mapping" style={{maxHeight:'240px',overflowY:'auto',marginBottom:'8px'}}>
                <div className="empty-text">Run allocation first to see routes</div>
              </div>

              <div style={{display:'flex',gap:'8px',marginBottom:'8px',fontSize:'.76rem',alignItems:'center'}}>
                <label><input type="checkbox" id="lm-auto-confirm" defaultChecked/> Auto-confirm (OFD)</label>
                <label style={{marginLeft:'auto'}}>Delay:
                  <input type="number" id="lm-delay" defaultValue="200" min="50" max="2000" step="50" style={{width:'55px',padding:'2px 4px',border:'1px solid #ced4da',borderRadius:'4px',fontSize:'.74rem'}}/>ms
                </label>
              </div>

              <div style={{display:'flex',gap:'5px',flexWrap:'wrap'}}>
                <button className="btn" id="lm-push-all-btn" disabled style={{flex:1,background:'#e65100',color:'#fff',fontSize:'.78rem'}}>
                  🚀 Push All Routes
                </button>
                <button className="btn btn-secondary" id="lm-confirm-all-btn" disabled style={{flex:1,fontSize:'.78rem'}}>
                  ✅ Confirm All
                </button>
              </div>

              <div id="lm-progress" style={{display:'none',marginTop:'8px'}}>
                <div style={{fontSize:'.76rem',fontWeight:600,color:'#495057',marginBottom:'3px'}} id="lm-progress-text">Pushing...</div>
                <div style={{background:'#e9ecef',borderRadius:'4px',height:'6px',overflow:'hidden'}}>
                  <div id="lm-progress-bar" style={{background:'#1976d2',height:'100%',width:'0%',transition:'width .3s'}}></div>
                </div>
              </div>

              <div id="lm-results" style={{marginTop:'8px'}}></div>
            </div>
          </div>
        </aside>

        <div id="map-container">
          <div id="map"></div>
          <div id="gmap" style={{display:'none',width:'100%',height:'100%'}}></div>
          <button id="fullscreen-btn" title="Toggle fullscreen map" style={{position:'absolute',top:'10px',left:'50px',zIndex:10000,background:'#fff',color:'#333',border:'2px solid rgba(0,0,0,.2)',borderRadius:'4px',padding:'4px 8px',cursor:'pointer',fontSize:'.75rem',fontWeight:600,boxShadow:'none',lineHeight:'1.4'}}>⛶</button>
          <button id="map-toggle-btn" title="Switch map provider" style={{position:'absolute',top:'10px',left:'90px',zIndex:10000,background:'#fff',color:'#333',border:'2px solid rgba(0,0,0,.2)',borderRadius:'4px',padding:'4px 8px',cursor:'pointer',fontSize:'.72rem',fontWeight:600,boxShadow:'none',lineHeight:'1.4'}}>🗺 Google</button>
          <button id="pincode-boundary-btn" title="Toggle pincode boundaries" style={{position:'absolute',top:'10px',left:'160px',zIndex:10000,background:'#fff',color:'#333',border:'2px solid rgba(0,0,0,.2)',borderRadius:'4px',padding:'4px 8px',cursor:'pointer',fontSize:'.72rem',fontWeight:600,boxShadow:'none',lineHeight:'1.4',opacity:.6}}>📍 Show Pincodes</button>
          <div id="compare-banner">🔍 Comparison Mode: OSM (solid) vs Google Maps (dashed)</div>
          <div id="map-legend">
            <h4>Legend <span className="legend-toggle">▼</span></h4>
            <div id="legend-items"></div>
          </div>

          {/* Density markers toggle */}
          <div id="density-toggle-btn"
               title="Show/hide shipment density markers (clustered dots with numbers)"
               style={{position:'absolute',bottom:'100px',right:'12px',zIndex:10001,
                      background:'#fff',border:'2px solid rgba(0,0,0,.2)',borderRadius:'6px',
                      padding:'6px 10px',cursor:'pointer',fontSize:'.75rem',fontWeight:600,
                      boxShadow:'0 2px 6px rgba(0,0,0,.18)',display:'flex',alignItems:'center',gap:'6px',
                      userSelect:'none',lineHeight:'1.3'}}>
            <span id="density-toggle-icon" style={{fontSize:'.9rem'}}>🔴🔵</span>
            <span id="density-toggle-label">Density ON</span>
            <span id="density-toggle-indicator"
                  style={{width:'28px',height:'14px',borderRadius:'7px',background:'#43a047',
                         display:'inline-flex',alignItems:'center',padding:'0 2px',transition:'background .2s',flexShrink:0}}>
              <span style={{width:'10px',height:'10px',borderRadius:'50%',background:'#fff',
                           marginLeft:'auto',transition:'margin .2s',display:'block'}}></span>
            </span>
          </div>

          {/* Shipment display toggle */}
          <div id="shipment-toggle-btn"
               title="Toggle between all shipments and affinity-only shipments"
               style={{position:'absolute',bottom:'12px',right:'12px',zIndex:10001,
                      background:'#fff',border:'2px solid rgba(0,0,0,.2)',borderRadius:'6px',
                      padding:'6px 10px',cursor:'pointer',fontSize:'.75rem',fontWeight:600,
                      boxShadow:'0 2px 6px rgba(0,0,0,.18)',display:'flex',alignItems:'center',gap:'6px',
                      userSelect:'none',lineHeight:'1.3'}}>
            <span id="shipment-toggle-icon" style={{fontSize:'.9rem'}}>🗺</span>
            <span id="shipment-toggle-label">All Shipments</span>
            <span id="shipment-toggle-indicator"
                  style={{width:'28px',height:'14px',borderRadius:'7px',background:'#43a047',
                         display:'inline-flex',alignItems:'center',padding:'0 2px',transition:'background .2s',flexShrink:0}}>
              <span style={{width:'10px',height:'10px',borderRadius:'50%',background:'#fff',
                           marginLeft:'auto',transition:'margin .2s',display:'block'}}></span>
            </span>
          </div>

          {/* Raw shipment plot button */}
          <div id="raw-plot-btn"
               title="Plot all uploaded shipments by lat/lng — no zone or SR filter"
               style={{position:'absolute',bottom:'56px',right:'12px',zIndex:10001,
                      background:'#fff',border:'2px solid rgba(0,0,0,.2)',borderRadius:'6px',
                      padding:'6px 10px',cursor:'pointer',fontSize:'.75rem',fontWeight:600,
                      boxShadow:'0 2px 6px rgba(0,0,0,.18)',display:'flex',alignItems:'center',gap:'6px',
                      userSelect:'none',lineHeight:'1.3'}}>
            <span style={{fontSize:'.9rem'}}>📦</span>
            <span id="raw-plot-label">Plot All Points</span>
          </div>
        </div>
      </div>

      <dialog id="override-modal">
        <div className="modal-header">🔄 Reassign Shipment</div>
        <div className="modal-body">
          <div className="modal-info" id="modal-info"></div>
          <div style={{fontSize:'.8rem',fontWeight:600,marginBottom:'7px',color:'#343a40'}}>Select new SR:</div>
          <div className="sr-radio-list" id="sr-radio-list"></div>
        </div>
        <div className="modal-footer">
          <button className="btn btn-secondary" id="modal-cancel-btn">Cancel</button>
          <button className="btn btn-primary" id="modal-confirm-btn">Confirm Reassign</button>
        </div>
      </dialog>
    </>
  );
}
