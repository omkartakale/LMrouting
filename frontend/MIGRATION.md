# LMrouting Frontend Migration

## Overview

This document describes the React+TypeScript+Tailwind migration of the legacy static HTML allocation system. The migration uses a **hybrid approach** where React owns the UI shell and layout, while legacy runtime logic operates via DOM IDs to minimize parity risk and ensure zero functional regression.

## Architecture

### Hybrid Migration Strategy

**Why Hybrid?**
- Preserves 100% of legacy behavior and business logic
- Minimizes risk of subtle bugs in complex allocation algorithms
- Allows progressive enhancement over time
- Maintains exact API contracts with Spring Boot backend

**How It Works:**
1. **React Layer**: Renders the DOM skeleton with exact IDs and structure from legacy HTML
2. **Legacy Runtime**: TypeScript modules containing ported inline JavaScript
3. **Boot Lifecycle**: React mounts DOM → Legacy runtime initializes → Attaches event handlers

```
┌─────────────────────────────────────────┐
│   React Components (UI Shell)          │
│   - AllocationPage.tsx                  │
│   - AffinitySetupPage.tsx              │
└─────────────┬───────────────────────────┘
              │ Renders DOM with IDs
              ▼
┌─────────────────────────────────────────┐
│   Legacy Runtime (Business Logic)      │
│   - legacy-allocation.ts (main)         │
│   - legacy-allocation-rest.ts (part 2)  │
│   - legacy-affinity.ts (affinity page)  │
└─────────────┬───────────────────────────┘
              │ fetch('/api/...')
              ▼
┌─────────────────────────────────────────┐
│   Spring Boot Backend (Unchanged)      │
│   - Same REST API endpoints             │
│   - Same allocation algorithms          │
└─────────────────────────────────────────┘
```

## Project Structure

```
frontend/
├── index.html                      # React entry for allocation page
├── affinity-setup.html             # React entry for affinity page
├── package.json                    # Dependencies
├── vite.config.ts                  # Build configuration
├── tsconfig.json                   # TypeScript config (app)
├── tsconfig.node.json              # TypeScript config (Vite)
├── tailwind.config.js              # Tailwind CSS config
├── postcss.config.js               # PostCSS config
└── src/
    ├── main.tsx                    # Entry: allocation app
    ├── affinity-main.tsx           # Entry: affinity app
    ├── index.css                   # Global styles + design tokens
    ├── routes/
    │   ├── AllocationPage.tsx      # Main allocation page shell
    │   └── AffinitySetupPage.tsx   # Affinity setup page shell
    ├── lib/
    │   ├── legacy-allocation.ts    # Legacy runtime part 1 (1374 lines)
    │   ├── legacy-allocation-rest.ts # Legacy runtime part 2 (remainder)
    │   ├── legacy-affinity.ts      # Affinity page runtime
    │   └── api.ts                  # API wrapper (typed fetch)
    ├── stores/
    │   ├── sessionStore.ts         # Session state (Zustand)
    │   ├── runSnapshotStore.ts     # Allocation snapshots
    │   └── uiStore.ts              # UI state (fullscreen, panels)
    ├── api/
    │   └── endpoints.ts            # API re-export for compatibility
    ├── features/
    │   ├── analytics/
    │   │   └── AnalyticsPage.tsx   # Read-only analytics view
    │   └── ... (optional enhancements)
    └── map/
        └── MapFullscreenControls.tsx # Map controls component
```

## Development Workflow

### Prerequisites
- Node.js >= 16 (Node 20 LTS recommended for best performance)
- npm >= 8
- Spring Boot backend running on `localhost:8080`

**Note on Node Versions:**
- **Node 16**: Currently supported via Vite 4.x (stable, tested)
- **Node 18+**: Can upgrade to Vite 5.x for better performance (see `NODE_COMPATIBILITY.md`)
- **Recommended**: Node 20 LTS for long-term support

### Setup

```bash
cd frontend
npm install
```

### Development Server

```bash
npm run dev
```

This starts Vite dev server on `http://localhost:5173` with:
- Hot module replacement (HMR)
- Proxy to backend: `/api/*` → `http://localhost:8080/api/*`
- TypeScript type checking
- Tailwind CSS JIT compilation

### Build

```bash
npm run build
```

Outputs to `frontend/dist/`:
- `index.html` - Allocation app entry
- `affinity-setup.html` - Affinity app entry
- `assets/` - Bundled JS/CSS with content hashing

