package com.example.LMrouting.service;

import com.example.LMrouting.dto.AllocationSummary;
import com.example.LMrouting.dto.RegionSummaryDto;
import com.example.LMrouting.dto.SrSummaryDto;
import com.example.LMrouting.model.Shipment;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Bug Condition Exploration Property Test for Time-Based Allocation.
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10</b></p>
 *
 * <p>Property 1: Bug Condition — Time-Based Allocation Produces Suboptimal Routes,
 * Inconsistent ETAs, and Missing Operational Intelligence</p>
 *
 * <p>CRITICAL: These tests are EXPECTED TO FAIL on unfixed code — failure confirms the bugs exist.
 * DO NOT attempt to fix the test or the code when it fails.</p>
 *
 * <p>This test exercises the time-based allocation pipeline with realistic shipment data
 * and asserts the expected (fixed) behavior. On unfixed code, these assertions will fail,
 * surfacing counterexamples that demonstrate the bugs.</p>
 */
class TimeBasedAllocationBugConditionTest {

    // Pune hub coordinates
    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;
    // Pune delivery area bounds
    private static final double LAT_MIN = 18.40;
    private static final double LAT_MAX = 18.62;
    private static final double LNG_MIN = 73.75;
    private static final double LNG_MAX = 74.05;

    // =========================================================================
    // Property 1: Cluster-First Route-Second Produces Shorter Routes
    // Validates: Requirements 2.1, 2.2
    // =========================================================================

    /**
     * For any set of ≥ 10 shipments distributed around the hub, the cluster-first
     * route-second optimizer SHALL produce a route with total travel time ≤ the
     * travel time produced by nearest-neighbour + 2-opt.
     *
     * <p>On UNFIXED code: The system only uses nearest-neighbour + 2-opt, so there
     * is no cluster-first optimizer to compare against. We implement a simple angular
     * sweep + intra-cluster 2-opt here to demonstrate the improvement potential.</p>
     */
    @Property(tries = 50)
    void clusterFirstProducesShorterOrEqualRoutes(
            @ForAll("shipmentsForRouting") List<Shipment> shipments) throws Exception {

        TravelTimeCacheService cache = buildCache();
        TwoOptRouteOptimizer twoOpt = new TwoOptRouteOptimizer();

        // Current approach: nearest-neighbour + 2-opt
        List<Shipment> nnOrder = nearestNeighbourOrder(shipments, HUB_LAT, HUB_LNG);
        List<Shipment> nnTwoOptRoute = twoOpt.optimize(nnOrder, cache, HUB_LAT, HUB_LNG, 100);
        double nnTwoOptTime = twoOpt.routeTime(nnTwoOptRoute, cache, HUB_LAT, HUB_LNG);

        // Expected approach: cluster-first route-second (angular sweep + intra-cluster 2-opt)
        List<Shipment> cfrsRoute = clusterFirstRouteSecond(shipments, cache, twoOpt);
        double cfrsTime = twoOpt.routeTime(cfrsRoute, cache, HUB_LAT, HUB_LNG);

        // Property: CFRS route time ≤ NN+2-opt route time
        // On unfixed code, the system doesn't use CFRS, so this demonstrates the gap
        assertThat(cfrsTime)
                .as("Cluster-first route (%.2f min) should be ≤ nearest-neighbour+2-opt route (%.2f min) for %d shipments",
                        cfrsTime, nnTwoOptTime, shipments.size())
                .isLessThanOrEqualTo(nnTwoOptTime);
    }

    // =========================================================================
    // Property 2: Consistent ETA Computation
    // Validates: Requirements 2.3
    // =========================================================================

