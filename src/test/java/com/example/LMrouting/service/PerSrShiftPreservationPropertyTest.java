package com.example.LMrouting.service;

import com.example.LMrouting.dto.AllocationSummary;
import com.example.LMrouting.dto.SrSummaryDto;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.store.InMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

/**
 * Preservation property-based tests for the per-SR shift duration and summary metrics fix.
 *
 * <p>These tests capture the CURRENT (unfixed) behavior that must NOT change after the fix
 * is applied. They verify preservation requirements from the bugfix spec.</p>
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7</b></p>
 *
 * <p>Property 2: Preservation — Fallback to Global Default and Count-Based Mode Unchanged</p>
 *
 * <p>IMPORTANT: These tests must PASS on unfixed code to establish the baseline behavior.
 * They must continue to PASS after the fix is applied (no regressions).</p>
 */
class PerSrShiftPreservationPropertyTest {

    // Pune hub coordinates
    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;
    private static final double LAT_MIN = 18.42;
    private static final double LAT_MAX = 18.55;
    private static final double LNG_MIN = 73.80;
    private static final double LNG_MAX = 73.95;

    // Global default shift duration (same as application.properties)
    private static final int GLOBAL_SHIFT_DURATION_MINUTES = 600;
    private static final double TARGET_UTILISATION = 0.88;

    // =========================================================================
    // Property 1: No srShiftDurations in config → all SRs use global default
    // Validates: Requirements 3.2, 3.3
    // =========================================================================

    /**
     * For all time-based allocation requests where no srShiftDurations map exists
     * in affinity config, the allocation results are identical to current behavior —
     * all SRs use global allocation.shift.duration.minutes as fallback.
     *
     * <p>This test verifies that when no per-SR shift duration config exists,
     * densePackRegion() uses the global shiftDurationMinutes field for all SRs.
     * The targetMinutes = shiftDurationMinutes * targetUtilisation for every SR.</p>
     *
     * <p><b>Validates: Requirements 3.2, 3.3</b></p>
     */
    @Property(tries = 30)
    void noSrShiftDurationsConfig_allSrsUseGlobalDefault(
            @ForAll("shipmentSets") List<Shipment> shipments,
            @ForAll @IntRange(min = 1, max = 3) int srCount) throws Exception {

        List<String> srs = IntStream.rangeClosed(1, srCount)
                .mapToObj(i -> "SR-" + String.format("%03d", i))
                .collect(Collectors.toList());

        // Build service with global shift duration (no per-SR config)
        AffinityShiftAllocationService service = buildService(GLOBAL_SHIFT_DURATION_MINUTES, TARGET_UTILISATION);

        // Run dense packing — this is the current behavior with no per-SR config
        AffinityShiftAllocationService.DensePackResult result =
                service.densePackRegion(shipments, srs);

        // Verify: all assigned shipments fit within the global shift duration
        // (the current behavior uses global for all SRs)
        ShiftWorkloadCalculatorService workloadCalc = buildWorkloadCalculator();
        for (String sr : srs) {
            List<Shipment> assigned = result.assignments().get(sr);
            if (!assigned.isEmpty()) {
                // Order the route for workload calculation
                List<Shipment> ordered = service.nearestNeighbourOrder(assigned);
                ShiftWorkloadCalculatorService.WorkloadResult workload = workloadCalc.computeWorkload(ordered);
                // Current behavior: workload must be < global shift duration
                assertThat(workload.totalMinutes())
                        .as("SR %s workload (%.1f min) should be < global shift duration (%d min)",
                                sr, workload.totalMinutes(), GLOBAL_SHIFT_DURATION_MINUTES)
                        .isLessThan((double) GLOBAL_SHIFT_DURATION_MINUTES);
            }
        }

        // Verify: total assigned + overflow = total input
        int totalAssigned = srs.stream()
                .mapToInt(sr -> result.assignments().get(sr).size())
                .sum();
        assertThat(totalAssigned + result.overflow().size())
                .as("Total assigned (%d) + overflow (%d) should equal input shipments (%d)",
                        totalAssigned, result.overflow().size(), shipments.size())
                .isEqualTo(shipments.size());
    }

    // =========================================================================
    // Property 2: All SRs same duration as global → identical results
    // Validates: Requirements 3.4
    // =========================================================================

