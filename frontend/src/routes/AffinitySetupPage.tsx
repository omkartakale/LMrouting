import { useEffect } from 'react';
import { bootAffinityApp } from '../lib/legacy-affinity';

export default function AffinitySetupPage() {
  useEffect(() => {
    bootAffinityApp();
  }, []);

  return (
    <>
      <style>{`
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; overflow: hidden; }
        #header { background: #1a1a2e; color: white; padding: 12px 20px; display: flex; align-items: center; justify-content: space-between; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        #header h1 { font-size: 20px; font-weight: 600; }
        #header a { color: #64b5f6; text-decoration: none; font-size: 14px; }
        #header a:hover { text-decoration: underline; }
        #container { display: flex; height: calc(100vh - 48px); }
        #map { flex: 1; }
        #sidebar { width: 280px; background: white; border-left: 1px solid #e0e0e0; display: flex; flex-direction: column; overflow-y: auto; }
        #sidebar-header { padding: 16px; border-bottom: 1px solid #e0e0e0; }
        #sidebar-header h2 { font-size: 18px; margin-bottom: 12px; }
        #draw-btn { width: 100%; padding: 10px; background: #4CAF50; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 14px; font-weight: 500; }
        #draw-btn:hover { background: #45a049; }
        #region-list { flex: 1; padding: 12px; overflow-y: auto; }
        .region-item { padding: 10px 12px; margin-bottom: 8px; background: #f5f5f5; border-radius: 4px; cursor: pointer; display: flex; align-items: center; justify-content: space-between; border-left: 4px solid; }
        .region-item:hover { background: #eeeeee; }
        .region-info { display: flex; align-items: center; flex: 1; }
        .region-dot { width: 12px; height: 12px; border-radius: 50%; margin-right: 8px; }
        .region-name { font-size: 14px; font-weight: 500; }
        .sr-badge { background: #2196F3; color: white; padding: 2px 8px; border-radius: 12px; font-size: 11px; margin-left: 8px; }
        #sidebar-footer { padding: 12px; border-top: 1px solid #e0e0e0; }
        .footer-btn { width: 100%; padding: 10px; margin-bottom: 8px; border: none; border-radius: 4px; cursor: pointer; font-size: 14px; font-weight: 500; }
        #save-btn { background: #2196F3; color: white; }
        #save-btn:hover { background: #1976D2; }
        #load-btn { background: #FF9800; color: white; }
        #load-btn:hover { background: #F57C00; }
        #clear-btn { background: #f44336; color: white; }
        #clear-btn:hover { background: #d32f2f; }
        #status { padding: 8px 12px; font-size: 12px; color: #666; text-align: center; border-top: 1px solid #e0e0e0; }
        .modal { display: none; position: fixed; z-index: 10000; left: 0; top: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.5); }
        .modal-content { background: white; margin: 5% auto; padding: 24px; border-radius: 8px; width: 90%; max-width: 500px; box-shadow: 0 4px 20px rgba(0,0,0,0.3); }
        .modal-header { font-size: 18px; font-weight: 600; margin-bottom: 16px; }
        .modal-body { margin-bottom: 20px; }
        .form-group { margin-bottom: 16px; }
        .form-group label { display: block; margin-bottom: 6px; font-size: 14px; font-weight: 500; }
        .form-group input[type="text"] { width: 100%; padding: 8px; border: 1px solid #ddd; border-radius: 4px; font-size: 14px; }
        .color-picker { display: flex; gap: 8px; flex-wrap: wrap; }
        .color-swatch { width: 40px; height: 40px; border-radius: 4px; cursor: pointer; border: 3px solid transparent; }
        .color-swatch.selected { border-color: #333; }
        .sr-checkboxes { max-height: 200px; overflow-y: auto; border: 1px solid #ddd; border-radius: 4px; padding: 8px; }
        .sr-checkbox-item { margin-bottom: 6px; }
        .sr-checkbox-item label { display: flex; align-items: center; cursor: pointer; }
        .sr-checkbox-item input { margin-right: 8px; }
        .modal-footer { display: flex; gap: 8px; justify-content: flex-end; }
        .modal-btn { padding: 8px 16px; border: none; border-radius: 4px; cursor: pointer; font-size: 14px; font-weight: 500; }
        .btn-primary { background: #2196F3; color: white; }
        .btn-primary:hover { background: #1976D2; }
        .btn-secondary { background: #9E9E9E; color: white; }
        .btn-secondary:hover { background: #757575; }
        .leaflet-popup-content { min-width: 250px; }
        .popup-header { font-size: 16px; font-weight: 600; margin-bottom: 8px; }
        .popup-riders { font-size: 13px; color: #666; margin-bottom: 8px; }
        .popup-sr-chips { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 12px; }
        .sr-chip { background: #E3F2FD; color: #1976D2; padding: 4px 8px; border-radius: 12px; font-size: 11px; }
        .popup-toggle { margin-bottom: 12px; font-size: 12px; }
        .popup-actions { display: flex; gap: 8px; }
        .popup-btn { padding: 6px 12px; border: none; border-radius: 4px; cursor: pointer; font-size: 12px; font-weight: 500; }
        .btn-edit { background: #2196F3; color: white; }
        .btn-delete { background: #f44336; color: white; }
        .toast { position: fixed; bottom: 20px; right: 20px; background: #323232; color: white; padding: 12px 20px; border-radius: 4px; z-index: 10001; display: none; box-shadow: 0 2px 8px rgba(0,0,0,0.3); }
        .toast.show { display: block; animation: fadein 0.3s, fadeout 0.3s 2.7s; }
        @keyframes fadein { from { opacity: 0; } to { opacity: 1; } }
        @keyframes fadeout { from { opacity: 1; } to { opacity: 0; } }
      `}</style>

      <div id="header">
        <h1>🗺 Affinity Region Setup</h1>
        <a href="/">← Back to Allocation</a>
      </div>
      <div id="container">
        <div id="map"></div>
        <div id="sidebar">
          <div id="sidebar-header">
            <h2>Affinity Setup</h2>
            <button id="draw-btn">+ Draw Region</button>
          </div>
          <div id="region-list"></div>
          <div id="sidebar-footer">
            <button id="save-btn" className="footer-btn">💾 Save All</button>
            <button id="load-btn" className="footer-btn">📂 Load Saved</button>
            <button id="clear-btn" className="footer-btn">🗑 Clear All</button>
          </div>
          <div id="status">Ready</div>
        </div>
      </div>
      <div id="name-modal" className="modal">
        <div className="modal-content">
          <div className="modal-header">Name Your Region</div>
          <div className="modal-body">
            <div className="form-group">
              <label>Region Name</label>
              <input type="text" id="region-name-input" placeholder="e.g., Wanowrie Zone"/>
            </div>
          </div>
          <div className="modal-footer">
            <button className="modal-btn btn-secondary" id="cancel-name-modal-btn">Cancel</button>
            <button className="modal-btn btn-primary" id="save-name-modal-btn">Save</button>
          </div>
        </div>
      </div>
      <div id="edit-modal" className="modal">
        <div className="modal-content">
          <div className="modal-header">Edit Region</div>
          <div className="modal-body">
            <div className="form-group">
              <label>Region Name</label>
              <input type="text" id="edit-region-name"/>
            </div>
            <div className="form-group">
              <label>Color</label>
              <div className="color-picker" id="color-picker"></div>
            </div>
            <div className="form-group">
              <label>Assign Service Representatives</label>
              <div className="sr-checkboxes" id="sr-checkboxes"></div>
            </div>
          </div>
          <div className="modal-footer">
            <button className="modal-btn btn-secondary" id="close-edit-modal-btn">Cancel</button>
            <button className="modal-btn btn-primary" id="save-edit-modal-btn">Save</button>
          </div>
        </div>
      </div>
      <div id="toast" className="toast"></div>
    </>
  );
}
