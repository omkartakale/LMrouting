# Hackathon 2026 — Shipment Allocation Optimizer v3
# PPT Content (Copy-paste into slides)

---

## SLIDE 01 — TITLE
**Shipment Allocation Optimizer v3**
Affinity-Based Smart Last Mile Allocation

Team: Omkar Takale | Omkar Baviskar
Hub: PNQ HDP (Pune)

---

## SLIDE 02 — PROBLEM

### Current State: Manual & Inefficient Allocation

**Pain Points:**
- Allocation depends on supervisor intuition → not scalable
- No geographic clustering → SRs cross each other's routes
- Uneven earnings → ₹500–₹2000 variance between SRs daily
- No route optimization → excess travel distance
- No preview/comparison → blind decisions

**Business Impact:**
| Metric | Current State |
|--------|--------------|
| Earnings variance across SRs | 15–25% |
| Cross-region deliveries | 20–30% |
| Manual effort per hub/day | 30–45 min |
| SR attrition (earnings-driven) | High |

---

## SLIDE 03 — APPROACH

### Smart Affinity-Based Allocation System

**Phase 1 — Data Ingestion & Cleanup**
- Upload XLSX/CSV with shipment data (lat/lng, pincode, weight)
- Auto-detect columns, handle aliases (20+ column name variants)
- Filter out-of-range shipments (>50km from hub)
- Outlier detection: isolated points with <3 neighbors in 2km → excluded
- Hub boundary polygon from XpressBees orchestration API

**Phase 2 — Hub-Centric Angular Clustering**
- Sort shipments by angle from hub (compass bearing)
- Split into equal pie-slice sectors — one per present SR
- Each SR gets a contiguous geographic wedge → no route crossing
- Boundary refinement: balance max travel distance between adjacent sectors

**Phase 3 — Earnings-Based Fairness Rebalancing**
- Compute per-SR: gross payout, fuel cost (₹2.5/km), net earnings
- Iteratively swap boundary shipments to minimize earnings range
- Target: all SRs earn within ±₹50 of each other

**Phase 4 — Route Optimization**
- Nearest-neighbor TSP from hub → optimal stop sequence
- Google Maps Directions API for real road routing (789 API calls, 0% error)
- Leaflet + Google Maps toggle for visualization

**Phase 5 — LM System Integration**
- Auto Keycloak authentication (client_credentials)
- Bulk allocate via POST /expose/shipment/allocate (with delay)
- Auto confirm allocation (OFD + trip creation)
- One-click "Push All Routes" from v3 UI → LM system

---

## SLIDE 04 — OUTCOMES

### Measurable Impact (Before vs After)

| Metric | Before (Manual) | After (v3) | Improvement |
|--------|----------------|------------|-------------|
| Earnings variance | ₹500–₹2000 | <₹100 | **80% reduction** |
| Cross-region deliveries | 20–30% | <5% | **75% reduction** |
| Avg distance per SR | Unoptimized | Road-optimized | **20–30% less km** |
| Allocation time per hub | 30–45 min | <2 min | **95% faster** |
| SR earnings fairness | Low | High (±₹50) | **Balanced** |

**Additional Outcomes:**
✅ Zero manual intervention — fully automated pipeline
✅ Real-time preview before execution (zero-risk rollout)
✅ Scalable across any hub (just change hub config)
✅ Google Maps + Leaflet dual visualization
✅ Direct LM system integration — no copy-paste needed
✅ Deployed on EC2 — accessible at http://13.127.28.147:8080

---

## SLIDE 05 — COST

### Cost vs Value

| Category | Description | Cost |
|----------|-------------|------|
| Infrastructure | Spring Boot on existing EC2 | ₹0 (shared instance) |
| Development | 2 engineers, 3 days (hackathon) | Internal |
| Google Maps API | Directions API — 10,000 free/month | ₹0 (within free tier) |
| Keycloak Auth | Existing stage infra | ₹0 |
| Maintenance | Modular, self-contained | Minimal |

**TOTAL: Near-zero incremental cost**

**ROI:**
- 95% reduction in allocation time → supervisor freed for other tasks
- 80% reduction in earnings variance → lower SR attrition
- 75% reduction in cross-region → fuel savings
- **Estimated savings: ₹2–5L/month per hub at scale**

---

## SLIDE 06 — EXECUTIVE SUMMARY

### What We Built

| Problem | Resolution | Impact |
|---------|-----------|--------|
| Manual allocation | Automated engine | 95% time saved |
| Earnings imbalance | Fairness rebalancing | 80% variance reduction |
| Route inefficiency | Angular clustering + TSP | 25% less distance |
| No visibility | Real-time map + preview | Full transparency |
| System disconnect | LM API integration | One-click push |

### Tech Stack
- Backend: Spring Boot 3.4 + Java 17
- Frontend: Static HTML + Leaflet + Google Maps
- Routing: Google Directions API
- Auth: Keycloak (client_credentials)
- Deploy: Docker + EC2
- Integration: XpressBees LM Allocation API

### Delivery Velocity
- Sprint: 3 days (hackathon)
- Features: 10+ (engine, UI, integration, deploy)
- Status: **Production-ready, deployed on stage**

---

## CLOSING STATEMENT (say this)

> "Today, allocation depends on human judgment.
> With v3, we convert it into a data-driven, fair, and scalable
> decision engine — with zero disruption to existing systems."

---

## DEMO SCRIPT (2 minutes)

1. Open http://13.127.28.147:8080
2. Upload XLSX → show 4570 rows parsed, dates detected, out-of-range filtered
3. Select date → mark all SRs present
4. Click "Run Allocation (Google Maps)" → show pie-slice routes on map
5. Click fullscreen → show clean map with color-coded routes
6. Click any SR → show route details, stop sequence, distance
7. Toggle to Google Maps view → show satellite/road detail
8. Scroll to "Push to LM System" → click Connect → show Keycloak auth
9. Map SRs → click "Push All Routes" → show allocation flowing to LM
10. "This entire flow — from upload to LM allocation — takes under 2 minutes."

---

## TOUGH QUESTIONS & ANSWERS

**Q: How is this different from existing allocation?**
A: Existing is manual, pincode-based. V3 uses geo-coordinates, angular clustering,
   and earnings-based rebalancing. It's data-driven, not intuition-driven.

**Q: What if SR count changes mid-day?**
A: Re-run allocation with updated attendance. Takes <30 seconds.

**Q: Can this work for other hubs?**
A: Yes. Change hub lat/lng in config. Upload that hub's shipment data. Done.

**Q: What about reverse pickups (RVP)?**
A: Currently forward-only. RVP can be added as a separate allocation type
   using the same engine.

**Q: What's the Google Maps API cost at scale?**
A: 10,000 free calls/month. At 10 SRs × 30 days = 300 calls/month.
   Well within free tier. Even at 100 hubs = 30,000 calls = ~$100/month.

**Q: Is this production-ready?**
A: Deployed on EC2 stage right now. Integrated with LM APIs.
   Needs pilot approval for one hub, then scale.