    /**
     * For any time-based allocation, the ETA used during dense-packing decisions
     * and the ETA displayed in the SR timeline SHALL use the same travel-time
     * computation method, ensuring divergence remains within ±5%.
     *
     * <p>On UNFIXED code: The packing uses getRouteTimeHaversine() while the timeline
     * uses getRouteTime() (ORS/Google Maps fallback), causing systematic divergence.</p>
     */
    @Property(tries = 50)
    void etaDivergenceBetweenPackingAndTimelineWithinFivePercent(
            @ForAll("shipmentsForRouting") List<Shipment> shipments) throws Exception {

        TravelTimeCacheService cache = buildCache();

        // Packing ETA: uses getRouteTimeHaversine (Haversine-only)
        List<double[]> waypoints = shipments.stream()
                .map(s -> new double[]{s.getDropLatitude(), s.getDropLongitude()})
                .toList();
        double packingEta = cache.getRouteTimeHaversine(waypoints);

        // Timeline ETA: uses getRouteTime (which calls ORS → Google Maps → Haversine fallback)
        // In test environment without API keys, this falls back to Haversine too,
        // but with a different code path that may produce different results
        double timelineEta = cache.getRouteTime(waypoints);

        // Compute divergence
        double maxEta = Math.max(packingEta, timelineEta);
        double divergence = (maxEta > 0) ? Math.abs(packingEta - timelineEta) / maxEta : 0.0;

        // Property: divergence ≤ 5%
        // On unfixed code, the two code paths may diverge when external APIs are available
        // Even without APIs, the code paths should be identical (same Haversine) — 
        // this test documents the architectural issue that TWO separate paths exist
        assertThat(divergence)
                .as("ETA divergence between packing (%.2f min) and timeline (%.2f min) should be ≤ 5%% but was %.2f%%",
                        packingEta, timelineEta, divergence * 100)
                .isLessThanOrEqualTo(0.05);
    }

    // =========================================================================
    // Property 3: Earnings Balancing Enforcement
    // Validates: Requirements 2.4
    // =========================================================================

    /**
     * For any time-based allocation with ≥ 2 active SRs, at least 50% of active SRs
     * SHALL have net earnings within ±20% of the median SR earnings.
     *
     * <p>On UNFIXED code: No earnings balancing pass exists. The dense-packing fills
     * SRs sequentially (minimum-manpower), which can produce extreme earnings imbalance.</p>
     */
    @Property(tries = 50)
    void earningsBalanceRatioAtLeastFiftyPercent(
            @ForAll("shipmentsWithEarnings") List<Shipment> shipments,
            @ForAll @IntRange(min = 2, max = 5) int srCount) throws Exception {

        // Simulate dense packing: distribute shipments across SRs sequentially (current behavior)
        List<List<Shipment>> srAssignments = distributeSequentially(shipments, srCount);

        // Compute net earnings per SR
        List<Double> earnings = srAssignments.stream()
                .filter(sr -> !sr.isEmpty())
                .map(sr -> sr.stream().mapToDouble(Shipment::getExpectedPayout).sum())
                .sorted()
                .collect(Collectors.toList());

        if (earnings.size() < 2) return; // Need at least 2 active SRs

        // Compute median
        double median = earnings.get(earnings.size() / 2);
        if (median <= 0) return; // Skip degenerate case

        // Count SRs within ±20% of median
        long withinThreshold = earnings.stream()
                .filter(e -> Math.abs(e - median) / median <= 0.20)
                .count();

        double balanceRatio = (double) withinThreshold / earnings.size();

        // Property: at least 50% of SRs within ±20% of median
        assertThat(balanceRatio)
                .as("Earnings balance ratio should be ≥ 0.50 but was %.2f (earnings: %s, median: %.2f)",
                        balanceRatio, earnings, median)
                .isGreaterThanOrEqualTo(0.50);
    }

    // =========================================================================
    // Property 4: Region Load Classification
    // Validates: Requirements 2.5
    // =========================================================================

    /**
     * For any time-based allocation, each region SHALL be classified as UNDERLOADED,
     * HEALTHY, or OVERLOADED, and the classification SHALL be included in the response.
     *
     * <p>On UNFIXED code: The RegionSummaryDto.computeHealthStatus() uses different
     * thresholds (OVERFLOW/IDLE_SRS/UNDERLOADED/HEALTHY) that don't match the required
     * classification (UNDERLOADED < 50%, HEALTHY 50-90%, OVERLOADED > 90% or overflow > 0).</p>
     */
    @Property(tries = 50)
    void regionLoadClassificationIsPresentAndValid(
            @ForAll @IntRange(min = 10, max = 60) int shipmentCount,
            @ForAll @IntRange(min = 1, max = 5) int srCount) {

        // Simulate region metrics
        double avgUtilisation = (double) shipmentCount / (srCount * 50.0) * 100.0; // rough estimate
        int overflow = (shipmentCount > srCount * 50) ? shipmentCount - srCount * 50 : 0;

        // Current implementation's classification
        String currentStatus = RegionSummaryDto.computeHealthStatus(overflow, 0, avgUtilisation);

        // Expected classification per design:
        // UNDERLOADED (< 50%), HEALTHY (50-90%), OVERLOADED (> 90% or overflow > 0)
        Set<String> validStatuses = Set.of("UNDERLOADED", "HEALTHY", "OVERLOADED");

        // The current implementation uses "OVERFLOW" and "IDLE_SRS" which are NOT in the
        // required set {UNDERLOADED, HEALTHY, OVERLOADED}
        assertThat(validStatuses)
                .as("Region classification '%s' should be one of %s (avgUtil=%.1f%%, overflow=%d)",
                        currentStatus, validStatuses, avgUtilisation, overflow)
                .contains(currentStatus);
    }

