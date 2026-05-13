package com.example.LMrouting.service;

import com.example.LMrouting.dto.*;
import com.example.LMrouting.exception.AllocationNotFoundException;
import com.example.LMrouting.exception.NoPresentSrsException;
import com.example.LMrouting.model.AllocationRun;
import com.example.LMrouting.model.AllocationStatus;
import com.example.LMrouting.model.Priority;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the full shipment allocation pipeline.
 *
 * <h2>Fairness objective</h2>
 * The primary goal is to minimise the <em>range</em> of net earnings across SRs:
 * <pre>
 *   netEarnings(sr) = Σ expectedPayout(shipment) − 2.5 × routeDistanceKm
 *   objective       = minimise  max(netEarnings) − min(netEarnings)
 * </pre>
 *
 * <h2>Geographic integrity constraint</h2>
 * Each SR owns a compact geographic territory produced by K-Means clustering.
 * Rebalancing only moves <em>boundary</em> shipments — those closest to the
 * border between the overloaded and underloaded SR's territory — so that no SR
 * crosses deep into another SR's area.
 *
 * <h2>Pipeline phases</h2>
 * <ol>
 *   <li>K-Means geographic clustering (territory assignment)</li>
 *   <li>Net-earnings rebalancing (boundary shipment transfers)</li>
 *   <li>Forward/Reverse co-location</li>
 *   <li>Heavy shipment balancing</li>
 *   <li>Route sequencing</li>
 *   <li>Persist</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AllocationEngineService {

    private final InMemoryStore store;
    private final RouteOptimizerService routeOptimizerService;
    private final ScoreWeights scoreWeights;
    private final HubBoundaryService hubBoundaryService;
    private final PincodeBoundaryService pincodeBoundaryService;
    private final AffinityShiftAllocationService affinityShiftAllocationService;

    @Value("${hub.name:PNQ HDP}")
    private String hubName;

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    /**
     * Rebalancing stops when no single boundary transfer further reduces the
     * earnings range (local optimum), or when maxIterations is reached.
     * There is no hard earnings-range cap — the objective is to minimise
     * variance as much as possible, not to stop at an arbitrary threshold.
     * This value is kept only as a micro-optimisation: skip rebalancing
     * entirely if the range is already negligibly small (< ₹1).
     */
    @Value("${allocation.rebalancing.threshold:1.0}")
    private double earningsRangeThreshold;

    @Value("${allocation.rebalancing.maxIterations:200}")
    private int maxIterations;

    @Value("${allocation.sr.capacity.min:80}")
    private int srCapacityMin;

    @Value("${allocation.sr.capacity.max:80}")
    private int srCapacityMax;

    /**
     * Fallback straight-line distance used ONLY when the hub boundary polygon
     * cannot be fetched from the external API. The primary boundary check uses
     * the actual polygon coordinates from /api/hub/boundary.
     *
     * Default: 25 km — covers all of Pune city comfortably.
     * Configurable via allocation.boundary.km in application.properties.
     */
    @Value("${allocation.boundary.km:25.0}")
    private double allocationBoundaryKmFallback;

    // =========================================================================
    // Public API
    // =========================================================================

    @org.springframework.transaction.annotation.Transactional
    public AllocationSummary allocate(LocalDate date) {
        return allocate(date, new AllocateRequest(date.toString(), null));
    }

    @org.springframework.transaction.annotation.Transactional
    public AllocationSummary allocate(LocalDate date, AllocateRequest request) {
        // ── Mode branch: delegate to time-based pipeline if requested ─────────
        if (request != null && "time-based".equals(request.allocationMode())) {
            return affinityShiftAllocationService.allocate(date, request);
        }

        String dateStr = formatDate(date);
        log.info("AllocationEngineService: starting allocation for '{}'", dateStr);

        List<Shipment> allShipments = store.findShipmentsByDate(dateStr);
        if (allShipments.isEmpty()) {
            throw new AllocationNotFoundException(
                    "No shipment data found for " + dateStr + ". Please upload a CSV file first.");
        }

        // ── Filter 1: valid coordinates ───────────────────────────────────────
        // Exclude shipments with zero/missing coordinates — genuinely undeliverable.
        // We do NOT filter on isOutOfRange() here; that flag is set at ingestion time
        // and must not be re-evaluated or mutated during allocation.
        List<Shipment> withCoords = allShipments.stream()
                .filter(s -> s.getDropLatitude() != 0 && s.getDropLongitude() != 0)
                .collect(Collectors.toList());

        // ── Filter 2: hub boundary (polygon-based, cached for this allocation run) ──
        // Fetch the hub service boundary polygon once at the start of the run.
        // The polygon is reused for all shipment tests — do NOT re-fetch per shipment.
        //
        // Any shipment whose drop coordinates fall outside this polygon is an
        // outlier (wrong coordinates, different city, data error) and must be
        // excluded from routing — it would cause enormous route distances and
        // deeply negative net earnings.
        //
        // Fallback: if the API is unavailable, fall back to a straight-line
        // radius check using allocationBoundaryKmFallback (default 25 km).
        //
        // The polygon check uses the ray-casting algorithm (point-in-polygon).
        // This is a pure read — no stored Shipment objects are mutated.
        List<double[]> boundaryPolygon = null;
        try {
            HubBoundaryResponse boundaryResp = hubBoundaryService.fetchBoundary(hubName);
            if (boundaryResp != null && boundaryResp.coordinates() != null
                    && !boundaryResp.coordinates().isEmpty()) {
                boundaryPolygon = boundaryResp.coordinates();
                log.info("Boundary filter: using polygon with {} vertices for hub '{}'",
                        boundaryPolygon.size(), hubName);
            } else {
                log.warn("Hub boundary polygon unavailable, falling back to radius filter");
            }
        } catch (Exception e) {
            log.warn("Hub boundary polygon unavailable, falling back to radius filter");
            log.debug("Hub boundary fetch error detail: {}", e.getMessage());
        }

        final List<double[]> polygon = boundaryPolygon; // effectively final for lambda
        List<Shipment> withinBoundary;
        int outsideBoundary;
        if (polygon != null) {
            withinBoundary = withCoords.stream()
                    .filter(s -> isPointInPolygon(s.getDropLatitude(), s.getDropLongitude(), polygon))
                    .collect(Collectors.toList());
            outsideBoundary = withCoords.size() - withinBoundary.size();
            log.info("Boundary filter (polygon): excluded {} shipments outside hub service area",
                    outsideBoundary);
        } else {
            // Fallback: straight-line radius check
            withinBoundary = withCoords.stream()
                    .filter(s -> {
                        double dist = s.getDistanceFromHubKm();
                        if (dist == 0.0) {
                            dist = haversine(hubLat, hubLng, s.getDropLatitude(), s.getDropLongitude());
                        }
                        return dist <= allocationBoundaryKmFallback;
                    })
                    .collect(Collectors.toList());
            outsideBoundary = withCoords.size() - withinBoundary.size();
            log.info("Boundary filter (radius fallback): excluded {} shipments beyond {}km",
                    outsideBoundary, allocationBoundaryKmFallback);
        }

        // ── Filter 3: pincode boundary (GeoJSON polygon-based) ────────────────
        // If pincode boundary data is loaded, additionally filter shipments
        // that fall outside any known serviceable pincode polygon.
        // This catches shipments with valid coordinates but wrong pincode area.
        if (pincodeBoundaryService.isLoaded()) {
            int beforePincodeFilter = withinBoundary.size();
            withinBoundary = withinBoundary.stream()
                    .filter(s -> pincodeBoundaryService.isInsideServiceArea(
                            s.getDropLatitude(), s.getDropLongitude()))
                    .collect(Collectors.toList());
            int excludedByPincode = beforePincodeFilter - withinBoundary.size();
            if (excludedByPincode > 0) {
                log.info("Pincode boundary filter: excluded {} shipments outside known pincode areas",
                        excludedByPincode);
            }
        }

        List<String> presentSrs = store.getPresentSrNames(date);
        if (presentSrs.isEmpty()) {
            throw new NoPresentSrsException(
                    "At least one SR must be marked present before running allocation.");
        }

        log.info("AllocationEngineService: {} within-boundary shipments (from {} total, {} zero-coords, {} outside boundary), {} present SRs",
                withinBoundary.size(), allShipments.size(),
                allShipments.size() - withCoords.size(),
                outsideBoundary,
                presentSrs.size());

        // ── Capacity cap with effective-payout-sorted selection ───────────────
        // Total capacity scales with the number of present SRs.
        // If 10 SRs are present, capacity = 10 × 80 = 800.
        // If 3 SRs are present, capacity = 3 × 80 = 240.
        //
        // SELECTION RULE — sort by effectivePayout descending only, with
        // shippingId ascending as a deterministic stable tiebreaker.
        //
        //   effectivePayout = expectedPayout × priorityFactor
        //                       (P0=1.00, P1=0.75, P2=0.50)
        //
        // This naturally enforces "1 P0 should NOT be sacrificed for 4 P2 unless
        // those 4 P2s genuinely earn more in total":
        //   1 P0 with payout ₹X  → effective ₹X × 1.00 = ₹X
        //   4 P2 with payout ₹X  → effective 4 × ₹X × 0.50 = ₹2X
        // So a P0 only loses its slot when the displacing P2s' combined
        // effective payout truly exceeds the P0's effective payout — which
        // is exactly when keeping them improves total earnings.
        //
        // We do NOT sort by priority tier first; that would let a low-payout
        // P0 displace any number of high-payout P2s, hurting overall earnings
        // and route distance, which is not the desired trade-off.
        int totalCapacity = presentSrs.size() * srCapacityMax;
        List<Shipment> toAllocate;
        List<Shipment> unallocated;
        if (withinBoundary.size() > totalCapacity) {
            log.warn("Capacity exceeded: {} shipments > {} capacity ({} SRs × {}). {} will be unallocated.",
                    withinBoundary.size(), totalCapacity, presentSrs.size(), srCapacityMax,
                    withinBoundary.size() - totalCapacity);
            List<Shipment> sorted = withinBoundary.stream()
                    .sorted(Comparator
                            .comparingDouble(Shipment::effectivePayout).reversed()
                            .thenComparing(Shipment::getShippingId))
                    .collect(Collectors.toList());
            toAllocate  = new ArrayList<>(sorted.subList(0, totalCapacity));
            unallocated = new ArrayList<>(sorted.subList(totalCapacity, sorted.size()));
        } else {
            toAllocate  = new ArrayList<>(withinBoundary);
            unallocated = Collections.emptyList();
        }

        // ── Split Forward / Reverse ───────────────────────────────────────────
        List<Shipment> forward = toAllocate.stream()
                .filter(s -> "Forward".equalsIgnoreCase(s.getShipmentFlow()))
                .collect(Collectors.toList());
        List<Shipment> reverse = toAllocate.stream()
                .filter(s -> !"Forward".equalsIgnoreCase(s.getShipmentFlow()))
                .collect(Collectors.toList());

        // ── Phase 1: K-Means geographic clustering ────────────────────────────
        Map<String, List<Shipment>> assignment = kMeansCluster(forward, presentSrs);

        // ── Phase 2: Net-earnings rebalancing ─────────────────────────────────
        assignment = rebalanceByNetEarnings(assignment);

        // ── Phase 3: Forward/Reverse co-location ──────────────────────────────
        assignment = coLocateReverse(reverse, assignment);

        // ── Phase 4: Heavy shipment balancing ─────────────────────────────────
        assignment = balanceHeavy(assignment);

        // ── Phase 5: Route sequencing ─────────────────────────────────────────
        Map<String, List<Shipment>> ordered = new LinkedHashMap<>();
        Map<String, Double> distancesBySr   = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> e : assignment.entrySet()) {
            String sr = e.getKey();
            List<Shipment> srShipments = e.getValue();
            List<Shipment> seq = srShipments.size() >= 2
                    ? routeOptimizerService.optimizeRoute(sr, srShipments)
                    : new ArrayList<>(srShipments);
            if (seq.size() == 1) seq.get(0).setRouteSequence(1);
            ordered.put(sr, seq);
            distancesBySr.put(sr, routeOptimizerService.estimateDistanceKm(seq));
        }

        // ── Phase 6: Persist ──────────────────────────────────────────────────
        // Build a set of all allocated shipping IDs for fast lookup.
        Set<String> allocatedIds = new HashSet<>();
        for (Map.Entry<String, List<Shipment>> e : ordered.entrySet()) {
            e.getValue().forEach(s -> {
                s.setAssignedSr(e.getKey());
                allocatedIds.add(s.getShippingId());
            });
        }

        // Collect all allocated shipments in a map keyed by shippingId so we can
        // merge their updated state back into the full dataset.
        Map<String, Shipment> allocatedById = new LinkedHashMap<>();
        for (List<Shipment> list : ordered.values()) {
            for (Shipment s : list) allocatedById.put(s.getShippingId(), s);
        }

        // Rebuild the full shipment list for this date:
        //  - Allocated shipments get their updated assignedSr + routeSequence.
        //  - Previously-allocated shipments that are NOT in this run's allocation
        //    (e.g. capacity overflow, boundary exclusions on re-run) get cleared.
        //  - Brand-new unallocated shipments (zero coords, outside boundary, overflow)
        //    are preserved as-is with no assignedSr.
        List<Shipment> fullList = allShipments.stream().map(s -> {
            Shipment updated = allocatedById.get(s.getShippingId());
            if (updated != null) {
                return updated; // use the freshly assigned version
            }
            // Not allocated in this run — clear any stale assignment from a previous run
            s.setAssignedSr(null);
            s.setRouteSequence(0);
            return s;
        }).collect(Collectors.toList());

        store.saveShipments(dateStr, fullList);

        // ── Compute final earnings metrics ────────────────────────────────────
        Map<String, Double> earningsBySr = computeNetEarningsMap(ordered, distancesBySr);
        double earningsVar   = CompositeLoadScoreCalculator.earningsVariance(earningsBySr);
        double earningsRange = CompositeLoadScoreCalculator.earningsRange(earningsBySr);
        double meanEarnings  = CompositeLoadScoreCalculator.meanEarnings(earningsBySr);

        // Legacy composite score variance (kept for API compatibility)
        Map<String, Double> scoresBySr = new LinkedHashMap<>();
        ordered.forEach((sr, list) -> scoresBySr.put(sr,
                CompositeLoadScoreCalculator.compute(list, distancesBySr.getOrDefault(sr, 0.0), scoreWeights)));
        double legacyVariance = CompositeLoadScoreCalculator.variance(scoresBySr);

        log.info("Allocation complete — earningsRange=₹{}, earningsVariance=₹²{}, " +
                        "meanNetEarnings=₹{}, allocated={}, unallocated={}",
                String.format("%.2f", earningsRange), String.format("%.2f", earningsVar),
                String.format("%.2f", meanEarnings), allocatedById.size(), unallocated.size());

        AllocationRun run = AllocationRun.builder()
                .allocationDate(date)
                .status(AllocationStatus.COMPLETED)
                .totalShipments(allShipments.size())
                .totalSrs(presentSrs.size())
                .fairnessVariance(earningsVar)
                .earningsRange(earningsRange)
                .createdAt(LocalDateTime.now())
                .build();

        // Re-run warning: if a previous run exists for this date and produced a different
        // earnings range, log a WARN — this indicates a non-determinism bug.
        store.findAllocationRun(date).ifPresent(prev -> {
            if (Math.abs(prev.getEarningsRange() - earningsRange) > 0.001) {
                log.warn("Non-determinism detected: re-run for {} produced earningsRange=₹{}, previous was ₹{}",
                        dateStr,
                        String.format("%.2f", earningsRange),
                        String.format("%.2f", prev.getEarningsRange()));
            }
        });

        store.saveAllocationRun(run);

        PriorityCountsDto priorityCounts = computePriorityCounts(allShipments, allocatedIds);

        return buildSummary(dateStr, allShipments.size(), presentSrs.size(),
                legacyVariance, earningsVar, earningsRange, meanEarnings,
                ordered, distancesBySr, earningsBySr,
                // Total unallocated = capacity overflow + outside boundary + zero/missing coords
                unallocated.size() + outsideBoundary + (allShipments.size() - withCoords.size()),
                priorityCounts);
    }

    public AllocationSummary getSummary(LocalDate date) {
        String dateStr = formatDate(date);
        AllocationRun run = store.findAllocationRun(date)
                .orElseThrow(() -> new AllocationNotFoundException("No allocation found for " + dateStr));

        List<Shipment> all = store.findShipmentsByDate(dateStr);
        if (all.isEmpty()) throw new AllocationNotFoundException("No shipment data found for " + dateStr);

        Map<String, List<Shipment>> bySr = all.stream()
                .filter(s -> s.getAssignedSr() != null)
                .collect(Collectors.groupingBy(Shipment::getAssignedSr, LinkedHashMap::new, Collectors.toList()));

        Map<String, Double> distancesBySr = new LinkedHashMap<>();
        bySr.forEach((sr, list) -> {
            List<Shipment> sorted = list.stream()
                    .sorted(Comparator.comparingInt(Shipment::getRouteSequence))
                    .collect(Collectors.toList());
            distancesBySr.put(sr, routeOptimizerService.estimateDistanceKm(sorted));
        });

        Map<String, Double> earningsBySr = computeNetEarningsMap(bySr, distancesBySr);
        double earningsVar   = CompositeLoadScoreCalculator.earningsVariance(earningsBySr);
        double earningsRange = CompositeLoadScoreCalculator.earningsRange(earningsBySr);
        double meanEarnings  = CompositeLoadScoreCalculator.meanEarnings(earningsBySr);

        Map<String, Double> scoresBySr = new LinkedHashMap<>();
        bySr.forEach((sr, list) -> scoresBySr.put(sr,
                CompositeLoadScoreCalculator.compute(list, distancesBySr.getOrDefault(sr, 0.0), scoreWeights)));
        double legacyVariance = CompositeLoadScoreCalculator.variance(scoresBySr);

        // Re-derive priority counts from the persisted shipment list.
        Set<String> allocatedIds = bySr.values().stream()
                .flatMap(List::stream)
                .map(Shipment::getShippingId)
                .collect(Collectors.toSet());
        PriorityCountsDto priorityCounts = computePriorityCounts(all, allocatedIds);

        return buildSummary(dateStr, run.getTotalShipments(), run.getTotalSrs(),
                legacyVariance, earningsVar, earningsRange, meanEarnings,
                bySr, distancesBySr, earningsBySr, 0, priorityCounts);
    }

    public List<double[]> getRoutePolyline(LocalDate date, String srName) {
        return routeOptimizerService.getRoutePolyline(
                store.findShipmentsByDateAndSr(formatDate(date), srName));
    }

    public List<double[]> getOrsPolyline(LocalDate date, String srName) {
        return routeOptimizerService.getOrsPolyline(
                store.findShipmentsByDateAndSr(formatDate(date), srName));
    }

    public List<double[]> getGoogleMapsPolyline(LocalDate date, String srName) {
        return routeOptimizerService.getGoogleMapsPolyline(
                store.findShipmentsByDateAndSr(formatDate(date), srName));
    }

    public SrRouteDto getSrRoute(LocalDate date, String srName) {
        String dateStr = formatDate(date);
        List<Shipment> shipments = store.findShipmentsByDateAndSr(dateStr, srName);
        if (shipments.isEmpty())
            throw new AllocationNotFoundException(
                    "No shipments found for SR '" + srName + "' on " + dateStr);

        double distKm = routeOptimizerService.estimateDistanceKm(shipments);
        List<ShipmentStopDto> stops = shipments.stream()
                .map(s -> new ShipmentStopDto(
                        s.getRouteSequence(), s.getShippingId(), s.getDropPincode(),
                        s.getDropLatitude(), s.getDropLongitude(), s.getOrderType(),
                        s.getPhyWeight(), s.getIsHeavy() == 1, s.getShipmentFlow(),
                        s.isOverride(), s.isOutOfRange()))
                .collect(Collectors.toList());

        return new SrRouteDto(srName, dateStr, shipments.size(), distKm, stops);
    }

    // =========================================================================
    // Phase 1 — K-Means geographic clustering
    // =========================================================================

    /**
     * Assigns each Forward shipment to one of k geographic clusters using K-Means.
     *
     * <p>Initialisation: centroids are seeded from evenly-spaced angular positions
     * around the hub (not random), which guarantees that each SR starts with a
     * distinct pie-slice of the city. This prevents the degenerate case where
     * multiple centroids collapse to the same dense area.
     *
     * <p>After convergence, a size-balancing pass moves boundary points from
     * oversized clusters to undersized ones, keeping cluster sizes within ±20%
     * of the target (n/k). This is the geographic integrity constraint: only
     * boundary points (those closest to the receiving cluster's centroid) are
     * moved, so the spatial compactness of each territory is preserved.
     */
    Map<String, List<Shipment>> kMeansCluster(List<Shipment> forward, List<String> srNames) {
        int k = srNames.size();
        Map<String, List<Shipment>> assignment = new LinkedHashMap<>();
        for (String sr : srNames) assignment.put(sr, new ArrayList<>());
        if (forward.isEmpty()) return assignment;

        int n = forward.size();

        // ── Seed centroids at evenly-spaced angles around the hub ─────────────
        // Sort shipments by bearing angle, then shippingId as a stable tiebreaker
        // for shipments at equal angles — guarantees deterministic centroid seeding.
        List<Shipment> byAngle = forward.stream()
                .sorted(Comparator.comparingDouble((Shipment s) ->
                        Math.atan2(s.getDropLongitude() - hubLng, s.getDropLatitude() - hubLat))
                        .thenComparing(Shipment::getShippingId))
                .collect(Collectors.toList());

        double[][] centroids = new double[k][2];
        for (int i = 0; i < k; i++) {
            int idx = (int) ((long) i * n / k);
            centroids[i][0] = byAngle.get(idx).getDropLatitude();
            centroids[i][1] = byAngle.get(idx).getDropLongitude();
        }

        // ── K-Means iterations with route-compactness awareness ──────────────────
        // Standard K-Means assigns each shipment to the nearest centroid.
        // To reduce route overlap, we add a small penalty for shipments that would
        // cause an SR to "reach across" another SR's territory.
        int[] labels = new int[n];
        for (int iter = 0; iter < 50; iter++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                Shipment s = forward.get(i);
                double minD = Double.MAX_VALUE;
                int best = 0;
                for (int c = 0; c < k; c++) {
                    double d = haversine(s.getDropLatitude(), s.getDropLongitude(),
                            centroids[c][0], centroids[c][1]);
                    
                    // Add a small penalty if this shipment is on the "wrong side" of the hub
                    // relative to the centroid (would cause route crossing).
                    // Compute the angle from hub to shipment and hub to centroid.
                    double angleToShipment = Math.atan2(s.getDropLongitude() - hubLng, 
                                                        s.getDropLatitude() - hubLat);
                    double angleToCentroid = Math.atan2(centroids[c][1] - hubLng, 
                                                        centroids[c][0] - hubLat);
                    double angleDiff = Math.abs(angleToShipment - angleToCentroid);
                    if (angleDiff > Math.PI) angleDiff = 2 * Math.PI - angleDiff;
                    
                    // If the angle difference is > 90°, this shipment is on the opposite side
                    // of the hub from the centroid — add a penalty to discourage assignment.
                    if (angleDiff > Math.PI / 2) {
                        d *= 1.5; // 50% penalty for cross-hub assignments
                    }
                    
                    if (d < minD) { minD = d; best = c; }
                }
                if (labels[i] != best) { labels[i] = best; changed = true; }
            }
            if (!changed) break;

            // Recompute centroids
            for (int c = 0; c < k; c++) {
                double sumLat = 0, sumLng = 0;
                int cnt = 0;
                for (int i = 0; i < n; i++) {
                    if (labels[i] == c) {
                        sumLat += forward.get(i).getDropLatitude();
                        sumLng += forward.get(i).getDropLongitude();
                        cnt++;
                    }
                }
                if (cnt > 0) { centroids[c][0] = sumLat / cnt; centroids[c][1] = sumLng / cnt; }
            }
        }

        // ── Size-balancing: move boundary points to keep clusters within ±20% ─
        List<List<Integer>> clusters = new ArrayList<>();
        for (int c = 0; c < k; c++) clusters.add(new ArrayList<>());
        for (int i = 0; i < n; i++) clusters.get(labels[i]).add(i);

        int targetSize = n / k;
        int maxSize    = (int) (targetSize * 1.2) + 1;
        // Hard floor: every cluster must end up with at least 50% of target,
        // so no SR is left with zero (or near-zero) shipments. This protects
        // against the K-Means starvation case where two seed centroids land
        // in dead zones and never attract any points.
        int minSize    = Math.max(1, targetSize / 2);

        // Downsize-only pass — sheds points from clusters > maxSize.
        for (int pass = 0; pass < 20; pass++) {
            boolean moved = false;
            for (int c = 0; c < k; c++) {
                while (clusters.get(c).size() > maxSize) {
                    // Find the smallest cluster
                    int smallest = -1, smallestSz = Integer.MAX_VALUE;
                    for (int j = 0; j < k; j++) {
                        if (j != c && clusters.get(j).size() < smallestSz) {
                            smallestSz = clusters.get(j).size(); smallest = j;
                        }
                    }
                    if (smallest < 0 || smallestSz >= maxSize) break;

                    // Move the boundary point closest to the receiving cluster's centroid
                    double bestD = Double.MAX_VALUE;
                    int bestIdx = -1;
                    for (int idx : clusters.get(c)) {
                        Shipment s = forward.get(idx);
                        double d = haversine(s.getDropLatitude(), s.getDropLongitude(),
                                centroids[smallest][0], centroids[smallest][1]);
                        if (d < bestD) { bestD = d; bestIdx = idx; }
                    }
                    if (bestIdx >= 0) {
                        clusters.get(c).remove(Integer.valueOf(bestIdx));
                        clusters.get(smallest).add(bestIdx);
                        labels[bestIdx] = smallest;
                        // Refresh receiving cluster's centroid so subsequent
                        // boundary picks reflect its current shape.
                        centroids[smallest] = computeCentroid(clusters.get(smallest), forward);
                        moved = true;
                    }
                }
            }
            if (!moved) break;
        }

        // Upsize pass — guarantees every cluster has at least minSize. Without
        // this, a cluster that the K-Means iterations starved (size 0) stays
        // at 0 forever because the downsize-only pass only fires when some
        // cluster exceeds maxSize. We pull from the *largest* cluster (only if
        // it still has more than minSize to give) so we never re-starve a
        // donor. The chosen point is the one closest to the recipient's
        // centroid — same "boundary point" rule as the downsize pass.
        for (int pass = 0; pass < 20; pass++) {
            boolean moved = false;
            for (int c = 0; c < k; c++) {
                while (clusters.get(c).size() < minSize) {
                    // Refresh recipient centroid (it may currently sit on stale
                    // seed coordinates if the cluster started empty).
                    if (!clusters.get(c).isEmpty()) {
                        centroids[c] = computeCentroid(clusters.get(c), forward);
                    }
                    int largest = -1, largestSz = -1;
                    for (int j = 0; j < k; j++) {
                        if (j != c && clusters.get(j).size() > largestSz) {
                            largestSz = clusters.get(j).size(); largest = j;
                        }
                    }
                    // Stop if even the largest donor is at/under minSize —
                    // any further transfer would re-starve someone.
                    if (largest < 0 || largestSz <= minSize) break;

                    double bestD = Double.MAX_VALUE;
                    int bestIdx = -1;
                    for (int idx : clusters.get(largest)) {
                        Shipment s = forward.get(idx);
                        double d = haversine(s.getDropLatitude(), s.getDropLongitude(),
                                centroids[c][0], centroids[c][1]);
                        if (d < bestD) { bestD = d; bestIdx = idx; }
                    }
                    if (bestIdx >= 0) {
                        clusters.get(largest).remove(Integer.valueOf(bestIdx));
                        clusters.get(c).add(bestIdx);
                        labels[bestIdx] = c;
                        moved = true;
                    } else {
                        break;
                    }
                }
            }
            if (!moved) break;
        }

        // ── Assign to SR names ────────────────────────────────────────────────
        for (int c = 0; c < k; c++) {
            String sr = srNames.get(c);
            for (int idx : clusters.get(c)) assignment.get(sr).add(forward.get(idx));
        }

        // Per-cluster summary for the operator: shows the distribution range
        // and flags any cluster outside [minSize..maxSize] (none expected).
        int actualMin = Integer.MAX_VALUE, actualMax = 0;
        for (List<Integer> cl : clusters) {
            actualMin = Math.min(actualMin, cl.size());
            actualMax = Math.max(actualMax, cl.size());
        }
        log.info("Phase 1 (K-Means): {} shipments → {} clusters " +
                "(target={}, min={}, max={}, actual range=[{}..{}])",
                n, k, targetSize, minSize, maxSize, actualMin, actualMax);
        return assignment;
    }

    /** Helper used by the size-balancing passes to refresh a cluster centroid. */
    private double[] computeCentroid(List<Integer> indices, List<Shipment> forward) {
        if (indices == null || indices.isEmpty()) return new double[]{hubLat, hubLng};
        double sumLat = 0, sumLng = 0;
        for (int idx : indices) {
            sumLat += forward.get(idx).getDropLatitude();
            sumLng += forward.get(idx).getDropLongitude();
        }
        return new double[]{sumLat / indices.size(), sumLng / indices.size()};
    }

    // =========================================================================
    // Phase 2 — Net-earnings rebalancing
    // =========================================================================

    /**
     * Iteratively transfers boundary shipments between SRs to minimise the
     * range of net earnings (max − min).
     *
     * <h3>Why net earnings, not shipment count?</h3>
     * Shipments have different {@code expectedPayout} values (₹3.88–₹16.77 in
     * the real data). An SR with 20 high-payout shipments in a compact area
     * earns far more than an SR with 25 low-payout shipments spread over a
     * long route. Balancing on shipment count alone would leave large earnings
     * gaps. Balancing on net earnings (payout minus fuel cost) directly targets
     * the fairness objective.
     *
     * <h3>Geographic integrity</h3>
     * Only <em>boundary</em> shipments are eligible for transfer — those in the
     * overloaded SR's cluster that are geographically closest to the underloaded
     * SR's centroid. This ensures that transferred shipments come from the
     * border between the two territories, not from the interior, so each SR's
     * area remains spatially compact.
     *
     * <h3>Convergence</h3>
     * The loop stops when:
     * <ul>
     *   <li>The earnings range drops below {@code earningsRangeThreshold} (₹5 default), or</li>
     *   <li>No single transfer reduces the range (local optimum), or</li>
     *   <li>{@code maxIterations} is reached.</li>
     * </ul>
     */
    Map<String, List<Shipment>> rebalanceByNetEarnings(Map<String, List<Shipment>> assignment) {
        if (assignment.size() < 2) return assignment;

        // Pre-compute distances for all SRs
        Map<String, Double> distances = computeDistances(assignment);
        Map<String, Double> earnings  = computeNetEarningsMap(assignment, distances);

        double range = CompositeLoadScoreCalculator.earningsRange(earnings);
        double mean  = CompositeLoadScoreCalculator.meanEarnings(earnings);
        int iterations = 0;

        log.debug("Phase 2 start: earningsRange=₹{}", String.format("%.2f", range));

        while (range > earningsRangeThreshold && iterations < maxIterations) {
            String richSr = maxEarningsSr(earnings);
            String poorSr = minEarningsSr(earnings);
            if (richSr.equals(poorSr)) break;

            // Find the best boundary shipment to transfer from richSr → poorSr.
            // "Boundary" = shipments in richSr closest to poorSr's centroid.
            // We simulate each candidate transfer and pick the one that most
            // reduces the earnings range, subject to the do-not-over-equalize guard.
            Shipment best = findBestTransfer(richSr, poorSr, assignment, distances, earnings, mean);
            if (best == null) break; // no beneficial transfer exists (or all would over-equalize)

            // Execute the transfer
            assignment.get(richSr).remove(best);
            assignment.get(poorSr).add(best);

            // Recompute only the two affected SRs
            distances.put(richSr, routeOptimizerService.estimateDistanceKm(assignment.get(richSr)));
            distances.put(poorSr, routeOptimizerService.estimateDistanceKm(assignment.get(poorSr)));
            earnings.put(richSr, CompositeLoadScoreCalculator.netEarnings(
                    assignment.get(richSr), distances.get(richSr)));
            earnings.put(poorSr, CompositeLoadScoreCalculator.netEarnings(
                    assignment.get(poorSr), distances.get(poorSr)));

            range = CompositeLoadScoreCalculator.earningsRange(earnings);
            mean  = CompositeLoadScoreCalculator.meanEarnings(earnings);
            iterations++;
        }

        double finalMeanDev = CompositeLoadScoreCalculator.meanEarningsDeviation(earnings);
        log.info("Phase 2 (Net-earnings rebalancing): {} iterations, final earningsRange=₹{}, meanEarningsDeviation=₹{}",
                iterations, String.format("%.2f", range), String.format("%.2f", finalMeanDev));
        return assignment;
    }

    /**
     * Find the single shipment in {@code richSr}'s cluster whose transfer to
     * {@code poorSr} most reduces the earnings range, subject to the constraint
     * that the transfer must actually reduce the range (not worsen it).
     *
     * <p>To preserve geographic integrity, candidates are ranked by their
     * distance to {@code poorSr}'s centroid (ascending) and only the closest
     * {@code BOUNDARY_CANDIDATE_LIMIT} are evaluated. This limits the search
     * to genuine boundary shipments.
     */
    private static final int BOUNDARY_CANDIDATE_LIMIT = 15;

    /**
     * Maximum allowed distance (km) between a transfer candidate and the
     * receiving SR's centroid. This is the geographic-integrity guard for the
     * fairness rebalancer: even the most beneficial earnings transfer is
     * rejected if the shipment is too far from the poor SR's territory.
     */
    private static final double REBALANCE_TRANSFER_MAX_DISTANCE_KM = 5.0;

    private Shipment findBestTransfer(String richSr, String poorSr,
                                      Map<String, List<Shipment>> assignment,
                                      Map<String, Double> distances,
                                      Map<String, Double> earnings,
                                      double mean) {
        List<Shipment> richShipments = assignment.get(richSr);
        if (richShipments.isEmpty()) return null;

        // Must leave at least 1 shipment in richSr
        if (richShipments.size() <= 1) return null;

        double[] poorCentroid = centroid(assignment.get(poorSr));

        // If the receiving SR is empty or severely under-loaded, its centroid
        // is meaningless (it's the hub fallback) — the distance cap must be
        // bypassed for those cases or the SR will starve forever.
        int poorSize = assignment.get(poorSr).size();
        boolean bypassDistanceCap = poorSize < 5;

        // Sort by distance to poorSr centroid — boundary candidates first.
        // thenComparing(shippingId) is a stable tiebreaker for equidistant shipments.
        // Distance filter: only consider candidates within
        // REBALANCE_TRANSFER_MAX_DISTANCE_KM of the receiving SR's centroid,
        // EXCEPT when the receiving SR is starved (poorSize < 5) — those
        // transfers must be allowed regardless of distance to fill the gap.
        List<Shipment> candidates = richShipments.stream()
                .filter(s -> bypassDistanceCap
                        || haversine(s.getDropLatitude(), s.getDropLongitude(),
                                poorCentroid[0], poorCentroid[1])
                                <= REBALANCE_TRANSFER_MAX_DISTANCE_KM)
                .sorted(Comparator.comparingDouble((Shipment s) ->
                        haversine(s.getDropLatitude(), s.getDropLongitude(),
                                poorCentroid[0], poorCentroid[1]))
                        .thenComparing(Shipment::getShippingId))
                .limit(BOUNDARY_CANDIDATE_LIMIT)
                .collect(Collectors.toList());

        double currentRange = CompositeLoadScoreCalculator.earningsRange(earnings);
        double bestImprovement = 0.0;
        Shipment bestShipment  = null;

        for (Shipment candidate : candidates) {
            // Simulate transfer
            List<Shipment> newRich = new ArrayList<>(richShipments);
            newRich.remove(candidate);
            List<Shipment> newPoor = new ArrayList<>(assignment.get(poorSr));
            newPoor.add(candidate);

            double newRichDist = routeOptimizerService.estimateDistanceKm(newRich);
            double newPoorDist = routeOptimizerService.estimateDistanceKm(newPoor);

            Map<String, Double> simEarnings = new LinkedHashMap<>(earnings);
            simEarnings.put(richSr, CompositeLoadScoreCalculator.netEarnings(newRich, newRichDist));
            simEarnings.put(poorSr, CompositeLoadScoreCalculator.netEarnings(newPoor, newPoorDist));

            // Do-not-over-equalize guard: skip if the transfer would push richSr below the mean.
            // SRs handling more or higher-value shipments should still earn more than the mean.
            // The objective is to eliminate extreme outliers, not enforce identical earnings.
            if (simEarnings.get(richSr) < mean) continue;

            double simRange = CompositeLoadScoreCalculator.earningsRange(simEarnings);
            double improvement = currentRange - simRange;

            if (improvement > bestImprovement) {
                bestImprovement = improvement;
                bestShipment    = candidate;
            }
        }

        return bestShipment; // null if no beneficial transfer found (or all would over-equalize)
    }

    // =========================================================================
    // Phase 3 — Forward/Reverse co-location
    // =========================================================================

    Map<String, List<Shipment>> coLocateReverse(List<Shipment> reverse,
                                                Map<String, List<Shipment>> assignment) {
        if (reverse.isEmpty() || assignment.isEmpty()) return assignment;
        List<String> srNames = new ArrayList<>(assignment.keySet());

        // Sort Reverse shipments by shippingId ascending for deterministic processing order.
        List<Shipment> sortedReverse = reverse.stream()
                .sorted(Comparator.comparing(Shipment::getShippingId))
                .collect(Collectors.toList());

        // Pre-compute every SR's centroid once per call. Reverse shipments are
        // always routed to the *territorially nearest* SR — picking by "close
        // to any Forward shipment" alone was producing assignments where a
        // Reverse drop sat at the very edge of an SR's cluster, dragging the
        // route across other SRs' territories.
        Map<String, double[]> centroids = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> e : assignment.entrySet()) {
            centroids.put(e.getKey(), centroid(e.getValue()));
        }

        for (Shipment rev : sortedReverse) {
            double rLat = rev.getDropLatitude(), rLng = rev.getDropLongitude();

            // Candidates: SRs whose centroid is within ~3 km of the Reverse drop.
            // 1 km was too tight on sparse territories (almost everything fell
            // through to nearestCentroidSr); 3 km matches the typical inter-
            // centroid distance for Pune-scale territories.
            String bestNearby = null;
            double bestNearbyDist = Double.MAX_VALUE;
            for (Map.Entry<String, double[]> e : centroids.entrySet()) {
                double d = haversine(rLat, rLng, e.getValue()[0], e.getValue()[1]);
                if (d <= 3.0 && d < bestNearbyDist) {
                    bestNearbyDist = d;
                    bestNearby = e.getKey();
                }
            }

            String target = (bestNearby != null)
                    ? bestNearby
                    : nearestCentroidSr(rLat, rLng, srNames, assignment);
            assignment.get(target).add(rev);
            // Refresh that SR's centroid so subsequent picks reflect the new shipment
            centroids.put(target, centroid(assignment.get(target)));
        }
        return assignment;
    }

    // =========================================================================
    // Phase 4 — Heavy shipment balancing
    // =========================================================================

    /**
     * Heavy-shipment transfers must respect territory geometry: only move a
     * heavy if the candidate sits within this radius (km) of the receiving SR's
     * centroid. Larger values cause routes to "reach" into other territories
     * (the visual purple-route disorder); 4 km matches a typical metropolitan
     * SR territory radius.
     */
    private static final double HEAVY_TRANSFER_MAX_DISTANCE_KM = 4.0;

    Map<String, List<Shipment>> balanceHeavy(Map<String, List<Shipment>> assignment) {
        if (assignment.size() < 2) return assignment;

        Map<String, Integer> heavyCounts = new LinkedHashMap<>();
        assignment.forEach((sr, list) ->
                heavyCounts.put(sr, (int) list.stream().filter(s -> s.getIsHeavy() == 1).count()));

        int maxH = Collections.max(heavyCounts.values());
        int minH = Collections.min(heavyCounts.values());

        while (maxH - minH > 1) {
            String overloaded  = heavyCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
            String underloaded = heavyCounts.entrySet().stream()
                    .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
            if (overloaded.equals(underloaded)) break;

            List<Shipment> heavies = assignment.get(overloaded).stream()
                    .filter(s -> s.getIsHeavy() == 1).collect(Collectors.toList());
            if (heavies.isEmpty()) break;

            double[] underCentroid = centroid(assignment.get(underloaded));
            Shipment toMove = heavies.stream()
                    .min(Comparator.comparingDouble((Shipment s) ->
                            haversine(s.getDropLatitude(), s.getDropLongitude(),
                                    underCentroid[0], underCentroid[1]))
                            .thenComparing(Shipment::getShippingId))
                    .orElseThrow();

            // Geographic-integrity guard: skip the transfer if even the closest
            // heavy candidate is too far from the receiving SR's territory.
            // Without this, balanceHeavy could move a heavy across the entire
            // city just to even out heavy counts by ±1.
            double candidateDist = haversine(toMove.getDropLatitude(), toMove.getDropLongitude(),
                    underCentroid[0], underCentroid[1]);
            if (candidateDist > HEAVY_TRANSFER_MAX_DISTANCE_KM) {
                log.debug("balanceHeavy: skipping cross-territory transfer of '{}' " +
                        "({} km from {}'s centroid > {} km cap)",
                        toMove.getShippingId(), String.format("%.1f", candidateDist),
                        underloaded, HEAVY_TRANSFER_MAX_DISTANCE_KM);
                break; // No safe transfer available — accept current imbalance
            }

            assignment.get(overloaded).remove(toMove);
            assignment.get(underloaded).add(toMove);
            heavyCounts.put(overloaded,  heavyCounts.get(overloaded)  - 1);
            heavyCounts.put(underloaded, heavyCounts.get(underloaded) + 1);
            maxH = Collections.max(heavyCounts.values());
            minH = Collections.min(heavyCounts.values());
        }
        return assignment;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Compute Haversine-estimated route distances for all SRs. */
    private Map<String, Double> computeDistances(Map<String, List<Shipment>> assignment) {
        Map<String, Double> distances = new LinkedHashMap<>();
        assignment.forEach((sr, list) ->
                distances.put(sr, routeOptimizerService.estimateDistanceKm(list)));
        return distances;
    }

    /** Compute net earnings for all SRs given pre-computed distances. */
    private Map<String, Double> computeNetEarningsMap(Map<String, List<Shipment>> assignment,
                                                      Map<String, Double> distances) {
        Map<String, Double> earnings = new LinkedHashMap<>();
        assignment.forEach((sr, list) ->
                earnings.put(sr, CompositeLoadScoreCalculator.netEarnings(
                        list, distances.getOrDefault(sr, 0.0))));
        return earnings;
    }

    private String maxEarningsSr(Map<String, Double> earnings) {
        return earnings.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
    }

    private String minEarningsSr(Map<String, Double> earnings) {
        return earnings.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
    }

    private double[] centroid(List<Shipment> shipments) {
        if (shipments.isEmpty()) return new double[]{hubLat, hubLng};
        double sumLat = 0, sumLng = 0;
        for (Shipment s : shipments) {
            sumLat += s.getDropLatitude();
            sumLng += s.getDropLongitude();
        }
        return new double[]{sumLat / shipments.size(), sumLng / shipments.size()};
    }

    private String nearestCentroidSr(double lat, double lng,
                                     Iterable<String> candidates,
                                     Map<String, List<Shipment>> assignment) {
        String nearest = null;
        double minDist = Double.MAX_VALUE;
        for (String sr : candidates) {
            double[] c = centroid(assignment.get(sr));
            double d = haversine(lat, lng, c[0], c[1]);
            if (d < minDist) { minDist = d; nearest = sr; }
        }
        return nearest;
    }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        return GoogleMapsService.haversine(lat1, lng1, lat2, lng2);
    }

    /**
     * Ray-casting point-in-polygon test (Jordan curve theorem).
     *
     * Determines whether a point (lat, lng) lies inside the given polygon.
     * The polygon is a list of [lat, lng] pairs forming a closed ring
     * (as returned by HubBoundaryService — already swapped from GeoJSON).
     *
     * Algorithm: cast a horizontal ray from the point to the right and count
     * how many polygon edges it crosses. Odd count = inside, even = outside.
     *
     * Edge cases handled:
     *  - Points exactly on an edge are treated as inside (crossing count rounds up)
     *  - Works correctly for non-convex polygons
     *  - Polygon does not need to be explicitly closed (last→first edge is implicit)
     *
     * @param lat     latitude of the point to test
     * @param lng     longitude of the point to test
     * @param polygon list of [lat, lng] vertex pairs
     * @return true if the point is inside or on the boundary of the polygon
     */
    static boolean isPointInPolygon(double lat, double lng, List<double[]> polygon) {
        if (polygon == null || polygon.size() < 3) return false;
        int n = polygon.size();
        boolean inside = false;
        int j = n - 1;
        for (int i = 0; i < n; i++) {
            double xi = polygon.get(i)[1]; // lng of vertex i
            double yi = polygon.get(i)[0]; // lat of vertex i
            double xj = polygon.get(j)[1]; // lng of vertex j
            double yj = polygon.get(j)[0]; // lat of vertex j
            // Check if the horizontal ray from (lng, lat) crosses edge (j→i)
            boolean intersects = ((yi > lat) != (yj > lat))
                    && (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi);
            if (intersects) inside = !inside;
            j = i;
        }
        return inside;
    }



    private AllocationSummary buildSummary(String dateStr,
                                           int totalShipments,
                                           int totalSrs,
                                           double legacyVariance,
                                           double earningsVar,
                                           double earningsRange,
                                           double meanEarnings,
                                           Map<String, List<Shipment>> assignment,
                                           Map<String, Double> distancesBySr,
                                           Map<String, Double> earningsBySr,
                                           int unallocatedShipments,
                                           PriorityCountsDto priorityCounts) {

        Map<String, Double> scoresBySr = new LinkedHashMap<>();
        assignment.forEach((sr, list) ->
                scoresBySr.put(sr, CompositeLoadScoreCalculator.compute(
                        list, distancesBySr.getOrDefault(sr, 0.0), scoreWeights)));

        List<SrSummaryDto> srSummaries = new ArrayList<>();
        for (Map.Entry<String, List<Shipment>> entry : assignment.entrySet()) {
            String sr   = entry.getKey();
            List<Shipment> list = entry.getValue();

            int heavyCount = (int) list.stream().filter(s -> s.getIsHeavy() == 1).count();
            double dist    = distancesBySr.getOrDefault(sr, 0.0);
            double gross   = CompositeLoadScoreCalculator.grossPayout(list);
            double fuel    = CompositeLoadScoreCalculator.fuelCost(dist);
            double net     = earningsBySr.getOrDefault(sr, gross - fuel);

            List<String> pincodes = list.stream()
                    .map(Shipment::getDropPincode)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());

            srSummaries.add(new SrSummaryDto(
                    sr,
                    list.size(),
                    heavyCount,
                    scoresBySr.getOrDefault(sr, 0.0),
                    dist,
                    pincodes,
                    gross,
                    fuel,
                    net));
        }

        IntSummaryStatistics stats = assignment.values().stream()
                .mapToInt(List::size)
                .summaryStatistics();

        int allocated = totalShipments - unallocatedShipments;

        return new AllocationSummary(
                dateStr,
                totalShipments,
                allocated,
                unallocatedShipments,
                srCapacityMin,
                srCapacityMax,
                totalSrs,
                assignment.isEmpty() ? 0 : stats.getMin(),
                assignment.isEmpty() ? 0 : stats.getMax(),
                assignment.isEmpty() ? 0.0 : stats.getAverage(),
                legacyVariance,
                srSummaries,
                earningsVar,
                earningsRange,
                meanEarnings,
                /* allocationMode        */ null,
                /* shiftDurationMinutes  */ null,
                /* overflowShipments     */ null,
                /* noRegionShipments     */ null,
                /* regionSummaries       */ null,
                /* operationalWarnings   */ null,
                /* earningsImbalanceWarn */ null,
                /* priorityCounts        */ priorityCounts);
    }

    /**
     * Tally how many shipments fall in each priority tier (P0/P1/P2) and
     * how many of those were actually allocated. Unallocated counts are
     * computed as {@code total - allocated} per tier.
     *
     * <p>Shipments with a {@code null} priority are treated as P2 (the
     * documented default when the CSV column is missing).
     */
    private PriorityCountsDto computePriorityCounts(List<Shipment> allShipments,
                                                    Set<String> allocatedIds) {
        int p0Total = 0, p0Alloc = 0;
        int p1Total = 0, p1Alloc = 0;
        int p2Total = 0, p2Alloc = 0;
        for (Shipment s : allShipments) {
            Priority pr = s.getPriority() == null ? Priority.P2 : s.getPriority();
            boolean isAllocated = allocatedIds.contains(s.getShippingId());
            switch (pr) {
                case P0:
                    p0Total++;
                    if (isAllocated) p0Alloc++;
                    break;
                case P1:
                    p1Total++;
                    if (isAllocated) p1Alloc++;
                    break;
                case P2:
                default:
                    p2Total++;
                    if (isAllocated) p2Alloc++;
                    break;
            }
        }
        return new PriorityCountsDto(
                p0Total, p0Alloc, p0Total - p0Alloc,
                p1Total, p1Alloc, p1Total - p1Alloc,
                p2Total, p2Alloc, p2Total - p2Alloc);
    }

    // =========================================================================
    // Date formatting
    // =========================================================================

    private String formatDate(LocalDate date) {
        java.time.format.DateTimeFormatter[] fmts = {
                java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH),
                java.time.format.DateTimeFormatter.ofPattern("d-MMM-yy",  java.util.Locale.ENGLISH),
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", java.util.Locale.ENGLISH),
        };
        for (var fmt : fmts) {
            String candidate = date.format(fmt);
            if (store.hasShipmentsForDate(candidate)) return candidate;
        }
        String iso = date.toString();
        if (store.hasShipmentsForDate(iso)) return iso;
        return date.format(fmts[0]);
    }
}

