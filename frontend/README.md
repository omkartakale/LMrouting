# LMrouting Frontend

React+TypeScript+Tailwind frontend for the Shipment Allocation Optimizer.

## Quick Start

```bash
# Install dependencies
npm install

# Start development server (requires backend on localhost:8080)
npm run dev

# Build for production
npm run build

# Type check
npm run lint
```

**Dev server running at:** http://localhost:5173

**Node.js Compatibility:**
- Requires Node >= 16 (tested on Node 16.20.2)
- For Node 18+, see upgrade path in [`NODE_COMPATIBILITY.md`](NODE_COMPATIBILITY.md)

## Documentation

- **[MIGRATION.md](MIGRATION.md)** - Complete migration architecture and guide
- **[package.json](package.json)** - Dependencies and scripts

## Development

**Prerequisites:**
- Node.js >= 16 (Node 20 LTS recommended)
- npm >= 8
- Spring Boot backend running on port 8080

**Dev Server:**
```bash
npm run dev
```
Access at `http://localhost:5173`

**Build:**
```bash
npm run build
```
Output in `dist/`

## Project Structure

```
src/
├── routes/          # Page components
├── lib/             # Legacy runtime + API
├── stores/          # State management (Zustand)
├── features/        # Feature modules
└── index.css        # Global styles + design tokens
```

## Key Commands

| Command | Purpose |
|---------|---------|
| `npm run dev` | Start dev server with HMR |
| `npm run build` | Production build |
| `npm run preview` | Preview production build |
| `npm run lint` | TypeScript type checking |

## Environment Variables

Create `.env` file (see `.env.example`):

```env
VITE_API_BASE_URL=/api
```

## Deployment

See [MIGRATION.md](MIGRATION.md#deployment-strategy-parallel-mode) for deployment strategies.

## License

Same as parent LMrouting project.