    // =========================================================================
    // Property 5: Small Leftover Handling
    // Validates: Requirements 2.8
    // =========================================================================

    /**
     * For any region where fewer than 10 leftover shipments remain after dense packing
     * and consolidation fails, those shipments SHALL be marked as unallocated rather
     * than activating an additional SR.
     *
     * <p>On UNFIXED code: The system attempts consolidation but if it fails, it may
     * still activate a new SR for small leftover counts.</p>
     */
    @Property(tries = 50)
    void smallLeftoversDoNotActivateNewSRs(
            @ForAll("smallLeftoverScenario") SmallLeftoverInput input) throws Exception {

        TravelTimeCacheService cache = buildCache();
        ShiftWorkloadCalculatorService workloadCalc = buildWorkloadCalculator(cache);
        AffinityShiftAllocationService service = buildAllocationService(cache, workloadCalc);

        // Create a scenario with small leftover (< 10 shipments)
        List<Shipment> leftoverShipments = input.leftoverShipments();
        List<String> assignedSrs = input.assignedSrs();

        // Run dense packing with the leftover shipments and existing SRs
        // The SRs are already "full" (we simulate by giving them a full workload)
        AffinityShiftAllocationService.DensePackResult result =
                service.densePackRegion(leftoverShipments, assignedSrs);

        // Count how many SRs were activated (have at least 1 shipment)
        long activatedSrs = result.assignments().values().stream()
                .filter(list -> !list.isEmpty())
                .count();

        // Property: If leftover < 10 and consolidation fails (overflow exists),
        // no NEW SR should be activated for just those leftovers
        if (!result.overflow().isEmpty() && leftoverShipments.size() < 10) {
            // On unfixed code, the system may still try to activate SRs for small leftovers
            // The fix should mark these as unallocated instead
            assertThat(result.overflow())
                    .as("Small leftover (%d shipments < 10) should remain as overflow/unallocated, " +
                            "not activate new SRs. Got %d activated SRs with %d overflow.",
                            leftoverShipments.size(), activatedSrs, result.overflow().size())
                    .hasSize(leftoverShipments.size());
        }
    }

    // =========================================================================
    // Property 6: No-SR Region Warning
    // Validates: Requirements 2.9
    // =========================================================================

    /**
     * For any region that contains shipments but has no assigned SRs, the system
     * SHALL include a configuration warning in the region load panel.
     *
     * <p>On UNFIXED code: Shipments in regions with no SRs silently move to the
     * no-region pool without any warning to the supervisor.</p>
     */
    @Property(tries = 30)
    void noSrRegionProducesConfigurationWarning(
            @ForAll @IntRange(min = 10, max = 60) int shipmentCount) {

        // Simulate a region with shipments but no assigned SRs
        // On unfixed code, there's no mechanism to produce configuration warnings
        // for this scenario — the AllocationSummary doesn't have an operationalWarnings field

        // Check if AllocationSummary has operationalWarnings field
        boolean hasOperationalWarnings = false;
        try {
            AllocationSummary.class.getRecordComponents();
            // Check if the record has an operationalWarnings component
            hasOperationalWarnings = Arrays.stream(AllocationSummary.class.getRecordComponents())
                    .anyMatch(rc -> rc.getName().equals("operationalWarnings"));
        } catch (Exception e) {
            // Reflection failed
        }

        // Property: The system must have an operationalWarnings field to surface no-SR warnings
        assertThat(hasOperationalWarnings)
                .as("AllocationSummary should have 'operationalWarnings' field to surface " +
                        "configuration warnings for regions with %d shipments but no assigned SRs",
                        shipmentCount)
                .isTrue();
    }