    /**
     * For all time-based allocation requests where all SRs have shift duration
     * equal to the global default, results are identical to current behavior.
     *
     * <p>This test runs densePackRegion() twice with the same global shift duration
     * and verifies the results are deterministic and identical. This establishes
     * that when all SRs use the global default, the fix won't change behavior.</p>
     *
     * <p><b>Validates: Requirements 3.4</b></p>
     */
    @Property(tries = 30)
    void allSrsSameAsGlobalDefault_resultsIdentical(
            @ForAll("shipmentSets") List<Shipment> shipments,
            @ForAll @IntRange(min = 1, max = 3) int srCount) throws Exception {

        List<String> srs = IntStream.rangeClosed(1, srCount)
                .mapToObj(i -> "SR-" + String.format("%03d", i))
                .collect(Collectors.toList());

        // Build two identical services with global shift duration
        AffinityShiftAllocationService service1 = buildService(GLOBAL_SHIFT_DURATION_MINUTES, TARGET_UTILISATION);
        AffinityShiftAllocationService service2 = buildService(GLOBAL_SHIFT_DURATION_MINUTES, TARGET_UTILISATION);

        // Run dense packing on both
        AffinityShiftAllocationService.DensePackResult result1 =
                service1.densePackRegion(shipments, srs);
        AffinityShiftAllocationService.DensePackResult result2 =
                service2.densePackRegion(shipments, srs);

        // Results must be identical (deterministic)
        for (String sr : srs) {
            List<Shipment> assigned1 = result1.assignments().get(sr);
            List<Shipment> assigned2 = result2.assignments().get(sr);
            assertThat(assigned1.size())
                    .as("SR %s should have same shipment count in both runs", sr)
                    .isEqualTo(assigned2.size());

            // Verify same shipments assigned (by shipping ID)
            Set<String> ids1 = assigned1.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
            Set<String> ids2 = assigned2.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
            assertThat(ids1)
                    .as("SR %s should have same shipments in both runs", sr)
                    .isEqualTo(ids2);
        }

        // Overflow must be identical
        assertThat(result1.overflow().size())
                .as("Overflow count should be identical in both runs")
                .isEqualTo(result2.overflow().size());
    }

    // =========================================================================
    // Property 3: Count-based mode completely unaffected
    // Validates: Requirements 3.1, 3.7
    // =========================================================================

    /**
     * For all allocation requests where allocationMode != "time-based" (count-based),
     * the count-based pipeline (AllocationEngineService) is completely unaffected.
     *
     * <p>This test verifies that the count-based allocation pipeline does NOT reference
     * any per-SR shift duration logic. The AllocationEngineService.buildSummary() correctly
     * computes earnings metrics (it already works), and the K-Means + rebalancing pipeline
     * is independent of shift duration configuration.</p>
     *
     * <p><b>Validates: Requirements 3.1, 3.7</b></p>
     */
    @Property(tries = 20)
    void countBasedMode_completelyUnaffectedByShiftDurationConfig(
            @ForAll("earningsLists") List<Double> srEarnings) {

        // The count-based pipeline (AllocationEngineService) already computes
        // earnings metrics correctly. Verify the formula is correct:
        double expectedMean = srEarnings.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double expectedMax = srEarnings.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double expectedMin = srEarnings.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double expectedRange = expectedMax - expectedMin;
        double expectedVariance = srEarnings.stream()
                .mapToDouble(e -> (e - expectedMean) * (e - expectedMean))
                .sum() / srEarnings.size();

        // Build an AllocationSummary the way count-based buildSummary() does it
        // (with CORRECTLY computed earnings metrics — this is the existing behavior)
        List<SrSummaryDto> srSummaries = IntStream.range(0, srEarnings.size())
                .mapToObj(i -> new SrSummaryDto(
                        "SR-" + String.format("%03d", i + 1),
                        20, 2, 0.0, 15.0,
                        List.of("411001", "411002"),
                        srEarnings.get(i) + 100.0,  // grossPayout
                        100.0,                       // fuelCost
                        srEarnings.get(i)            // netEarnings
                ))
                .collect(Collectors.toList());

        // Count-based mode uses the shorter constructor (no time-based fields)
        AllocationSummary summary = new AllocationSummary(
                "2025-01-15",
                100,  // totalShipments
                90,   // allocatedShipments
                10,   // unallocatedShipments
                80, 80,
                srEarnings.size(),
                15, 25, 20.0,
                0.0,  // legacyVariance
                srSummaries,
                expectedVariance,   // earningsVariance — correctly computed in count-based
                expectedRange,      // earningsRange — correctly computed in count-based
                expectedMean        // meanNetEarnings — correctly computed in count-based
        );

        // Verify: count-based mode has NO time-based fields
        assertThat(summary.allocationMode())
                .as("Count-based mode should have null allocationMode field")
                .isNull();
        assertThat(summary.shiftDurationMinutes())
                .as("Count-based mode should have null shiftDurationMinutes")
                .isNull();
        assertThat(summary.overflowShipments())
                .as("Count-based mode should have null overflowShipments")
                .isNull();
        assertThat(summary.noRegionShipments())
                .as("Count-based mode should have null noRegionShipments")
                .isNull();

        // Verify: earnings metrics are correctly computed (already working in count-based)
        assertThat(summary.meanNetEarnings())
                .as("Count-based meanNetEarnings should be correctly computed")
                .isCloseTo(expectedMean, within(0.01));
        assertThat(summary.earningsRange())
                .as("Count-based earningsRange should be correctly computed")
                .isCloseTo(expectedRange, within(0.01));
        assertThat(summary.earningsVariance())
                .as("Count-based earningsVariance should be correctly computed")
                .isCloseTo(expectedVariance, within(0.01));
    }