### Type Checking

```bash
npm run lint
```

Runs TypeScript compiler in `--noEmit` mode to check types without building.

## Deployment Strategy (Parallel Mode)

Since deployment model is **parallel** (React app runs alongside legacy HTML), follow these steps:

### Option A: Same Spring Boot Server

1. **Build the React app:**
   ```bash
   cd frontend
   npm run build
   ```

2. **Copy dist/ to Spring Boot static resources:**
   ```bash
   # Example (adjust paths as needed)
   cp -r dist/* ../src/main/resources/static/react/
   ```

3. **Add Spring Boot route:**
   ```java
   @Controller
   public class ReactRoutingController {
       @GetMapping("/react/**")
       public String react() {
           return "forward:/react/index.html";
       }
       
       @GetMapping("/react/affinity-setup/**")
       public String reactAffinity() {
           return "forward:/react/affinity-setup.html";
       }
   }
   ```

4. **Access:**
   - Legacy: `http://localhost:8080/` → `index.html`
   - React: `http://localhost:8080/react/` → React SPA
   - Users can switch between versions during rollout

### Option B: Separate Server (Recommended for Testing)

1. **Dev server for React:** `npm run dev` on port 5173
2. **Spring Boot backend:** Runs on port 8080
3. **Proxy configured:** Vite proxies `/api/*` to backend
4. **Access:** `http://localhost:5173`

### Option C: Static CDN / Nginx

1. Build: `npm run build`
2. Upload `dist/` to CDN or serve via Nginx
3. Configure backend CORS to allow React origin
4. Update `VITE_API_BASE_URL` environment variable

## Environment Variables

Create `frontend/.env` for custom configuration:

```env
# API base URL (defaults to /api for same-origin)
VITE_API_BASE_URL=/api

# Optional: Backend URL for development
VITE_BACKEND_URL=http://localhost:8080
```

Access in code:
```typescript
const apiBase = import.meta.env.VITE_API_BASE_URL || '/api';
```

## Key Files

### `legacy-allocation.ts`

**Lines:** 1374  
**Purpose:** First half of legacy runtime - core allocation flow  
**Exports:** `bootAllocationApp()`  
**Contents:**
- Map initialization (Leaflet + Google Maps)
- Upload flow
- Attendance management
- Allocation execution (OSM/Google)
- SR list rendering
- Route visualization
- Timeline/summary panels
- Reassignment flow
- Finalization

**Dynamic Import:** Loads `legacy-allocation-rest.ts` at line 1388

### `legacy-allocation-rest.ts`

**Purpose:** Second half of legacy runtime  
**Exports:** `installRest(ctx, refs)`  
**Contents:**
- Region health panel
- Density/shipment display toggles
- Raw shipment plot
- Affinity mode & custom regions
- Polygon drawing/editing
- SR zone assignment
- Allocation mode switching
- Previous allocation loading
- LM integration (push/confirm trips)

**Injection Pattern:** Called by main runtime to install functions on `window`

### `legacy-affinity.ts`

**Lines:** ~350  
**Purpose:** Standalone affinity setup page runtime  
**Exports:** `bootAffinityApp()`  
**Contents:**
- Leaflet map with drawing controls
- Region CRUD (create, read, update, delete)
- SR assignment to regions
- Save/load/clear operations
- Backend persistence via `/api/affinity-config/*`

## API Surface

All backend endpoints are documented in [`src/lib/api.ts`](src/lib/api.ts). The API wrapper provides:

- Typed fetch wrappers
- Consistent error handling
- Same URLs as legacy HTML
- Re-exported for compatibility in `api/endpoints.ts`

**Example:**
```typescript
import { api } from '@/lib/api';

// Type-safe allocation call
const summary = await api.getAllocationSummary(date);
```

## Design Tokens

CSS custom properties are defined in [`src/index.css`](src/index.css) and match the legacy HTML exactly:

```css
:root {
  --primary: #2563eb;
  --bg: #f3f4f6;
  --border: #e5e7eb;
  /* ...40+ tokens */
}
```

Tailwind is configured to use these tokens for consistency.

## Migration Checklist

### Core Flows (40+ items)

**Upload & Allocation:**
- [ ] Upload CSV with valid data
- [ ] Select date and load attendance
- [ ] Mark SRs present/absent
- [ ] Run OSM allocation
- [ ] Run Google allocation
- [ ] Compare routes (OSM vs Google)
- [ ] View summary panel (stats, priority, earnings)

