# Node.js 16 Compatibility Fix

## Issue
The dev server failed to start with:
```
TypeError: crypto$2.getRandomValues is not a function
```

## Root Cause
- **Vite 5.x requires Node.js >= 18**
- Current environment: Node v16.20.2
- `crypto.getRandomValues` is not fully available in Node 16

## Solution Applied
Downgraded to Vite 4.x for Node 16 compatibility:

```json
{
  "vite": "^4.5.3",
  "@vitejs/plugin-react": "^4.2.1",
  "react-leaflet": "^4.2.1"
}
```

## Changes Made
1. **Vite**: `5.4.3` → `4.5.14` (stable, Node 16 compatible)
2. **@vitejs/plugin-react**: `4.3.1` → `4.2.1` (Vite 4 compatible)
3. **react-leaflet**: `5.0.0` → `4.2.1` (React 18 compatible)
4. Added `engines` field to document Node 16+ requirement

## Status
✅ **Dev server**: Running on http://localhost:5173
✅ **Production build**: Successful
✅ **Long-term stability**: Vite 4 is LTS, well-tested

## Future Upgrade Path
When Node.js is upgraded to v18+:
1. Update to Vite 5.x: `npm install vite@^5 @vitejs/plugin-react@^4.3 -D`
2. Update to react-leaflet 5: `npm install react-leaflet@^5`
3. Test thoroughly and commit

## Recommendation
**Upgrade Node.js to v20 LTS** for:
- Better performance
- Modern features
- Security patches
- Full ecosystem support

Install via: https://nodejs.org/en/download/package-manager