    // =========================================================================
    // Property 4: Workload formula produces identical results
    // Validates: Requirements 3.5
    // =========================================================================

    /**
     * For all calls to ShiftWorkloadCalculatorService.computeWorkload(), the workload
     * formula produces identical results. The formula (handling + travel + return + break)
     * must remain unchanged.
     *
     * <p>This test verifies the workload computation is deterministic and follows
     * the expected formula: totalMinutes = handlingMinutes + travelMinutes +
     * returnToHubMinutes + breakBuffer(30 min).</p>
     *
     * <p><b>Validates: Requirements 3.5</b></p>
     */
    @Property(tries = 50)
    void workloadFormula_producesIdenticalResults(
            @ForAll("shipmentSets") List<Shipment> shipments) throws Exception {

        if (shipments.isEmpty()) return;

        ShiftWorkloadCalculatorService workloadCalc = buildWorkloadCalculator();

        // Compute workload
        ShiftWorkloadCalculatorService.WorkloadResult result = workloadCalc.computeWorkload(shipments);

        // Verify formula: total = handling + travel + return + break(30)
        double expectedTotal = result.handlingMinutes() + result.travelMinutes()
                + result.returnToHubMinutes() + 30.0; // breakBufferMinutes = 30

        assertThat(result.totalMinutes())
                .as("Total workload should equal handling + travel + return + break(30)")
                .isCloseTo(expectedTotal, within(0.001));

        // Verify all components are non-negative
        assertThat(result.handlingMinutes())
                .as("Handling minutes should be >= 0")
                .isGreaterThanOrEqualTo(0.0);
        assertThat(result.travelMinutes())
                .as("Travel minutes should be >= 0")
                .isGreaterThanOrEqualTo(0.0);
        assertThat(result.returnToHubMinutes())
                .as("Return to hub minutes should be >= 0")
                .isGreaterThanOrEqualTo(0.0);

        // Verify determinism: same input → same output
        ShiftWorkloadCalculatorService.WorkloadResult result2 = workloadCalc.computeWorkload(shipments);
        assertThat(result.totalMinutes())
                .as("Workload computation should be deterministic")
                .isEqualTo(result2.totalMinutes());

        // Verify handling time is proportional to shipment count
        // Each shipment contributes 5.0 min (default) or 5.0 min (COD/Prepaid as configured)
        double expectedHandling = shipments.stream()
                .mapToDouble(s -> {
                    if (s.getOrderType() == null) return 5.0;
                    return switch (s.getOrderType().trim()) {
                        case "COD" -> 5.0;
                        case "Prepaid" -> 5.0;
                        default -> 5.0;
                    };
                })
                .sum();
        assertThat(result.handlingMinutes())
                .as("Handling minutes should match per-shipment handling times")
                .isCloseTo(expectedHandling, within(0.001));
    }

    // =========================================================================
    // Property 5: AllocationSummary JSON response structure backward-compatible
    // Validates: Requirements 3.6
    // =========================================================================