    // =========================================================================
    // Property 7: Overloaded/Idle SR Flagging
    // Validates: Requirements 2.10
    // =========================================================================

    /**
     * For any SR with utilization > 100% or utilization = 0% despite being assigned
     * to a region with shipments, the system SHALL flag these conditions as operational warnings.
     *
     * <p>On UNFIXED code: No flagging mechanism exists. SrSummaryDto doesn't have
     * a warnings/flags field, and AllocationSummary doesn't have operationalWarnings.</p>
     */
    @Property(tries = 30)
    void overloadedOrIdleSrsAreFlagged(
            @ForAll @IntRange(min = 10, max = 60) int shipmentCount,
            @ForAll @IntRange(min = 2, max = 5) int srCount) {

        // Simulate: one SR gets all shipments (overloaded), others get none (idle)
        // On unfixed code, there's no mechanism to flag these conditions

        // Check if AllocationSummary has operationalWarnings field
        boolean hasOperationalWarnings = Arrays.stream(AllocationSummary.class.getRecordComponents())
                .anyMatch(rc -> rc.getName().equals("operationalWarnings"));

        // Check if SrSummaryDto has any warning/flag field
        boolean hasWarningField = Arrays.stream(SrSummaryDto.class.getRecordComponents())
                .anyMatch(rc -> rc.getName().contains("warning") || rc.getName().contains("flag")
                        || rc.getName().contains("Warning") || rc.getName().contains("Flag"));

        // Property: The system must have warning mechanisms for overloaded/idle SRs
        assertThat(hasOperationalWarnings || hasWarningField)
                .as("System should have warning mechanism (operationalWarnings in AllocationSummary " +
                        "or warning field in SrSummaryDto) to flag overloaded/idle SRs " +
                        "when %d shipments distributed across %d SRs",
                        shipmentCount, srCount)
                .isTrue();
    }

    // =========================================================================
    // Providers
    // =========================================================================

    @Provide
    Arbitrary<List<Shipment>> shipmentsForRouting() {
        return shipmentArbitrary().list().ofMinSize(10).ofMaxSize(45);
    }

    @Provide
    Arbitrary<List<Shipment>> shipmentsWithEarnings() {
        return shipmentWithEarningsArbitrary().list().ofMinSize(15).ofMaxSize(60);
    }

    @Provide
    Arbitrary<SmallLeftoverInput> smallLeftoverScenario() {
        Arbitrary<List<Shipment>> leftoverShipments = shipmentArbitrary().list().ofMinSize(3).ofMaxSize(9);
        Arbitrary<List<String>> srs = Arbitraries.integers().between(1, 3)
                .map(n -> IntStream.rangeClosed(1, n)
                        .mapToObj(i -> "SR-" + String.format("%03d", i))
                        .collect(Collectors.toList()));

        return Combinators.combine(leftoverShipments, srs).as(SmallLeftoverInput::new);
    }

    // =========================================================================
    // Helper: Angular Sweep Cluster-First Route-Second
    // =========================================================================

    /**
     * Reference implementation of cluster-first route-second using angular sweep.
     * This demonstrates the improvement potential over nearest-neighbour + 2-opt.
     */
    private List<Shipment> clusterFirstRouteSecond(List<Shipment> shipments,
                                                    TravelTimeCacheService cache,
                                                    TwoOptRouteOptimizer twoOpt) {
        if (shipments.size() <= 6) {
            // For very small sets, just use 2-opt directly
            return twoOpt.optimize(new ArrayList<>(shipments), cache, HUB_LAT, HUB_LNG, 100);
        }

        int maxClusterSize = 12;

        // Step 1: Compute polar angles from hub
        List<ShipmentWithAngle> withAngles = shipments.stream()
                .map(s -> new ShipmentWithAngle(s,
                        Math.atan2(s.getDropLatitude() - HUB_LAT, s.getDropLongitude() - HUB_LNG)))
                .sorted(Comparator.comparingDouble(ShipmentWithAngle::angle))
                .collect(Collectors.toList());

        // Step 2: Partition into clusters by angular sweep
        List<List<Shipment>> clusters = new ArrayList<>();
        List<Shipment> currentCluster = new ArrayList<>();

        for (int i = 0; i < withAngles.size(); i++) {
            currentCluster.add(withAngles.get(i).shipment());

            if (currentCluster.size() >= maxClusterSize) {
                clusters.add(new ArrayList<>(currentCluster));
                currentCluster.clear();
            } else if (i < withAngles.size() - 1) {
                // Check for natural angular gap (> 2× median gap)
                double gap = withAngles.get(i + 1).angle() - withAngles.get(i).angle();
                double medianGap = estimateMedianGap(withAngles);
                if (gap > 2 * medianGap && currentCluster.size() >= 3) {
                    clusters.add(new ArrayList<>(currentCluster));
                    currentCluster.clear();
                }
            }
        }
        if (!currentCluster.isEmpty()) {
            clusters.add(currentCluster);
        }

        // Step 3: Intra-cluster 2-opt
        List<Shipment> finalRoute = new ArrayList<>();
        for (List<Shipment> cluster : clusters) {
            List<Shipment> optimizedCluster = twoOpt.optimize(cluster, cache, HUB_LAT, HUB_LNG, 100);
            finalRoute.addAll(optimizedCluster);
        }

        return finalRoute;
    }

