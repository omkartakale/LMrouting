package com.example.LMrouting.service;

import com.example.LMrouting.dto.AllocateRequest;
import com.example.LMrouting.dto.AllocationSummary;
import com.example.LMrouting.dto.HubBoundaryResponse;
import com.example.LMrouting.dto.RegionSummaryDto;
import com.example.LMrouting.dto.SrRebalanceSuggestion;
import com.example.LMrouting.dto.SrSummaryDto;
import com.example.LMrouting.exception.AllocationNotFoundException;
import com.example.LMrouting.exception.NoPresentSrsException;
import com.example.LMrouting.model.AllocationRun;
import com.example.LMrouting.model.AllocationStatus;
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
 * Affinity-first, shift-time-based allocation pipeline.
 *
 * <p>Activated when {@code allocationMode} is {@code "time-based"}.
 * Implements a 10-phase pipeline:
 * <ol>
 *   <li>Load affinity config and build regionPincodes map</li>
 *   <li>Build SR affinity status map (AFFINITY_ASSIGNED / NON_AFFINITY)</li>
 *   <li>Pre-filter shipments (valid coords, hub boundary, pincode boundary)</li>
 *   <li>Partition shipments by affinity region</li>
 *   <li>Dense-pack each region into assigned SRs by shift time</li>
 *   <li>Collect overflow + no-region shipments; allocate to NON_AFFINITY SRs</li>
 *   <li>Route sequencing: nearest-neighbour + 2-opt per SR</li>
 *   <li>Compute workload metrics per SR</li>
 *   <li>Persist to InMemoryStore</li>
 *   <li>Build and return AllocationSummary with time-based fields</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AffinityShiftAllocationService {

    private final InMemoryStore store;
    private final AffinityConfigStorageService affinityConfigStorage;
    private final TravelTimeCacheService travelTimeCache;
    private final ShiftWorkloadCalculatorService workloadCalculator;
    private final RouteOptimizerService routeOptimizerService;
    private final HubBoundaryService hubBoundaryService;
    private final PincodeBoundaryService pincodeBoundaryService;
    private final ClusterFirstRouteOptimizer clusterFirstRouteOptimizer;
    private final LegacyRouteOptimizer legacyRouteOptimizer;
    private final EarningsBalancingService earningsBalancingService;
    private final TerritoryPartitionService territoryPartitionService;

    @Value("${hub.name:PNQ HDP}")
    private String hubName;

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    @Value("${allocation.shift.duration.minutes:480}")
    private int shiftDurationMinutes;

    @Value("${allocation.twoopt.max.iterations:100}")
    private int twoOptMaxIterations;

    @Value("${allocation.boundary.km:25.0}")
    private double allocationBoundaryKmFallback;

    @Value("${allocation.shift.target.utilisation:0.88}")
    private double targetUtilisation;

    @Value("${allocation.leftover.min.threshold:10}")
    private int leftoverMinThreshold;

    @Value("${allocation.route.optimizer.strategy:cluster-first}")
    private String routeOptimizerStrategy;

    private final TwoOptRouteOptimizer twoOptOptimizer = new TwoOptRouteOptimizer();

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Main entry point for time-based allocation.
     *
     * @param date    allocation date
     * @param request allocation request (must have allocationMode = "time-based")
     * @return AllocationSummary with time-based fields populated
     */
    public AllocationSummary allocate(LocalDate date, AllocateRequest request) {
        String dateStr = date.toString();
        log.info("AffinityShiftAllocationService: starting time-based allocation for '{}'", dateStr);

        travelTimeCache.resetStats();

        // ── Load shipments ────────────────────────────────────────────────────
        List<Shipment> allShipments = store.findShipmentsByDate(dateStr);
        if (allShipments.isEmpty()) {
            throw new AllocationNotFoundException(
                    "No shipment data found for " + dateStr + ". Please upload a CSV file first.");
        }

        List<String> presentSrs = store.getPresentSrNames(date);
        if (presentSrs.isEmpty()) {
            throw new NoPresentSrsException(
                    "At least one SR must be marked present before running allocation.");
        }

        // ── Phase 1: Load affinity config ─────────────────────────────────────
        Map<String, Object> config;
        try {
            config = affinityConfigStorage.loadConfig();
        } catch (Exception e) {
            log.warn("AffinityShiftAllocationService: failed to load affinity config: {}", e.getMessage());
            config = new HashMap<>();
        }

        if (config == null || config.isEmpty()) {
            log.info("AffinityShiftAllocationService: no affinity config found — falling back to count-based pipeline");
            // Fall back: return a summary with allocationMode="count-based"
            // The caller (AllocationEngineService) should not reach here with empty config,
            // but we handle it gracefully.
            return buildEmptyFallbackSummary(dateStr, allShipments.size());
        }

        Map<String, Set<String>> regionPincodes = buildRegionPincodes(config);

        // ── Extract per-SR shift durations (empty map if not configured) ──────
        Map<String, Integer> srShiftDurations = extractSrShiftDurations(config);
        if (!srShiftDurations.isEmpty()) {
            log.info("AffinityShiftAllocationService: loaded per-SR shift durations: {}", srShiftDurations);
        }

        // ── Phase 2: Build SR affinity status ─────────────────────────────────
        Map<String, String> srAffinityStatus = buildSrAffinityStatus(presentSrs, config);
        srAffinityStatus.forEach((sr, status) ->
                log.info("AffinityShiftAllocationService: SR '{}' → {}", sr, status));

        // ── Phase 3: Pre-filter shipments ─────────────────────────────────────
        List<Shipment> filtered = applyPreFilters(allShipments);
        int unallocatedByFilter = allShipments.size() - filtered.size();
        log.info("AffinityShiftAllocationService: {} shipments after pre-filters (excluded {})",
                filtered.size(), unallocatedByFilter);

        // ── Phase 4: Partition shipments by affinity region ───────────────────
        // Two-pass: pincode match first, then coordinate match against region polygon
        Map<String, List<Shipment>> regionShipments = partitionByRegion(filtered, regionPincodes, config);
        List<Shipment> noRegionShipments = new ArrayList<>(
                regionShipments.getOrDefault("__NO_REGION__", Collections.emptyList()));
        regionShipments.remove("__NO_REGION__");
        log.info("AffinityShiftAllocationService: {} no-region shipments, {} regions with shipments",
                noRegionShipments.size(), regionShipments.size());
        regionShipments.forEach((region, ships) ->
                log.info("  Region '{}' → {} shipments", region, ships.size()));

        // ── Phase 5: Territory partitioning per region ─────────────────────────
        Map<String, List<Shipment>> srAssignments = new LinkedHashMap<>();
        for (String sr : presentSrs) srAssignments.put(sr, new ArrayList<>());

        List<Shipment> overflowShipments = new ArrayList<>();
        List<String> operationalWarnings = new ArrayList<>();
        Set<String> smallLeftoverRegions = new HashSet<>();
        Map<String, List<double[]>> allTerritoryBoundaries = new LinkedHashMap<>();

        for (Map.Entry<String, List<Shipment>> regionEntry : regionShipments.entrySet()) {
            String regionName = regionEntry.getKey();
            List<String> regionSrNames = getAssignedSrsForRegion(regionName, config, presentSrs);

            log.info("AffinityShiftAllocationService: Phase 5 — region '{}' → assigned SRs: {} (shipments: {})",
                    regionName, regionSrNames, regionEntry.getValue().size());

            if (regionSrNames.isEmpty()) {
                int shipmentCount = regionEntry.getValue().size();
                String warning = String.format("Region %s has %d shipments but no assigned SRs",
                        regionName, shipmentCount);
                operationalWarnings.add(warning);
                log.warn("AffinityShiftAllocationService: CONFIGURATION WARNING — {}", warning);
                noRegionShipments.addAll(regionEntry.getValue());
                continue;
            }

            TerritoryPartitionService.TerritoryResult territoryResult =
                    territoryPartitionService.partition(
                            regionEntry.getValue(), regionSrNames, srShiftDurations,
                            shiftDurationMinutes, targetUtilisation, hubLat, hubLng);

            territoryResult.assignments().forEach((sr, shipments) -> {
                log.info("AffinityShiftAllocationService: Phase 5 — SR '{}' gets {} shipments from region '{}'",
                        sr, shipments.size(), regionName);
                srAssignments.get(sr).addAll(shipments);
            });

            // Store territory boundaries for visualization
            allTerritoryBoundaries.putAll(territoryResult.territoryBoundaries());

            if (!territoryResult.overflow().isEmpty()) {
                int overflowCount = territoryResult.overflow().size();

                // ── Small leftover guard ──────────────────────────────────────
                if (overflowCount < leftoverMinThreshold) {
                    log.info("AffinityShiftAllocationService: region '{}' has {} overflow shipments (< threshold {}), attempting consolidation",
                            regionName, overflowCount, leftoverMinThreshold);

                    // Try to consolidate into existing SRs in this region (strict shift cap)
                    double overfillTolerance = 1.0;
                    List<Shipment> consolidated = new ArrayList<>();
                    List<Shipment> unconsolidated = new ArrayList<>();

                    for (Shipment s : territoryResult.overflow()) {
                        boolean placed = false;
                        List<String> srsByLoad = regionSrNames.stream()
                                .filter(sr -> srAssignments.get(sr) != null && !srAssignments.get(sr).isEmpty())
                                .sorted(Comparator.comparingInt(
                                        (String sr) -> srAssignments.get(sr).size()).reversed())
                                .collect(Collectors.toList());

                        for (String sr : srsByLoad) {
                            List<Shipment> current = srAssignments.get(sr);
                            List<Shipment> trial = new ArrayList<>(current);
                            trial.add(s);
                            ShiftWorkloadCalculatorService.WorkloadResult w =
                                    workloadCalculator.computeWorkload(nearestNeighbourOrder(trial));
                            if (w.totalMinutes() < getShiftDurationForSr(sr, srShiftDurations) * overfillTolerance) {
                                current.add(s);
                                consolidated.add(s);
                                placed = true;
                                break;
                            }
                        }
                        if (!placed) {
                            unconsolidated.add(s);
                        }
                    }

                    if (!unconsolidated.isEmpty()) {
                        smallLeftoverRegions.add(regionName);
                        String warning = String.format(
                                "Region %s: %d leftover shipments (< %d threshold) could not be consolidated — marked as unallocated instead of activating a new SR",
                                regionName, unconsolidated.size(), leftoverMinThreshold);
                        operationalWarnings.add(warning);
                        log.warn("AffinityShiftAllocationService: SMALL LEFTOVER — {}", warning);
                        overflowShipments.addAll(unconsolidated);
                    }

                    if (!consolidated.isEmpty()) {
                        log.info("AffinityShiftAllocationService: region '{}' — consolidated {} of {} overflow shipments into existing SRs",
                                regionName, consolidated.size(), overflowCount);
                    }
                } else {
                    log.warn("AffinityShiftAllocationService: region '{}' has {} overflow shipments",
                            regionName, overflowCount);
                    overflowShipments.addAll(territoryResult.overflow());
                }
            }
        }

        // ── Phase 6: Strict region-isolated overflow handling ─────────────────
        // STRICT AFFINITY RULE: Overflow from a region goes ONLY to SRs in that
        // same region (with 5% overfill tolerance). Cross-region assignment is
        // NEVER allowed. Shipments that can't fit in any SR of their region
        // remain unallocated.
        //
        // No-region shipments go to NON_AFFINITY SRs only.
        // If no NON_AFFINITY SRs exist, no-region shipments remain unallocated.

        // 6a: Re-assign overflow shipments back to their own region's SRs
        //     (strict shift duration cap — no overfill allowed)
        double overfillTolerance = 1.0;
        List<Shipment> trulyUnallocated = new ArrayList<>();

        for (Shipment s : overflowShipments) {
            // Find which region this shipment belongs to (coordinate-based)
            String shipmentRegion = findRegionForShipment(s, config);
            if (shipmentRegion == null) {
                // No region match — treat as no-region
                noRegionShipments.add(s);
                continue;
            }

            // Try to assign to an SR in the same region (prefer SR with most shipments
            // to maximize utilisation of already-active SRs)
            List<String> regionSrs = getAssignedSrsForRegion(shipmentRegion, config, presentSrs);
            // Sort by current load descending (fill busiest SR first — minimum manpower)
            regionSrs = regionSrs.stream()
                    .sorted(Comparator.comparingInt(
                            (String sr) -> srAssignments.getOrDefault(sr, Collections.emptyList()).size())
                            .reversed())
                    .collect(Collectors.toList());

            boolean placed = false;
            for (String sr : regionSrs) {
                List<Shipment> current = srAssignments.get(sr);
                if (current == null) continue;
                List<Shipment> trial = new ArrayList<>(current);
                trial.add(s);
                ShiftWorkloadCalculatorService.WorkloadResult w =
                        workloadCalculator.computeWorkload(nearestNeighbourOrder(trial));
                if (w.totalMinutes() < getShiftDurationForSr(sr, srShiftDurations) * overfillTolerance) {
                    current.add(s);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                trulyUnallocated.add(s);
            }
        }

        // 6b: No-region shipments → NON_AFFINITY SRs only
        List<String> nonAffinitySrs = presentSrs.stream()
                .filter(sr -> "NON_AFFINITY".equals(srAffinityStatus.get(sr)))
                .sorted()
                .collect(Collectors.toList());

        if (!nonAffinitySrs.isEmpty() && !noRegionShipments.isEmpty()) {
            DensePackResult nonAffinityPack = densePackRegion(noRegionShipments, nonAffinitySrs, srShiftDurations);
            nonAffinityPack.assignments().forEach((sr, shipments) ->
                    srAssignments.get(sr).addAll(shipments));
            trulyUnallocated.addAll(nonAffinityPack.overflow());
        } else {
            trulyUnallocated.addAll(noRegionShipments);
        }

        if (!trulyUnallocated.isEmpty()) {
            log.info("AffinityShiftAllocationService: {} shipments remain unallocated (strict affinity — all region SRs at capacity)",
                    trulyUnallocated.size());
        }

        int totalUnallocated = unallocatedByFilter + trulyUnallocated.size();

        // ── Phase 6c: (DISABLED — superseded by territory partitioning) ────────
        // Territory partitioning in Phase 5 already handles optimal SR count
        // determination, so the tiny-allocation consolidation phase is no longer
        // needed. The territory K-Means algorithm only activates the optimal
        // number of SRs based on workload estimation.

        // ── Phase 7: Route sequencing (strategy-based optimization) ────────────
        RouteOptimizationStrategy routeStrategy = resolveRouteStrategy();
        log.info("AffinityShiftAllocationService: Phase 7 — using route optimization strategy: '{}'", routeOptimizerStrategy);

        Map<String, List<Shipment>> orderedAssignments = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> entry : srAssignments.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> srShipments = entry.getValue();

            if (srShipments.isEmpty()) {
                orderedAssignments.put(sr, srShipments);
                continue;
            }
            if (srShipments.size() == 1) {
                srShipments.get(0).setRouteSequence(1);
                orderedAssignments.put(sr, srShipments);
                continue;
            }

            // Use the configured route optimization strategy
            List<Shipment> optimized = routeStrategy.optimize(srShipments, hubLat, hubLng);
            // Assign route sequences
            for (int i = 0; i < optimized.size(); i++) {
                optimized.get(i).setRouteSequence(i + 1);
            }
            orderedAssignments.put(sr, optimized);
        }

        // ── Phase 8: Compute workload metrics per SR ──────────────────────────
        Map<String, ShiftWorkloadCalculatorService.WorkloadResult> workloadBySr = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> entry : orderedAssignments.entrySet()) {
            workloadBySr.put(entry.getKey(), workloadCalculator.computeWorkload(entry.getValue()));
        }

        // ── Phase 8b: Earnings balancing pass (per region) ────────────────────
        // For each region with ≥2 active SRs, invoke EarningsBalancingService to
        // reduce earnings spread. Shipments may move between SRs within the same
        // region, so routes need re-sequencing afterward.
        boolean earningsImbalanceWarning = false;

        // Group SR assignments by region
        Map<String, Map<String, List<Shipment>>> regionSrAssignments = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> entry : orderedAssignments.entrySet()) {
            String sr = entry.getKey();
            if (entry.getValue().isEmpty()) continue;
            String srRegion = getSrRegion(sr, config);
            if (srRegion == null) continue;
            regionSrAssignments
                    .computeIfAbsent(srRegion, k -> new LinkedHashMap<>())
                    .put(sr, entry.getValue());
        }

        for (Map.Entry<String, Map<String, List<Shipment>>> regionEntry : regionSrAssignments.entrySet()) {
            String regionName = regionEntry.getKey();
            Map<String, List<Shipment>> regionAssignments = regionEntry.getValue();

            // Only balance regions with ≥2 active SRs
            long activeSrCount = regionAssignments.values().stream()
                    .filter(shipments -> !shipments.isEmpty())
                    .count();
            if (activeSrCount < 2) continue;

            log.info("AffinityShiftAllocationService: Phase 8b — earnings balancing for region '{}' ({} active SRs)",
                    regionName, activeSrCount);

            EarningsBalancingService.BalancingResult balancingResult =
                    earningsBalancingService.balance(regionAssignments, srShiftDurations, shiftDurationMinutes);

            if (balancingResult.earningsImbalanceWarning()) {
                earningsImbalanceWarning = true;
                log.warn("AffinityShiftAllocationService: Phase 8b — earnings imbalance detected in region '{}'", regionName);
            }

            // Update orderedAssignments with balanced assignments and re-sequence routes
            RouteOptimizationStrategy routeStrategyForResequence = resolveRouteStrategy();
            for (Map.Entry<String, List<Shipment>> srEntry : balancingResult.srAssignments().entrySet()) {
                String sr = srEntry.getKey();
                List<Shipment> balancedShipments = srEntry.getValue();

                if (balancedShipments.isEmpty()) {
                    orderedAssignments.put(sr, balancedShipments);
                    continue;
                }
                if (balancedShipments.size() == 1) {
                    balancedShipments.get(0).setRouteSequence(1);
                    orderedAssignments.put(sr, balancedShipments);
                    continue;
                }

                // Re-sequence routes since shipments may have moved between SRs
                List<Shipment> reOptimized = routeStrategyForResequence.optimize(balancedShipments, hubLat, hubLng);
                for (int i = 0; i < reOptimized.size(); i++) {
                    reOptimized.get(i).setRouteSequence(i + 1);
                }
                orderedAssignments.put(sr, reOptimized);
            }
        }

        // Recompute workload metrics after earnings balancing (routes may have changed)
        if (earningsImbalanceWarning || !regionSrAssignments.isEmpty()) {
            for (Map.Entry<String, List<Shipment>> entry : orderedAssignments.entrySet()) {
                workloadBySr.put(entry.getKey(), workloadCalculator.computeWorkload(entry.getValue()));
            }
        }

        // ── Phase 8a-enforce: Workload enforcement pass ───────────────────────
        // After route optimization (Phase 7) and earnings balancing (Phase 8b),
        // the final route order may differ from what was estimated during dense
        // packing. This pass ensures NO SR exceeds shiftDurationMinutes by
        // shedding the last shipments (furthest from hub in route order) until
        // the workload fits within the shift.
        List<Shipment> shedShipments = new ArrayList<>();
        for (Map.Entry<String, List<Shipment>> entry : orderedAssignments.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> route = entry.getValue();
            if (route.isEmpty()) continue;

            ShiftWorkloadCalculatorService.WorkloadResult w = workloadBySr.get(sr);
            if (w == null) continue;

            // Shed shipments from the end of the route until workload fits
            while (w.totalMinutes() >= getShiftDurationForSr(sr, srShiftDurations) && route.size() > 1) {
                Shipment removed = route.remove(route.size() - 1);
                shedShipments.add(removed);
                w = workloadCalculator.computeWorkload(route);
                workloadBySr.put(sr, w);
            }
        }

        // Try to place shed shipments into SRs that still have capacity
        if (!shedShipments.isEmpty()) {
            log.info("AffinityShiftAllocationService: Phase 8a-enforce — shed {} shipments from overloaded SRs, attempting redistribution",
                    shedShipments.size());

            List<Shipment> unplacedShed = new ArrayList<>();
            RouteOptimizationStrategy shedRouteStrategy = resolveRouteStrategy();

            for (Shipment s : shedShipments) {
                boolean placed = false;
                // Find SRs in the same region with remaining capacity
                String shipmentRegion = findRegionForShipment(s, config);
                List<String> candidateSrs = shipmentRegion != null
                        ? getAssignedSrsForRegion(shipmentRegion, config, presentSrs)
                        : new ArrayList<>(orderedAssignments.keySet());

                // Sort by current workload ascending (prefer least-loaded SR)
                candidateSrs.sort(Comparator.comparingDouble(sr -> {
                    ShiftWorkloadCalculatorService.WorkloadResult wl = workloadBySr.get(sr);
                    return wl != null ? wl.totalMinutes() : Double.MAX_VALUE;
                }));

                for (String sr : candidateSrs) {
                    List<Shipment> route = orderedAssignments.get(sr);
                    if (route == null) continue;

                    List<Shipment> trial = new ArrayList<>(route);
                    trial.add(s);
                    // Re-optimize the trial route
                    List<Shipment> optimizedTrial = trial.size() > 2
                            ? shedRouteStrategy.optimize(trial, hubLat, hubLng)
                            : trial;
                    ShiftWorkloadCalculatorService.WorkloadResult trialW =
                            workloadCalculator.computeWorkload(optimizedTrial);

                    if (trialW.totalMinutes() < getShiftDurationForSr(sr, srShiftDurations)) {
                        // Fits — update the route
                        for (int i = 0; i < optimizedTrial.size(); i++) {
                            optimizedTrial.get(i).setRouteSequence(i + 1);
                        }
                        orderedAssignments.put(sr, optimizedTrial);
                        workloadBySr.put(sr, trialW);
                        placed = true;
                        break;
                    }
                }

                if (!placed) {
                    unplacedShed.add(s);
                }
            }

            if (!unplacedShed.isEmpty()) {
                log.warn("AffinityShiftAllocationService: Phase 8a-enforce — {} shed shipments could not be placed, marking as unallocated",
                        unplacedShed.size());
                totalUnallocated += unplacedShed.size();
            }

            // Recompute workload after enforcement
            for (Map.Entry<String, List<Shipment>> entry : orderedAssignments.entrySet()) {
                workloadBySr.put(entry.getKey(), workloadCalculator.computeWorkload(entry.getValue()));
            }
        }

        log.info("AffinityShiftAllocationService: Phase 8b complete — earningsImbalanceWarning={}", earningsImbalanceWarning);

        // ── Phase 8a-consolidate: Final tiny-allocation cleanup ───────────────
        // After Phase 8a-enforce, some SRs may have received shed shipments but
        // still have fewer than the minimum threshold. Consolidate them into other
        // SRs in the same region, or mark as unallocated.
        for (String sr : new ArrayList<>(orderedAssignments.keySet())) {
            List<Shipment> route = orderedAssignments.get(sr);
            if (route == null || route.isEmpty()) continue;
            if (route.size() >= leftoverMinThreshold) continue;

            String srRegion = getSrRegion(sr, config);
            if (srRegion == null) continue;

            // Try to distribute these shipments individually across other SRs in the region
            List<String> regionSrs = getAssignedSrsForRegion(srRegion, config, presentSrs);
            List<String> candidateSrs = regionSrs.stream()
                    .filter(candidate -> !candidate.equals(sr))
                    .filter(candidate -> orderedAssignments.get(candidate) != null
                            && orderedAssignments.get(candidate).size() >= leftoverMinThreshold)
                    .sorted(Comparator.comparingDouble(candidate -> {
                        ShiftWorkloadCalculatorService.WorkloadResult wl = workloadBySr.get(candidate);
                        return wl != null ? wl.totalMinutes() : Double.MAX_VALUE;
                    }))
                    .collect(Collectors.toList());

            RouteOptimizationStrategy consolidateStrategy = resolveRouteStrategy();
            List<Shipment> placed = new ArrayList<>();
            List<Shipment> unplaced = new ArrayList<>();

            for (Shipment s : route) {
                boolean wasPlaced = false;
                for (String candidate : candidateSrs) {
                    List<Shipment> candidateRoute = orderedAssignments.get(candidate);
                    List<Shipment> trial = new ArrayList<>(candidateRoute);
                    trial.add(s);
                    List<Shipment> optimizedTrial = trial.size() > 2
                            ? consolidateStrategy.optimize(trial, hubLat, hubLng)
                            : trial;
                    ShiftWorkloadCalculatorService.WorkloadResult w =
                            workloadCalculator.computeWorkload(optimizedTrial);
                    if (w.totalMinutes() < getShiftDurationForSr(candidate, srShiftDurations)) {
                        for (int i = 0; i < optimizedTrial.size(); i++) {
                            optimizedTrial.get(i).setRouteSequence(i + 1);
                        }
                        orderedAssignments.put(candidate, optimizedTrial);
                        workloadBySr.put(candidate, w);
                        placed.add(s);
                        wasPlaced = true;
                        break;
                    }
                }
                if (!wasPlaced) {
                    unplaced.add(s);
                }
            }

            // Clear this SR
            orderedAssignments.put(sr, new ArrayList<>());
            workloadBySr.put(sr, new ShiftWorkloadCalculatorService.WorkloadResult(0, 0, 0, 0));

            if (!placed.isEmpty() || !unplaced.isEmpty()) {
                log.info("AffinityShiftAllocationService: Phase 8a-consolidate — SR '{}' had {} shipments (< {} threshold): {} redistributed, {} marked unallocated",
                        sr, placed.size() + unplaced.size(), leftoverMinThreshold, placed.size(), unplaced.size());
            }

            if (!unplaced.isEmpty()) {
                totalUnallocated += unplaced.size();
                String warning = String.format(
                        "SR %s: %d shipments (< %d threshold) could not be consolidated after enforcement — marked as unallocated",
                        sr, unplaced.size(), leftoverMinThreshold);
                operationalWarnings.add(warning);
            }
        }

        // ── Phase 8c: Region load classification ──────────────────────────────
        // Classify each region's health status based on utilisation metrics:
        //   UNDERLOADED: avg utilisation < 50%
        //   HEALTHY:     avg utilisation 50-90%
        //   OVERLOADED:  avg utilisation > 90% OR overflow > 0
        // This pre-computes classifications so they are available for operational
        // decisions and the allocation summary.
        Map<String, String> regionHealthClassifications = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> regionEntry : regionShipments.entrySet()) {
            String regionName = regionEntry.getKey();
            List<String> regionSrNames = getAssignedSrsForRegion(regionName, config, presentSrs);

            // Compute utilisation stats for this region's SRs
            List<Double> regionUtilisations = new ArrayList<>();
            for (String sr : regionSrNames) {
                ShiftWorkloadCalculatorService.WorkloadResult w =
                        workloadBySr.getOrDefault(sr, new ShiftWorkloadCalculatorService.WorkloadResult(0, 0, 0, 0));
                int srDuration = getShiftDurationForSr(sr, srShiftDurations);
                double utilisationPct = srDuration > 0
                        ? Math.round((w.totalMinutes() / srDuration) * 1000.0) / 10.0
                        : 0.0;
                regionUtilisations.add(utilisationPct);
            }

            double avgUtil = regionUtilisations.isEmpty() ? 0.0
                    : regionUtilisations.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

            // Compute overflow for this region
            int regionAllocated = regionSrNames.stream()
                    .mapToInt(sr -> orderedAssignments.getOrDefault(sr, Collections.emptyList()).size())
                    .sum();
            int regionOverflow = Math.max(0, regionEntry.getValue().size() - regionAllocated);

            String healthStatus = RegionSummaryDto.computeHealthStatus(regionOverflow, 0, avgUtil);
            regionHealthClassifications.put(regionName, healthStatus);

            log.info("AffinityShiftAllocationService: Phase 8c — region '{}' classified as {} (avgUtil={}%, overflow={})",
                    regionName, healthStatus, Math.round(avgUtil * 10.0) / 10.0, regionOverflow);
        }

        log.info("AffinityShiftAllocationService: Phase 8c complete — {} regions classified", regionHealthClassifications.size());

        // ── Phase 9: Persist to InMemoryStore ─────────────────────────────────
        Set<String> allocatedIds = new HashSet<>();
        for (Map.Entry<String, List<Shipment>> entry : orderedAssignments.entrySet()) {
            entry.getValue().forEach(s -> {
                s.setAssignedSr(entry.getKey());
                allocatedIds.add(s.getShippingId());
            });
        }

        Map<String, Shipment> allocatedById = new LinkedHashMap<>();
        for (List<Shipment> list : orderedAssignments.values()) {
            for (Shipment s : list) allocatedById.put(s.getShippingId(), s);
        }

        List<Shipment> fullList = allShipments.stream().map(s -> {
            Shipment updated = allocatedById.get(s.getShippingId());
            if (updated != null) return updated;
            s.setAssignedSr(null);
            s.setRouteSequence(0);
            return s;
        }).collect(Collectors.toList());

        store.saveShipments(dateStr, fullList);

        // NOTE: Polylines are NOT pre-fetched here to avoid ORS rate limits.
        // With 65 SRs, pre-fetching would make 65 ORS calls simultaneously.
        // Polylines are fetched lazily when the user clicks on an SR in the UI.

        // Save allocation run
        AllocationRun run = AllocationRun.builder()
                .allocationDate(date)
                .status(AllocationStatus.COMPLETED)
                .totalShipments(allShipments.size())
                .totalSrs(presentSrs.size())
                .fairnessVariance(0.0)
                .earningsRange(0.0)
                .createdAt(LocalDateTime.now())
                .build();
        store.saveAllocationRun(run);

        travelTimeCache.logCacheStats();

        // ── Phase 10: Build AllocationSummary ─────────────────────────────────
        return buildSummary(dateStr, allShipments, presentSrs, orderedAssignments,
                workloadBySr, srAffinityStatus, allocatedIds.size(), totalUnallocated,
                overflowShipments.size(), noRegionShipments.size(), config, regionShipments,
                operationalWarnings, smallLeftoverRegions, earningsImbalanceWarning, srShiftDurations,
                allTerritoryBoundaries);
    }

    // =========================================================================
    // Phase helpers (package-private for testing)
    // =========================================================================

    /**
     * Parse affinity config JSON and build Map&lt;regionName, Set&lt;pincodes&gt;&gt;.
     * Uses the pincode boundary service to find pincodes whose centroids fall
     * inside each region polygon.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Set<String>> buildRegionPincodes(Map<String, Object> config) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (config == null || config.isEmpty()) return result;

        List<Map<String, Object>> regions = (List<Map<String, Object>>) config.get("regions");
        if (regions == null) return result;

        Set<String> allPincodes = pincodeBoundaryService.getAllPincodes();

        for (Map<String, Object> region : regions) {
            String regionName = (String) region.get("name");
            if (regionName == null) continue;

            List<List<Double>> polygon = (List<List<Double>>) region.get("polygon");
            if (polygon == null || polygon.isEmpty()) continue;

            // Convert polygon to double[][] for point-in-polygon check
            List<double[]> polygonPoints = polygon.stream()
                    .map(p -> new double[]{p.get(0), p.get(1)})
                    .collect(Collectors.toList());

            // Find all pincodes whose centroid falls inside this region polygon
            Set<String> pincodes = new HashSet<>();
            for (String pincode : allPincodes) {
                // Get the centroid of the pincode polygon
                List<List<double[]>> rings = pincodeBoundaryService.getPolygonForPincode(pincode);
                if (rings.isEmpty()) continue;
                double[] centroid = computeCentroid(rings.get(0));
                // centroid is [lng, lat] — convert to [lat, lng] for polygon check
                if (isPointInPolygon(centroid[1], centroid[0], polygonPoints)) {
                    pincodes.add(pincode);
                }
            }

            result.put(regionName, pincodes);
            log.debug("AffinityShiftAllocationService: region '{}' → {} pincodes", regionName, pincodes.size());
        }
        return result;
    }

    /**
     * Build SR affinity status map: SR name → "AFFINITY_ASSIGNED" or "NON_AFFINITY".
     *
     * <p>Checks both {@code assignedSRs} in each region AND the top-level {@code srZoneMap}
     * (which is how the frontend persists SR→zone assignments from the attendance panel).
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> buildSrAffinityStatus(List<String> presentSrs, Map<String, Object> config) {
        Set<String> assignedSrs = new HashSet<>();
        if (config != null) {
            // Check assignedSRs in each region
            List<Map<String, Object>> regions = (List<Map<String, Object>>) config.get("regions");
            if (regions != null) {
                for (Map<String, Object> region : regions) {
                    List<String> srs = (List<String>) region.get("assignedSRs");
                    if (srs != null) assignedSrs.addAll(srs);
                }
            }
            // Also check srZoneMap (frontend attendance panel assignments)
            Map<String, String> srZoneMap = (Map<String, String>) config.get("srZoneMap");
            if (srZoneMap != null) {
                assignedSrs.addAll(srZoneMap.keySet());
            }        }

        Map<String, String> status = new LinkedHashMap<>();
        for (String sr : presentSrs) {
            status.put(sr, assignedSrs.contains(sr) ? "AFFINITY_ASSIGNED" : "NON_AFFINITY");
        }
        return status;
    }

    /**
     * Apply pre-filters for time-based mode:
     * 1. Valid coordinates (non-zero lat/lng)
     * 2. Hub boundary polygon (or radius fallback)
     *
     * NOTE: Pincode boundary filter is DISABLED in time-based mode because it's
     * too aggressive — many valid shipments have coordinates slightly outside the
     * loaded pincode GeoJSON boundaries. The hub boundary polygon is sufficient
     * to exclude true outliers.
     */
    public List<Shipment> applyPreFilters(List<Shipment> all) {
        // Filter 1: valid coordinates
        List<Shipment> withCoords = all.stream()
                .filter(s -> s.getDropLatitude() != 0 && s.getDropLongitude() != 0)
                .collect(Collectors.toList());

        // Filter 2: hub boundary polygon
        List<double[]> boundaryPolygon = null;
        try {
            HubBoundaryResponse resp = hubBoundaryService.fetchBoundary(hubName);
            if (resp != null && resp.coordinates() != null && !resp.coordinates().isEmpty()) {
                boundaryPolygon = resp.coordinates();
            }
        } catch (Exception e) {
            log.warn("AffinityShiftAllocationService: hub boundary fetch failed: {}", e.getMessage());
        }

        final List<double[]> polygon = boundaryPolygon;
        List<Shipment> withinBoundary;
        if (polygon != null) {
            withinBoundary = withCoords.stream()
                    .filter(s -> isPointInPolygon(s.getDropLatitude(), s.getDropLongitude(), polygon))
                    .collect(Collectors.toList());
            log.info("AffinityShiftAllocationService: hub boundary filter excluded {} shipments",
                    withCoords.size() - withinBoundary.size());
        } else {
            withinBoundary = withCoords.stream()
                    .filter(s -> {
                        double dist = s.getDistanceFromHubKm() > 0
                                ? s.getDistanceFromHubKm()
                                : haversine(hubLat, hubLng, s.getDropLatitude(), s.getDropLongitude());
                        return dist <= allocationBoundaryKmFallback;
                    })
                    .collect(Collectors.toList());
            log.info("AffinityShiftAllocationService: radius fallback filter excluded {} shipments",
                    withCoords.size() - withinBoundary.size());
        }

        // Pincode boundary filter is INTENTIONALLY DISABLED for time-based mode.
        // The hub boundary polygon is sufficient to exclude outliers.
        // The pincode GeoJSON doesn't cover all valid delivery areas in Pune.

        return withinBoundary;
    }

    /**
     * Partition shipments by affinity region.
     *
     * <p>Two-pass matching:
     * <ol>
     *   <li>Pincode match: if the shipment's dropPincode is in the region's pincode set</li>
     *   <li>Coordinate match: if the shipment's coordinates fall inside the region polygon</li>
     * </ol>
     *
     * <p>Returns a map with region names as keys, plus "__NO_REGION__" for unmatched shipments.
     */
    @SuppressWarnings("unchecked")
    public Map<String, List<Shipment>> partitionByRegion(List<Shipment> shipments,
                                                   Map<String, Set<String>> regionPincodes) {
        return partitionByRegion(shipments, regionPincodes, null);
    }

    /**
     * Partition shipments by affinity region with optional polygon fallback.
     */
    @SuppressWarnings("unchecked")
    public Map<String, List<Shipment>> partitionByRegion(List<Shipment> shipments,
                                                   Map<String, Set<String>> regionPincodes,
                                                   Map<String, Object> config) {
        Map<String, List<Shipment>> result = new LinkedHashMap<>();
        result.put("__NO_REGION__", new ArrayList<>());

        // Build region polygon map for coordinate fallback
        Map<String, List<double[]>> regionPolygons = new LinkedHashMap<>();
        if (config != null) {
            List<Map<String, Object>> regions = (List<Map<String, Object>>) config.get("regions");
            if (regions != null) {
                for (Map<String, Object> region : regions) {
                    String name = (String) region.get("name");
                    List<List<Double>> polygon = (List<List<Double>>) region.get("polygon");
                    if (name != null && polygon != null && !polygon.isEmpty()) {
                        List<double[]> pts = polygon.stream()
                                .map(p -> new double[]{p.get(0), p.get(1)})
                                .collect(Collectors.toList());
                        regionPolygons.put(name, pts);
                    }
                }
            }
        }

        for (Shipment s : shipments) {
            String pincode = s.getDropPincode();
            boolean assigned = false;

            // Pass 1 (PRIMARY): Coordinate match against region polygon
            // Direct lat/lng containment is more accurate than pincode centroid matching
            // because a pincode covers a large area and its centroid may be in a different
            // region than the actual shipment location.
            if (!regionPolygons.isEmpty()
                    && s.getDropLatitude() != 0 && s.getDropLongitude() != 0) {
                for (Map.Entry<String, List<double[]>> entry : regionPolygons.entrySet()) {
                    if (isPointInPolygon(s.getDropLatitude(), s.getDropLongitude(), entry.getValue())) {
                        result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(s);
                        assigned = true;
                        break;
                    }
                }
            }

            // Pass 2 (FALLBACK): Pincode match — only when coordinates are missing/invalid
            if (!assigned && pincode != null) {
                for (Map.Entry<String, Set<String>> entry : regionPincodes.entrySet()) {
                    if (entry.getValue().contains(pincode)) {
                        result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(s);
                        assigned = true;
                        break;
                    }
                }
            }

            if (!assigned) {
                result.get("__NO_REGION__").add(s);
            }
        }
        return result;
    }

    /**
     * Dense-pack shipments into SRs using per-SR shift durations.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Group shipments by pincode (geographic clusters)</li>
     *   <li>Order pincode groups by nearest-neighbour from hub</li>
     *   <li>Fill each SR with complete pincode groups until shift is full</li>
     *   <li>When a pincode group would overflow the current SR, split it</li>
     * </ol>
     *
     * <p>This produces much better route stitching than per-shipment greedy packing
     * because shipments in the same pincode area stay together.
     *
     * @param regionShipments  shipments to pack into the SRs
     * @param assignedSrs      ordered list of SRs to fill
     * @param srShiftDurations per-SR shift duration overrides (empty map uses global default)
     * @return assignments per SR and overflow list
     */
    public DensePackResult densePackRegion(List<Shipment> regionShipments, List<String> assignedSrs,
                                             Map<String, Integer> srShiftDurations) {
        Map<String, List<Shipment>> assignments = new LinkedHashMap<>();
        for (String sr : assignedSrs) assignments.put(sr, new ArrayList<>());

        if (regionShipments.isEmpty() || assignedSrs.isEmpty()) {
            return new DensePackResult(assignments, new ArrayList<>());
        }

        // ── Step 1: Group shipments by pincode ────────────────────────────────
        Map<String, List<Shipment>> byPincode = new LinkedHashMap<>();
        for (Shipment s : regionShipments) {
            String key = s.getDropPincode() != null ? s.getDropPincode() : "UNKNOWN";
            byPincode.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        // ── Step 2: Order pincode groups by nearest-neighbour from hub ────────
        List<List<Shipment>> orderedGroups = orderGroupsByNearestNeighbour(byPincode);

        // ── Step 3: Minimum-manpower dense packing ────────────────────────────
        // Strategy: fill the current active SR to near-capacity before activating
        // the next SR. This maximises utilisation of each SR and minimises the
        // number of SRs needed.
        //
        // Target utilisation (default 88%) is configured via property
        // allocation.shift.target.utilisation. The 12% buffer accounts for
        // real-world travel variance that causes routes to exceed 8 hours.

        List<Shipment> overflow = new ArrayList<>();
        int srIdx = 0;
        int groupIdx = 0;

        while (groupIdx < orderedGroups.size() && srIdx < assignedSrs.size()) {
            String currentSr = assignedSrs.get(srIdx);
            int currentSrShiftDuration = getShiftDurationForSr(currentSr, srShiftDurations);
            double targetMinutes = currentSrShiftDuration * targetUtilisation;
            List<Shipment> currentRoute = assignments.get(currentSr);
            List<Shipment> group = orderedGroups.get(groupIdx);

            // Try adding the whole pincode group to the current SR
            List<Shipment> candidateRoute = new ArrayList<>(currentRoute);
            candidateRoute.addAll(group);
            ShiftWorkloadCalculatorService.WorkloadResult workload =
                    workloadCalculator.computeWorkload(nearestNeighbourOrder(candidateRoute));

            if (workload.totalMinutes() < currentSrShiftDuration) {
                // Group fits — add it
                currentRoute.addAll(group);
                groupIdx++;

                // If this SR has reached target utilisation, move to next SR
                // (minimum-manpower: only activate next SR when current is near-full)
                if (workload.totalMinutes() >= targetMinutes && srIdx < assignedSrs.size() - 1) {
                    srIdx++;
                }
            } else if (currentRoute.isEmpty()) {
                // SR is empty but even this group alone exceeds shift — add shipments one by one
                for (Shipment s : group) {
                    List<Shipment> trial = new ArrayList<>(currentRoute);
                    trial.add(s);
                    ShiftWorkloadCalculatorService.WorkloadResult w =
                            workloadCalculator.computeWorkload(nearestNeighbourOrder(trial));
                    if (w.totalMinutes() < currentSrShiftDuration) {
                        currentRoute.add(s);
                    } else {
                        overflow.add(s);
                    }
                }
                groupIdx++;
                srIdx++;
            } else {
                // Current SR is full — check if remaining shipments are below the
                // small leftover threshold before activating the next SR
                int remainingShipments = 0;
                for (int g = groupIdx; g < orderedGroups.size(); g++) {
                    remainingShipments += orderedGroups.get(g).size();
                }

                if (remainingShipments < leftoverMinThreshold && !currentRoute.isEmpty()) {
                    // Small leftover: don't activate a new SR, send remaining to overflow
                    log.info("AffinityShiftAllocationService: densePackRegion — {} remaining shipments < threshold {}, not activating next SR",
                            remainingShipments, leftoverMinThreshold);
                    while (groupIdx < orderedGroups.size()) {
                        overflow.addAll(orderedGroups.get(groupIdx++));
                    }
                    break;
                }

                // Normal case: move to next SR and retry this group
                srIdx++;
            }
        }

        // Remaining groups go to overflow
        while (groupIdx < orderedGroups.size()) {
            overflow.addAll(orderedGroups.get(groupIdx++));
        }

        // ── Step 4: Redistribute overflow to SRs with remaining capacity ─────
        // Try to fit individual overflow shipments into any SR that still has room
        if (!overflow.isEmpty()) {
            List<Shipment> stillOverflow = new ArrayList<>();
            for (Shipment s : overflow) {
                boolean placed = false;
                // Try SRs in order of current load (busiest first — minimum manpower)
                List<String> srsByLoad = assignedSrs.stream()
                        .sorted(Comparator.comparingInt(
                                (String sr) -> assignments.get(sr).size()).reversed())
                        .collect(Collectors.toList());
                for (String sr : srsByLoad) {
                    int srShiftDuration = getShiftDurationForSr(sr, srShiftDurations);
                    List<Shipment> route = assignments.get(sr);
                    List<Shipment> trial = new ArrayList<>(route);
                    trial.add(s);
                    ShiftWorkloadCalculatorService.WorkloadResult w =
                            workloadCalculator.computeWorkload(nearestNeighbourOrder(trial));
                    if (w.totalMinutes() < srShiftDuration) {
                        route.add(s);
                        placed = true;
                        break;
                    }
                }
                if (!placed) stillOverflow.add(s);
            }
            overflow = stillOverflow;
        }

        log.info("AffinityShiftAllocationService: dense-pack result — {} SRs used, {} overflow",
                assignedSrs.stream().filter(sr -> !assignments.get(sr).isEmpty()).count(),
                overflow.size());
        for (String sr : assignedSrs) {
            if (!assignments.get(sr).isEmpty()) {
                log.info("  {} → {} shipments", sr, assignments.get(sr).size());
            }
        }

        return new DensePackResult(assignments, overflow);
    }

    /**
     * Backward-compatible overload that uses the global shift duration for all SRs.
     * Delegates to {@link #densePackRegion(List, List, Map)} with an empty map.
     */
    public DensePackResult densePackRegion(List<Shipment> regionShipments, List<String> assignedSrs) {
        return densePackRegion(regionShipments, assignedSrs, Collections.emptyMap());
    }

    /**
     * Order pincode groups by nearest-neighbour from hub.
     * Groups are ordered so that the route visits geographically adjacent pincodes in sequence.
     */
    private List<List<Shipment>> orderGroupsByNearestNeighbour(Map<String, List<Shipment>> byPincode) {
        List<List<Shipment>> groups = new ArrayList<>(byPincode.values());
        if (groups.size() <= 1) return groups;

        List<List<Shipment>> ordered = new ArrayList<>();
        List<List<Shipment>> remaining = new ArrayList<>(groups);
        double curLat = hubLat, curLng = hubLng;

        while (!remaining.isEmpty()) {
            double minDist = Double.MAX_VALUE;
            List<Shipment> nearest = null;
            for (List<Shipment> group : remaining) {
                // Use centroid of the group for distance calculation
                double[] centroid = groupCentroid(group);
                double dist = haversine(curLat, curLng, centroid[0], centroid[1]);
                if (dist < minDist) { minDist = dist; nearest = group; }
            }
            remaining.remove(nearest);
            ordered.add(nearest);
            double[] c = groupCentroid(nearest);
            curLat = c[0]; curLng = c[1];
        }
        return ordered;
    }

    /**
     * Compute the geographic centroid of a group of shipments.
     */
    private double[] groupCentroid(List<Shipment> group) {
        double sumLat = 0, sumLng = 0;
        for (Shipment s : group) { sumLat += s.getDropLatitude(); sumLng += s.getDropLongitude(); }
        return new double[]{sumLat / group.size(), sumLng / group.size()};
    }

    /**
     * Greedy nearest-neighbour ordering from hub.
     */
    public List<Shipment> nearestNeighbourOrder(List<Shipment> shipments) {
        if (shipments == null || shipments.isEmpty()) return new ArrayList<>();
        if (shipments.size() == 1) return new ArrayList<>(shipments);

        List<Shipment> remaining = new ArrayList<>(shipments);
        List<Shipment> ordered = new ArrayList<>(shipments.size());
        double curLat = hubLat, curLng = hubLng;

        while (!remaining.isEmpty()) {
            double minDist = Double.MAX_VALUE;
            Shipment nearest = null;
            for (Shipment s : remaining) {
                double dist = haversine(curLat, curLng, s.getDropLatitude(), s.getDropLongitude());
                if (dist < minDist) { minDist = dist; nearest = s; }
            }
            remaining.remove(nearest);
            ordered.add(nearest);
            curLat = nearest.getDropLatitude();
            curLng = nearest.getDropLongitude();
        }
        return ordered;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Resolve the active route optimization strategy based on the
     * {@code allocation.route.optimizer.strategy} configuration property.
     *
     * @return the selected RouteOptimizationStrategy implementation
     */
    private RouteOptimizationStrategy resolveRouteStrategy() {
        if ("legacy".equalsIgnoreCase(routeOptimizerStrategy)) {
            return legacyRouteOptimizer;
        }
        // Default: "cluster-first" (also handles any unrecognized value)
        return clusterFirstRouteOptimizer;
    }

    /**
     * Normalize a region name for fuzzy matching.
     * Handles typos like "Region4" vs "Region 4" by removing spaces and lowercasing.
     */
    private static String normalizeRegionName(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase().replaceAll("\\s+", "");
    }

    /**
     * Find which region a shipment belongs to using coordinate-based polygon check.
     * Returns the region name, or null if the shipment doesn't fall inside any region.
     */
    @SuppressWarnings("unchecked")
    private String findRegionForShipment(Shipment s, Map<String, Object> config) {
        if (config == null) return null;
        List<Map<String, Object>> regions = (List<Map<String, Object>>) config.get("regions");
        if (regions == null) return null;
        for (Map<String, Object> region : regions) {
            String rName = (String) region.get("name");
            List<List<Double>> polygon = (List<List<Double>>) region.get("polygon");
            if (rName != null && polygon != null && !polygon.isEmpty()) {
                List<double[]> pts = polygon.stream()
                        .map(p -> new double[]{p.get(0), p.get(1)})
                        .collect(Collectors.toList());
                if (isPointInPolygon(s.getDropLatitude(), s.getDropLongitude(), pts)) {
                    return rName;
                }
            }
        }
        return null;
    }

    /**
     * Get the region name that an SR is assigned to (from srZoneMap or assignedSRs).
     * Returns null if the SR has no region assignment.
     */
    @SuppressWarnings("unchecked")
    private String getSrRegion(String srName, Map<String, Object> config) {
        if (config == null) return null;

        // Check srZoneMap first (primary source)
        Map<String, String> srZoneMap = (Map<String, String>) config.get("srZoneMap");
        if (srZoneMap != null && srZoneMap.containsKey(srName)) {
            // Return the canonical region name from the regions list (normalized match)
            String rawZone = srZoneMap.get(srName);
            List<Map<String, Object>> regions = (List<Map<String, Object>>) config.get("regions");
            if (regions != null) {
                for (Map<String, Object> region : regions) {
                    String rName = (String) region.get("name");
                    if (rName != null && normalizeRegionName(rName).equals(normalizeRegionName(rawZone))) {
                        return rName; // return canonical name
                    }
                }
            }
            return rawZone; // fallback to raw value
        }

        // Fallback: check assignedSRs in each region
        List<Map<String, Object>> regions = (List<Map<String, Object>>) config.get("regions");
        if (regions != null) {
            for (Map<String, Object> region : regions) {
                List<String> srs = (List<String>) region.get("assignedSRs");
                if (srs != null && srs.contains(srName)) {
                    return (String) region.get("name");
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> getAssignedSrsForRegion(String regionName, Map<String, Object> config,
                                                   List<String> presentSrs) {
        Set<String> srsForRegion = new LinkedHashSet<>();

        // Check assignedSRs in the region definition
        List<Map<String, Object>> regions = (List<Map<String, Object>>) config.get("regions");
        if (regions != null) {
            for (Map<String, Object> region : regions) {
                if (regionName.equals(region.get("name"))) {
                    List<String> srs = (List<String>) region.get("assignedSRs");
                    if (srs != null && !srs.isEmpty()) {
                        srsForRegion.addAll(srs);
                        log.debug("  getAssignedSrsForRegion('{}') — from assignedSRs: {}", regionName, srs);
                    }
                    break;
                }
            }
        }

        // Also check srZoneMap (frontend attendance panel assignments)
        // srZoneMap: { "SR-001": "Region 1", "SR-002": "Region 2" }
        // Normalize region names to handle typos like "Region4" vs "Region 4"
        Map<String, String> srZoneMap = (Map<String, String>) config.get("srZoneMap");
        if (srZoneMap != null) {
            String normalizedRegionName = normalizeRegionName(regionName);
            for (Map.Entry<String, String> entry : srZoneMap.entrySet()) {
                if (normalizedRegionName.equals(normalizeRegionName(entry.getValue()))) {
                    srsForRegion.add(entry.getKey());
                }
            }
            if (!srsForRegion.isEmpty()) {
                log.debug("  getAssignedSrsForRegion('{}') — from srZoneMap: {}", regionName, srsForRegion);
            }
        }

        // Log all srZoneMap entries for debugging if no SRs found
        if (srsForRegion.isEmpty() && srZoneMap != null && !srZoneMap.isEmpty()) {
            log.warn("  getAssignedSrsForRegion('{}') — NO MATCH. srZoneMap keys: {}, values: {}",
                    regionName, srZoneMap.keySet(),
                    srZoneMap.values().stream().distinct().collect(Collectors.toList()));
        }

        List<String> result = srsForRegion.stream()
                .filter(presentSrs::contains)
                .sorted()
                .collect(Collectors.toList());

        if (result.isEmpty() && !srsForRegion.isEmpty()) {
            log.warn("  getAssignedSrsForRegion('{}') — SRs {} found but none are present (present: {})",
                    regionName, srsForRegion, presentSrs);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private AllocationSummary buildSummary(String dateStr,
                                            List<Shipment> allShipments,
                                            List<String> presentSrs,
                                            Map<String, List<Shipment>> orderedAssignments,
                                            Map<String, ShiftWorkloadCalculatorService.WorkloadResult> workloadBySr,
                                            Map<String, String> srAffinityStatus,
                                            int allocatedCount,
                                            int unallocatedCount,
                                            int overflowCount,
                                            int noRegionCount,
                                            Map<String, Object> config,
                                            Map<String, List<Shipment>> regionShipments,
                                            List<String> operationalWarnings,
                                            Set<String> smallLeftoverRegions,
                                            boolean earningsImbalanceWarning,
                                            Map<String, Integer> srShiftDurations,
                                            Map<String, List<double[]>> territoryBoundaries) {
        List<SrSummaryDto> srSummaries = new ArrayList<>();
        int minShipments = Integer.MAX_VALUE, maxShipments = 0;
        long totalShipments = 0;

        // Build per-SR summaries
        Map<String, Double> utilisationBySr = new LinkedHashMap<>();
        for (String sr : presentSrs) {
            List<Shipment> srShipments = orderedAssignments.getOrDefault(sr, Collections.emptyList());
            ShiftWorkloadCalculatorService.WorkloadResult workload =
                    workloadBySr.getOrDefault(sr, new ShiftWorkloadCalculatorService.WorkloadResult(0, 0, 0, 0));

            int count = srShipments.size();
            totalShipments += count;
            if (count < minShipments) minShipments = count;
            if (count > maxShipments) maxShipments = count;

            double grossPayout = srShipments.stream().mapToDouble(Shipment::getExpectedPayout).sum();
            double distKm = routeOptimizerService.estimateDistanceKm(srShipments);
            double fuelCost = distKm * 2.5;
            double netEarnings = grossPayout - fuelCost;

            List<String> pincodes = srShipments.stream()
                    .map(Shipment::getDropPincode)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            int heavyCount = (int) srShipments.stream().filter(s -> s.getIsHeavy() == 1).count();

            int srShiftDuration = getShiftDurationForSr(sr, srShiftDurations);
            double utilisationPct = srShiftDuration > 0
                    ? Math.round((workload.totalMinutes() / srShiftDuration) * 1000.0) / 10.0
                    : 0.0;
            utilisationBySr.put(sr, utilisationPct);

            // ── Phase 8d: Determine operational warning for this SR ───────────
            String srWarning = null;
            if (utilisationPct > 100.0) {
                srWarning = "OVERLOADED";
                String warningMsg = String.format("SR %s is OVERLOADED (utilization: %.0f%%)", sr, utilisationPct);
                operationalWarnings.add(warningMsg);
                log.warn("AffinityShiftAllocationService: OPERATIONAL WARNING — {}", warningMsg);
            } else if (utilisationPct == 0.0) {
                // Check if this SR's region has shipments (idle only if region has work)
                String srRegion = getSrRegion(sr, config);
                if (srRegion != null) {
                    List<Shipment> regionShips = regionShipments.getOrDefault(srRegion, Collections.emptyList());
                    if (!regionShips.isEmpty()) {
                        srWarning = "IDLE";
                        String warningMsg = String.format("SR %s is IDLE (utilization: 0%%) in region %s with %d shipments",
                                sr, srRegion, regionShips.size());
                        operationalWarnings.add(warningMsg);
                        log.warn("AffinityShiftAllocationService: OPERATIONAL WARNING — {}", warningMsg);
                    }
                }
            }

            srSummaries.add(new SrSummaryDto(
                    sr, count, heavyCount, 0.0, distKm, pincodes,
                    grossPayout, fuelCost, netEarnings,
                    srAffinityStatus.getOrDefault(sr, "NON_AFFINITY"),
                    workload.totalMinutes(),
                    utilisationPct,
                    workload.handlingMinutes(),
                    workload.travelMinutes(),
                    workload.returnToHubMinutes(),
                    srWarning,
                    territoryBoundaries != null ? territoryBoundaries.get(sr) : null
            ));
        }

        if (presentSrs.isEmpty()) { minShipments = 0; }
        double avgShipments = presentSrs.isEmpty() ? 0.0 : (double) totalShipments / presentSrs.size();

        // ── Build per-region summaries ─────────────────────────────────────────
        List<RegionSummaryDto> regionSummaryList = buildRegionSummaries(
                config, presentSrs, orderedAssignments, utilisationBySr, regionShipments,
                smallLeftoverRegions);

        // ── Compute rebalancing suggestions ───────────────────────────────────
        List<SrRebalanceSuggestion> allSuggestions = computeRebalanceSuggestions(regionSummaryList, utilisationBySr);
        // Attach suggestions to the relevant region summaries
        Map<String, List<SrRebalanceSuggestion>> suggestionsByToRegion = allSuggestions.stream()
                .collect(Collectors.groupingBy(SrRebalanceSuggestion::toRegion));
        regionSummaryList = regionSummaryList.stream().map(rs -> {
            List<SrRebalanceSuggestion> regionSuggestions = suggestionsByToRegion.getOrDefault(rs.regionName(), Collections.emptyList());
            return new RegionSummaryDto(
                    rs.regionName(), rs.totalShipmentsInRegion(), rs.allocatedShipments(),
                    rs.overflowShipments(), rs.assignedSrCount(), rs.activeSrCount(),
                    rs.idleSrCount(), rs.assignedSrNames(), rs.activeSrNames(), rs.idleSrNames(),
                    rs.avgUtilisationPct(), rs.maxUtilisationPct(), rs.minUtilisationPct(),
                    rs.healthStatus(), regionSuggestions, rs.configurationWarnings(),
                    rs.smallLeftoverWarning()
            );
        }).collect(Collectors.toList());

        // ── Compute earnings metrics from per-SR summaries ──────────────────
        final double meanNetEarnings;
        final double earningsRange;
        final double earningsVariance;
        if (!srSummaries.isEmpty()) {
            double sumEarnings = srSummaries.stream().mapToDouble(SrSummaryDto::netEarnings).sum();
            meanNetEarnings = sumEarnings / srSummaries.size();
            double maxEarnings = srSummaries.stream().mapToDouble(SrSummaryDto::netEarnings).max().orElse(0.0);
            double minEarnings = srSummaries.stream().mapToDouble(SrSummaryDto::netEarnings).min().orElse(0.0);
            earningsRange = maxEarnings - minEarnings;
            final double mean = meanNetEarnings;
            double sumSquaredDiff = srSummaries.stream()
                    .mapToDouble(s -> Math.pow(s.netEarnings() - mean, 2))
                    .sum();
            earningsVariance = sumSquaredDiff / srSummaries.size();
        } else {
            meanNetEarnings = 0.0;
            earningsRange = 0.0;
            earningsVariance = 0.0;
        }

        return new AllocationSummary(
                dateStr,
                allShipments.size(),
                allocatedCount,
                unallocatedCount,
                0, 0,
                presentSrs.size(),
                minShipments == Integer.MAX_VALUE ? 0 : minShipments,
                maxShipments,
                avgShipments,
                0.0,
                srSummaries,
                earningsVariance, earningsRange, meanNetEarnings,
                "time-based",
                shiftDurationMinutes,
                overflowCount,
                noRegionCount,
                regionSummaryList,
                operationalWarnings,
                earningsImbalanceWarning
        );
    }

    /**
     * Build per-region health summaries from the allocation results.
     */
    @SuppressWarnings("unchecked")
    private List<RegionSummaryDto> buildRegionSummaries(
            Map<String, Object> config,
            List<String> presentSrs,
            Map<String, List<Shipment>> orderedAssignments,
            Map<String, Double> utilisationBySr,
            Map<String, List<Shipment>> regionShipments,
            Set<String> smallLeftoverRegions) {

        if (config == null) return Collections.emptyList();
        List<Map<String, Object>> regions = (List<Map<String, Object>>) config.get("regions");
        if (regions == null) return Collections.emptyList();

        List<RegionSummaryDto> result = new ArrayList<>();

        for (Map<String, Object> region : regions) {
            String regionName = (String) region.get("name");
            if (regionName == null) continue;

            // SRs assigned to this region
            List<String> assignedSrs = getAssignedSrsForRegion(regionName, config, presentSrs);

            // Shipments in this region
            List<Shipment> regionShips = regionShipments.getOrDefault(regionName, Collections.emptyList());

            // Allocated count
            int allocated = assignedSrs.stream()
                    .mapToInt(sr -> orderedAssignments.getOrDefault(sr, Collections.emptyList()).size())
                    .sum();

            // Total = max of original partition count and actual allocated (handles overflow redistribution)
            int totalInRegion = Math.max(regionShips.size(), allocated);

            // Overflow = only when original partition exceeds allocated (not negative)
            int overflow = Math.max(0, regionShips.size() - allocated);

            // Configuration warnings for this region
            List<String> regionWarnings = new ArrayList<>();
            if (assignedSrs.isEmpty() && totalInRegion > 0) {
                regionWarnings.add(String.format("Region %s has %d shipments but no assigned SRs",
                        regionName, totalInRegion));
            }

            // Active vs idle SRs
            List<String> activeSrs = assignedSrs.stream()
                    .filter(sr -> !orderedAssignments.getOrDefault(sr, Collections.emptyList()).isEmpty())
                    .collect(Collectors.toList());
            List<String> idleSrs = assignedSrs.stream()
                    .filter(sr -> orderedAssignments.getOrDefault(sr, Collections.emptyList()).isEmpty())
                    .collect(Collectors.toList());

            // Utilisation stats
            List<Double> utilisations = assignedSrs.stream()
                    .map(sr -> utilisationBySr.getOrDefault(sr, 0.0))
                    .collect(Collectors.toList());
            double avgUtil = utilisations.isEmpty() ? 0.0
                    : utilisations.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double maxUtil = utilisations.isEmpty() ? 0.0
                    : utilisations.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            double minUtil = utilisations.isEmpty() ? 0.0
                    : utilisations.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);

            String healthStatus = RegionSummaryDto.computeHealthStatus(overflow, idleSrs.size(), avgUtil);

            Boolean smallLeftoverWarning = smallLeftoverRegions.contains(regionName) ? Boolean.TRUE : null;

            result.add(new RegionSummaryDto(
                    regionName, totalInRegion, allocated, overflow,
                    assignedSrs.size(), activeSrs.size(), idleSrs.size(),
                    assignedSrs, activeSrs, idleSrs,
                    Math.round(avgUtil * 10.0) / 10.0,
                    Math.round(maxUtil * 10.0) / 10.0,
                    Math.round(minUtil * 10.0) / 10.0,
                    healthStatus,
                    Collections.emptyList(), // suggestions added later
                    regionWarnings,
                    smallLeftoverWarning
            ));
        }
        return result;
    }

    /**
     * Compute SR rebalancing suggestions across regions.
     *
     * Logic:
     * - Find OVERFLOW regions (need more SRs)
     * - Find IDLE_SRS or UNDERLOADED regions (have spare capacity)
     * - Suggest moving idle/underloaded SRs to overflow regions
     * - Prioritise: idle SRs first (HIGH), then underloaded (MEDIUM)
     */
    private List<SrRebalanceSuggestion> computeRebalanceSuggestions(List<RegionSummaryDto> regionSummaries, Map<String, Double> utilisationBySr) {
        List<SrRebalanceSuggestion> suggestions = new ArrayList<>();

        List<RegionSummaryDto> overflowRegions = regionSummaries.stream()
                .filter(r -> r.overflowShipments() > 0)
                .sorted(Comparator.comparingInt(RegionSummaryDto::overflowShipments).reversed())
                .collect(Collectors.toList());

        if (overflowRegions.isEmpty()) return suggestions;

        // Collect idle SRs from all regions (sorted by utilisation ascending)
        List<SrRebalanceSuggestion> idleSuggestions = new ArrayList<>();
        List<SrRebalanceSuggestion> underloadedSuggestions = new ArrayList<>();

        for (RegionSummaryDto sourceRegion : regionSummaries) {
            // Idle SRs (0% utilisation) — HIGH priority
            for (String idleSr : sourceRegion.idleSrNames()) {
                RegionSummaryDto bestTarget = overflowRegions.stream()
                        .filter(r -> !r.regionName().equals(sourceRegion.regionName()))
                        .findFirst().orElse(null);
                if (bestTarget != null) {
                    idleSuggestions.add(new SrRebalanceSuggestion(
                            idleSr,
                            sourceRegion.regionName(),
                            bestTarget.regionName(),
                            String.format("SR '%s' is idle (0%% utilisation) in %s. Moving to %s could allocate ~%d more shipments.",
                                    idleSr, sourceRegion.regionName(), bestTarget.regionName(), bestTarget.overflowShipments()),
                            0.0,
                            bestTarget.overflowShipments(),
                            "HIGH"
                    ));
                }
            }

            // Underloaded SRs (< 50% utilisation, but not idle) — MEDIUM priority
            if ("UNDERLOADED".equals(sourceRegion.healthStatus())) {
                if (sourceRegion.activeSrCount() <= 1) continue;

                // Find the least utilized active SR in this region
                String leastUtilizedSr = null;
                double leastUtil = Double.MAX_VALUE;
                for (String activeSr : sourceRegion.activeSrNames()) {
                    double srUtil = utilisationBySr.getOrDefault(activeSr, 0.0);
                    if (srUtil < leastUtil) {
                        leastUtil = srUtil;
                        leastUtilizedSr = activeSr;
                    }
                }

                // Only suggest if the least utilized SR is actually underloaded (< 50%)
                if (leastUtilizedSr != null && leastUtil < 50.0) {
                    RegionSummaryDto bestTarget = overflowRegions.stream()
                            .filter(r -> !r.regionName().equals(sourceRegion.regionName()))
                            .findFirst().orElse(null);
                    if (bestTarget != null) {
                        underloadedSuggestions.add(new SrRebalanceSuggestion(
                                leastUtilizedSr,
                                sourceRegion.regionName(),
                                bestTarget.regionName(),
                                String.format("SR '%s' is underloaded (%.1f%% utilisation) in %s. Moving to %s could help allocate %d overflow shipments.",
                                        leastUtilizedSr, leastUtil, sourceRegion.regionName(), bestTarget.regionName(), bestTarget.overflowShipments()),
                                leastUtil,
                                bestTarget.overflowShipments(),
                                "MEDIUM"
                        ));
                    }
                }
            }
        }

        suggestions.addAll(idleSuggestions);
        suggestions.addAll(underloadedSuggestions);
        return suggestions;
    }

    private AllocationSummary buildEmptyFallbackSummary(String dateStr, int totalShipments) {
        return new AllocationSummary(
                dateStr, totalShipments, 0, totalShipments,
                0, 0, 0, 0, 0, 0.0, 0.0, Collections.emptyList(),
                0.0, 0.0, 0.0,
                "count-based", null, null, null, null
        );
    }

    /**
     * Ray-casting point-in-polygon algorithm.
     * polygon points are [lat, lng].
     */
    private static boolean isPointInPolygon(double lat, double lng, List<double[]> polygon) {
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = polygon.get(i)[0]; // lat
            double xi = polygon.get(i)[1]; // lng
            double yj = polygon.get(j)[0];
            double xj = polygon.get(j)[1];
            if ((yi > lat) != (yj > lat) &&
                    lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Compute centroid of a polygon ring.
     * Ring points are [lng, lat].
     */
    private static double[] computeCentroid(List<double[]> ring) {
        double sumLng = 0, sumLat = 0;
        for (double[] p : ring) { sumLng += p[0]; sumLat += p[1]; }
        int n = ring.size();
        return new double[]{sumLng / n, sumLat / n}; // [lng, lat]
    }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // =========================================================================
    // Per-SR Shift Duration Support
    // =========================================================================

    /**
     * Extract the per-SR shift duration map from the affinity config.
     *
     * <p>The {@code srShiftDurations} field is stored alongside {@code srZoneMap} and
     * {@code regions} in the affinity config JSON. Each entry maps an SR name to its
     * configured shift duration in minutes (e.g., {@code {"SR-001": 480, "SR-005": 600}}).
     *
     * <p>When the field is absent or null in the config, returns an empty map — all SRs
     * fall back to the global {@code allocation.shift.duration.minutes} default.
     *
     * @param config the affinity config map loaded from disk
     * @return a non-null map of SR name → shift duration in minutes (empty if not configured)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Integer> extractSrShiftDurations(Map<String, Object> config) {
        if (config == null) {
            return Collections.emptyMap();
        }
        Object raw = config.get("srShiftDurations");
        if (raw == null) {
            return Collections.emptyMap();
        }
        if (raw instanceof Map) {
            Map<String, Object> rawMap = (Map<String, Object>) raw;
            Map<String, Integer> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                if (entry.getValue() instanceof Number) {
                    result.put(entry.getKey(), ((Number) entry.getValue()).intValue());
                }
            }
            return result;
        }
        return Collections.emptyMap();
    }

    /**
     * Returns the shift duration for a specific SR. If the SR has a configured
     * duration in the provided map, that value is returned. Otherwise, falls back
     * to the global {@code shiftDurationMinutes} field.
     *
     * @param srName           the SR name to look up
     * @param srShiftDurations per-SR shift duration map (may be null or empty)
     * @return the shift duration in minutes for the given SR
     */
    private int getShiftDurationForSr(String srName, Map<String, Integer> srShiftDurations) {
        if (srShiftDurations == null || srShiftDurations.isEmpty()) {
            return shiftDurationMinutes;
        }
        Integer duration = srShiftDurations.get(srName);
        return duration != null ? duration : shiftDurationMinutes;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /** Result of dense-packing a region: per-SR assignments + overflow. */
    public record DensePackResult(Map<String, List<Shipment>> assignments, List<Shipment> overflow) {}
}