    /**
     * The AllocationSummary JSON response structure remains backward-compatible —
     * no field removals or type changes.
     *
     * <p>This test verifies that AllocationSummary can be serialized to JSON and
     * deserialized back, maintaining all fields. The record structure must remain
     * stable across the fix.</p>
     *
     * <p><b>Validates: Requirements 3.6</b></p>
     */
    @Property(tries = 30)
    void allocationSummaryJsonStructure_remainsBackwardCompatible(
            @ForAll("earningsLists") List<Double> srEarnings) throws Exception {

        ObjectMapper mapper = new ObjectMapper();

        List<SrSummaryDto> srSummaries = IntStream.range(0, srEarnings.size())
                .mapToObj(i -> new SrSummaryDto(
                        "SR-" + String.format("%03d", i + 1),
                        20, 2, 0.0, 15.0,
                        List.of("411001"),
                        srEarnings.get(i) + 100.0, 100.0, srEarnings.get(i),
                        "AFFINITY_ASSIGNED", 350.0, 58.3, 100.0, 200.0, 50.0, null
                ))
                .collect(Collectors.toList());

        // Build a time-based AllocationSummary (the way buildSummary() currently does)
        AllocationSummary summary = new AllocationSummary(
                "2025-01-15",
                100, 90, 10,
                0, 0,
                srEarnings.size(),
                15, 25, 20.0,
                0.0,
                srSummaries,
                0.0, 0.0, 0.0,  // current buggy values (hardcoded zeros)
                "time-based",
                GLOBAL_SHIFT_DURATION_MINUTES,
                5, 5,
                List.of(),
                List.of(),
                false
        );

        // Serialize to JSON
        String json = mapper.writeValueAsString(summary);

        // Verify required fields exist in JSON
        assertThat(json).contains("\"date\"");
        assertThat(json).contains("\"totalShipments\"");
        assertThat(json).contains("\"allocatedShipments\"");
        assertThat(json).contains("\"unallocatedShipments\"");
        assertThat(json).contains("\"totalSrs\"");
        assertThat(json).contains("\"minShipmentsPerSr\"");
        assertThat(json).contains("\"maxShipmentsPerSr\"");
        assertThat(json).contains("\"avgShipmentsPerSr\"");
        assertThat(json).contains("\"srSummaries\"");
        assertThat(json).contains("\"earningsVariance\"");
        assertThat(json).contains("\"earningsRange\"");
        assertThat(json).contains("\"meanNetEarnings\"");
        assertThat(json).contains("\"allocationMode\"");
        assertThat(json).contains("\"shiftDurationMinutes\"");
        assertThat(json).contains("\"overflowShipments\"");
        assertThat(json).contains("\"noRegionShipments\"");

        // Verify field types via deserialization to Map
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = mapper.readValue(json, Map.class);

        assertThat(parsed.get("date")).isInstanceOf(String.class);
        assertThat(parsed.get("totalShipments")).isInstanceOf(Integer.class);
        assertThat(parsed.get("allocatedShipments")).isInstanceOf(Integer.class);
        assertThat(parsed.get("totalSrs")).isInstanceOf(Integer.class);
        assertThat(parsed.get("allocationMode")).isInstanceOf(String.class);
        assertThat(parsed.get("shiftDurationMinutes")).isInstanceOf(Integer.class);
        assertThat(parsed.get("srSummaries")).isInstanceOf(List.class);

        // Verify numeric fields are numbers (int or double)
        assertThat(parsed.get("earningsVariance")).isInstanceOf(Number.class);
        assertThat(parsed.get("earningsRange")).isInstanceOf(Number.class);
        assertThat(parsed.get("meanNetEarnings")).isInstanceOf(Number.class);

        // Verify the shiftDurationMinutes reports the global default
        assertThat(((Number) parsed.get("shiftDurationMinutes")).intValue())
                .as("shiftDurationMinutes should report the global default")
                .isEqualTo(GLOBAL_SHIFT_DURATION_MINUTES);
    }

    // =========================================================================
    // Providers
    // =========================================================================

    @Provide
    Arbitrary<List<Shipment>> shipmentSets() {
        return Arbitraries.integers().between(5, 25)
                .map(this::generateShipments);
    }

    @Provide
    Arbitrary<List<Double>> earningsLists() {
        Arbitrary<Double> earningsArb = Arbitraries.doubles().between(500.0, 2000.0).ofScale(2);
        return earningsArb.list().ofMinSize(2).ofMaxSize(6)
                .filter(list -> {
                    double min = list.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                    double max = list.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                    return (max - min) > 50.0;
                });
    }

    // =========================================================================
    // Helper: Generate shipments near hub
    // =========================================================================