    private double estimateMedianGap(List<ShipmentWithAngle> sorted) {
        if (sorted.size() < 2) return Double.MAX_VALUE;
        List<Double> gaps = new ArrayList<>();
        for (int i = 0; i < sorted.size() - 1; i++) {
            gaps.add(sorted.get(i + 1).angle() - sorted.get(i).angle());
        }
        Collections.sort(gaps);
        return gaps.get(gaps.size() / 2);
    }

    // =========================================================================
    // Helper: Nearest-Neighbour Order (mirrors AffinityShiftAllocationService)
    // =========================================================================

    private List<Shipment> nearestNeighbourOrder(List<Shipment> shipments, double hubLat, double hubLng) {
        if (shipments == null || shipments.isEmpty()) return new ArrayList<>();
        if (shipments.size() == 1) return new ArrayList<>(shipments);

        List<Shipment> remaining = new ArrayList<>(shipments);
        List<Shipment> ordered = new ArrayList<>(shipments.size());
        double curLat = hubLat, curLng = hubLng;

        while (!remaining.isEmpty()) {
            double minDist = Double.MAX_VALUE;
            Shipment nearest = null;
            for (Shipment s : remaining) {
                double dist = haversineDistance(curLat, curLng, s.getDropLatitude(), s.getDropLongitude());
                if (dist < minDist) { minDist = dist; nearest = s; }
            }
            remaining.remove(nearest);
            ordered.add(nearest);
            curLat = nearest.getDropLatitude();
            curLng = nearest.getDropLongitude();
        }
        return ordered;
    }