**Reassignment:**
- [ ] Open reassign modal from stop popup
- [ ] Select target SR
- [ ] Confirm override
- [ ] Update map and summary
- [ ] Undo override

**Finalization:**
- [ ] Finalize allocation
- [ ] Verify lock state
- [ ] Prevent further edits

**Region Health (Time-Based Mode):**
- [ ] Toggle region health panel
- [ ] Display overflow/idle metrics
- [ ] Auto-reassign suggestions
- [ ] Apply auto-reassign

**Affinity Custom Regions:**
- [ ] Set region count
- [ ] Draw region on map
- [ ] Edit region (name, color, SR assignment)
- [ ] Delete region
- [ ] Save/load/clear operations
- [ ] Run affinity allocation

**Previous Allocation:**
- [ ] Load previous allocation
- [ ] Compare with current

**LM Integration:**
- [ ] Token refresh
- [ ] Dashboard fetch
- [ ] Push all routes
- [ ] Confirm all trips

**UI Interactions:**
- [ ] Map toggle (Leaflet ↔ Google)
- [ ] Map fullscreen toggle
- [ ] Pincode boundary toggle
- [ ] Hub boundary display
- [ ] Density markers toggle
- [ ] Raw shipment plot toggle
- [ ] Timeline expand/collapse
- [ ] SR selection and highlighting

**Edge Cases:**
- [ ] Upload invalid CSV (error display)
- [ ] Allocation with 0 present SRs (error)
- [ ] Allocation timeout handling
- [ ] Network errors display correctly
- [ ] Finalized allocation prevents edits
- [ ] Custom region validation (area, hub overlap)

## Known Limitations

1. **Node 16 Compatibility**: Currently using Vite 4.x for Node 16 support. Upgrade to Node 18+ to use Vite 5 for better performance. See [`NODE_COMPATIBILITY.md`](NODE_COMPATIBILITY.md) for details.

2. **Legacy Runtime Dependencies**: The app still depends on CDN-loaded libraries (Leaflet, Geoman, Google Maps). These are loaded via `<script>` tags in the HTML shell.

3. **Google Maps API Key**: Loaded at runtime from `/api/config`. The legacy key fetch pattern is preserved.

4. **Map Library Versions**: Uses exact CDN versions from legacy HTML to avoid behavior changes.

5. **Hybrid State Management**: Some state lives in React (stores), some in legacy runtime (window globals). This is intentional for migration safety.

## Future Enhancements (Post-Parity)

Once functional parity is achieved and validated, consider these improvements:

### Phase 1: React-ify Components
- Replace legacy modals with React Portal-based modals
- Convert form interactions to controlled components
- Migrate panels to React state instead of class toggling
- Add React suspense for loading states

### Phase 2: State Management
- Migrate legacy globals to Zustand stores
- Implement optimistic updates for better UX
- Add undo/redo stack for allocation changes

### Phase 3: Map Integration
- Fully integrate react-leaflet
- Add MapFullscreenControls to map rendering
- Create reusable map components (markers, polylines, boundaries)

### Phase 4: Data Fetching
- Integrate @tanstack/react-query fully
- Add query caching and background refetching
- Implement stale-while-revalidate patterns

### Phase 5: Analytics
- Integrate AnalyticsPage into main app
- Add charts and visualizations
- Export allocation reports

## Troubleshooting

### Build Fails with "Cannot find module"

**Symptom:** TypeScript errors about missing modules  
**Solution:** Run `npm install` to ensure all dependencies are installed

### Map Doesn't Initialize

**Symptom:** Blank map container  
**Solution:** Check that CDN scripts loaded, verify DOM IDs match legacy HTML

### API Calls Fail with CORS

**Symptom:** Network errors in browser console  
**Solution:** 
- In dev: Verify Vite proxy config in `vite.config.ts`
- In prod: Configure backend CORS headers

### Allocation Takes Forever

**Symptom:** Loading overlay stuck  
**Solution:** Check backend logs, verify `/api/allocate` endpoint is responding

### Previous Allocation Not Found

**Symptom:** "No previous allocation" error  
**Solution:** Ensure backend has data for the selected date in previous allocations table

## Support

For questions or issues with the migration:

1. Check this documentation
2. Review [`migration-status.md`](migration-status.md) for current progress
3. Inspect legacy HTML source for original behavior reference
4. Test against legacy page for parity comparison

## License

Same as parent LMrouting project.