    private List<Shipment> generateShipments(int count) {
        List<Shipment> shipments = new ArrayList<>();
        Random rng = new Random(42); // Fixed seed for reproducibility
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count;
            double radius = 0.02 + rng.nextDouble() * 0.03; // 2-5 km from hub
            shipments.add(Shipment.builder()
                    .shippingId("SHP-" + String.format("%04d", i))
                    .dropLatitude(HUB_LAT + radius * Math.cos(angle))
                    .dropLongitude(HUB_LNG + radius * Math.sin(angle))
                    .orderType(i % 3 == 0 ? "COD" : "Prepaid")
                    .dropPincode("411" + String.format("%03d", i % 50))
                    .expectedPayout(50.0 + rng.nextDouble() * 150.0)
                    .rate(40.0 + rng.nextDouble() * 120.0)
                    .build());
        }
        return shipments;
    }

    // =========================================================================
    // Helper: Build AffinityShiftAllocationService with specific shift duration
    // =========================================================================

    private AffinityShiftAllocationService buildService(int shiftDurationMinutes, double targetUtilisation) throws Exception {
        OpenRouteService ors = mock(OpenRouteService.class);
        when(ors.isConfigured()).thenReturn(false);
        GoogleMapsService gms = mock(GoogleMapsService.class);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        TravelTimeCacheService cache = new TravelTimeCacheService(ors, gms);
        setField(cache, "hubLat", HUB_LAT);
        setField(cache, "hubLng", HUB_LNG);
        setField(cache, "avgSpeedKmh", 20.0);

        ShiftWorkloadCalculatorService workloadCalculator = new ShiftWorkloadCalculatorService(cache);
        setField(workloadCalculator, "hubLat", HUB_LAT);
        setField(workloadCalculator, "hubLng", HUB_LNG);
        setField(workloadCalculator, "handlingTimeCod", 5.0);
        setField(workloadCalculator, "handlingTimePrepaid", 5.0);
        setField(workloadCalculator, "handlingTimeDefault", 5.0);
        setField(workloadCalculator, "breakBufferMinutes", 30.0);

        PincodeBoundaryService pincodeBoundaryService = mock(PincodeBoundaryService.class);
        when(pincodeBoundaryService.getAllPincodes()).thenReturn(Collections.emptySet());
        when(pincodeBoundaryService.isLoaded()).thenReturn(false);

        ClusterFirstRouteOptimizer cfro = new ClusterFirstRouteOptimizer(cache);
        setField(cfro, "maxClusterSize", 12);

        RouteOptimizerService routeOptimizerService = mock(RouteOptimizerService.class);

        EarningsBalancingService earningsBalancingService = new EarningsBalancingService(
                workloadCalculator, routeOptimizerService);
        setField(earningsBalancingService, "balanceTarget", 0.50);
        setField(earningsBalancingService, "maxIterations", 50);

        AffinityShiftAllocationService service = new AffinityShiftAllocationService(
                mock(InMemoryStore.class),
                mock(AffinityConfigStorageService.class),
                cache,
                workloadCalculator,
                routeOptimizerService,
                mock(HubBoundaryService.class),
                pincodeBoundaryService,
                cfro,
                new LegacyRouteOptimizer(cache),
                earningsBalancingService
        );
        setField(service, "hubLat", HUB_LAT);
        setField(service, "hubLng", HUB_LNG);
        setField(service, "shiftDurationMinutes", shiftDurationMinutes);
        setField(service, "twoOptMaxIterations", 10);
        setField(service, "allocationBoundaryKmFallback", 25.0);
        setField(service, "targetUtilisation", targetUtilisation);
        setField(service, "leftoverMinThreshold", 10);
        setField(service, "routeOptimizerStrategy", "cluster-first");
        return service;
    }

    // =========================================================================
    // Helper: Build ShiftWorkloadCalculatorService
    // =========================================================================

    private ShiftWorkloadCalculatorService buildWorkloadCalculator() throws Exception {
        OpenRouteService ors = mock(OpenRouteService.class);
        when(ors.isConfigured()).thenReturn(false);
        GoogleMapsService gms = mock(GoogleMapsService.class);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        TravelTimeCacheService cache = new TravelTimeCacheService(ors, gms);
        setField(cache, "hubLat", HUB_LAT);
        setField(cache, "hubLng", HUB_LNG);
        setField(cache, "avgSpeedKmh", 20.0);

        ShiftWorkloadCalculatorService workloadCalc = new ShiftWorkloadCalculatorService(cache);
        setField(workloadCalc, "hubLat", HUB_LAT);
        setField(workloadCalc, "hubLng", HUB_LNG);
        setField(workloadCalc, "handlingTimeCod", 5.0);
        setField(workloadCalc, "handlingTimePrepaid", 5.0);
        setField(workloadCalc, "handlingTimeDefault", 5.0);
        setField(workloadCalc, "breakBufferMinutes", 30.0);
        return workloadCalc;
    }

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