    private double haversineDistance(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    // =========================================================================
    // Helper: Sequential Distribution (simulates current dense-packing behavior)
    // =========================================================================

    private List<List<Shipment>> distributeSequentially(List<Shipment> shipments, int srCount) {
        List<List<Shipment>> assignments = new ArrayList<>();
        for (int i = 0; i < srCount; i++) assignments.add(new ArrayList<>());

        // Current behavior: fill first SR to capacity, then next, etc.
        // This creates earnings imbalance because first SR gets high-value shipments
        int perSr = (int) Math.ceil((double) shipments.size() / srCount);
        int idx = 0;
        for (int sr = 0; sr < srCount && idx < shipments.size(); sr++) {
            int end = Math.min(idx + perSr, shipments.size());
            // Skew: first SR gets more, last gets fewer (simulating minimum-manpower)
            if (sr == 0) {
                end = Math.min(idx + (int)(perSr * 1.5), shipments.size());
            }
            assignments.get(sr).addAll(shipments.subList(idx, end));
            idx = end;
        }
        return assignments;
    }

    // =========================================================================
    // Helper: Build services for testing
    // =========================================================================

    private TravelTimeCacheService buildCache() throws Exception {
        OpenRouteService ors = mock(OpenRouteService.class);
        when(ors.isConfigured()).thenReturn(false);
        GoogleMapsService gms = mock(GoogleMapsService.class);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        TravelTimeCacheService cache = new TravelTimeCacheService(ors, gms);
        setField(cache, "hubLat", HUB_LAT);
        setField(cache, "hubLng", HUB_LNG);
        setField(cache, "avgSpeedKmh", 20.0);
        return cache;
    }

    private ShiftWorkloadCalculatorService buildWorkloadCalculator(TravelTimeCacheService cache) throws Exception {
        ShiftWorkloadCalculatorService calc = new ShiftWorkloadCalculatorService(cache);
        setField(calc, "hubLat", HUB_LAT);
        setField(calc, "hubLng", HUB_LNG);
        setField(calc, "handlingTimeCod", 6.0);
        setField(calc, "handlingTimePrepaid", 5.0);
        setField(calc, "handlingTimeDefault", 5.0);
        setField(calc, "breakBufferMinutes", 30.0);
        return calc;
    }

    private AffinityShiftAllocationService buildAllocationService(
            TravelTimeCacheService cache,
            ShiftWorkloadCalculatorService workloadCalc) throws Exception {

        ClusterFirstRouteOptimizer cfro = new ClusterFirstRouteOptimizer(cache);
        setField(cfro, "maxClusterSize", 12);

        AffinityShiftAllocationService service = new AffinityShiftAllocationService(
                null, // InMemoryStore - not needed for densePackRegion
                null, // AffinityConfigStorageService
                cache,
                workloadCalc,
                null, // RouteOptimizerService
                null, // HubBoundaryService
                null, // PincodeBoundaryService
                cfro,
                new LegacyRouteOptimizer(cache),
                new EarningsBalancingService(workloadCalc, null),
                new TerritoryPartitionService(workloadCalc)
        );
        setField(service, "hubLat", HUB_LAT);
        setField(service, "hubLng", HUB_LNG);
        setField(service, "shiftDurationMinutes", 480);
        setField(service, "twoOptMaxIterations", 100);
        setField(service, "targetUtilisation", 0.88);
        setField(service, "routeOptimizerStrategy", "cluster-first");
        return service;
    }

    // =========================================================================
    // Arbitrary generators
    // =========================================================================

    private Arbitrary<Shipment> shipmentArbitrary() {
        Arbitrary<Double> latArb = Arbitraries.doubles().between(LAT_MIN, LAT_MAX).ofScale(5);
        Arbitrary<Double> lngArb = Arbitraries.doubles().between(LNG_MIN, LNG_MAX).ofScale(5);
        Arbitrary<String> orderTypeArb = Arbitraries.of("COD", "Prepaid");

        return Combinators.combine(latArb, lngArb, orderTypeArb)
                .as((lat, lng, orderType) -> Shipment.builder()
                        .shippingId("SHP-" + UUID.randomUUID().toString().substring(0, 8))
                        .dropLatitude(lat)
                        .dropLongitude(lng)
                        .orderType(orderType)
                        .dropPincode("411" + String.format("%03d", (int)(Math.random() * 100)))
                        .expectedPayout(0.0)
                        .rate(0.0)
                        .build());
    }

    private Arbitrary<Shipment> shipmentWithEarningsArbitrary() {
        Arbitrary<Double> latArb = Arbitraries.doubles().between(LAT_MIN, LAT_MAX).ofScale(5);
        Arbitrary<Double> lngArb = Arbitraries.doubles().between(LNG_MIN, LNG_MAX).ofScale(5);
        Arbitrary<String> orderTypeArb = Arbitraries.of("COD", "Prepaid");
        // Realistic payout range: ₹20-₹200 per shipment
        Arbitrary<Double> payoutArb = Arbitraries.doubles().between(20.0, 200.0).ofScale(2);

        return Combinators.combine(latArb, lngArb, orderTypeArb, payoutArb)
                .as((lat, lng, orderType, payout) -> Shipment.builder()
                        .shippingId("SHP-" + UUID.randomUUID().toString().substring(0, 8))
                        .dropLatitude(lat)
                        .dropLongitude(lng)
                        .orderType(orderType)
                        .dropPincode("411" + String.format("%03d", (int)(Math.random() * 100)))
                        .expectedPayout(payout)
                        .rate(payout * 0.8)
                        .build());
    }

    // =========================================================================
    // Records
    // =========================================================================

    record ShipmentWithAngle(Shipment shipment, double angle) {}

    record SmallLeftoverInput(List<Shipment> leftoverShipments, List<String> assignedSrs) {}

    // =========================================================================
    // Reflection helpers
    // =========================================================================

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
}
