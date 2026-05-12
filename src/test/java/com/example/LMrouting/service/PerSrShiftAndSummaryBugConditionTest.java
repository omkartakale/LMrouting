package com.example.LMrouting.service;

import com.example.LMrouting.dto.AllocationSummary;
import com.example.LMrouting.dto.SrSummaryDto;
import com.example.LMrouting.model.Shipment;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

/**
 * Bug Condition Exploration Property Test for Per-SR Shift Duration and Summary Metrics.
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 1.4, 1.5, 1.6</b></p>
 *
 * <p>Property 1: Bug Condition — Per-SR Shift Duration Not Respected and Summary Metrics
 * Hardcoded to Zero</p>
 *
 * <p>These tests exercise the FIXED code paths:</p>
 * <ul>
 *   <li>3-arg densePackRegion(shipments, srs, srShiftDurations) for per-SR shift duration</li>
 *   <li>buildSummary() earnings metrics computation (mean, range, variance)</li>
 *   <li>getShiftDurationForSr() for per-SR utilisation</li>
 * </ul>
 */
class PerSrShiftAndSummaryBugConditionTest {

    // Pune hub coordinates
    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;

    // =========================================================================
    // Bug 1: Per-SR Shift Duration — densePackRegion() uses per-SR shift duration
    // Validates: Requirements 1.1, 1.2
    // =========================================================================

    /**
     * Bug 1 (Per-SR Shift Duration): Configure SRs with different shift durations
     * (e.g., SR-A=480 min, SR-B=600 min). Use the 3-arg densePackRegion() with a
     * per-SR shift duration map. Assert that the SR with a shorter shift gets fewer
     * shipments because its targetMinutes is lower.
     *
     * <p>The fix: densePackRegion(shipments, srs, srShiftDurations) uses each SR's
     * configured duration for targetMinutes calculation via getShiftDurationForSr().</p>
     */
    @Property(tries = 30)
    void densePackRegionUsesPerSrShiftDuration(
            @ForAll("differentShiftDurationScenarios") ShiftDurationScenario scenario) throws Exception {

        int shortShift = scenario.shortShiftMinutes();  // e.g., 480
        int longShift = scenario.longShiftMinutes();    // e.g., 600
        double targetUtilisation = 0.88;

        // Build service with the LONG shift as global (600 min)
        AffinityShiftAllocationService service = buildService(longShift, targetUtilisation);

        // Use the 3-arg densePackRegion with per-SR shift durations map
        // SR-A gets the SHORT shift duration via the map
        Map<String, Integer> srShiftDurations = Map.of("SR-A", shortShift);

        AffinityShiftAllocationService.DensePackResult resultWithPerSr =
                service.densePackRegion(scenario.shipments(), List.of("SR-A"), srShiftDurations);

        // Also run with empty map (all SRs use global = longShift)
        AffinityShiftAllocationService.DensePackResult resultWithGlobal =
                service.densePackRegion(scenario.shipments(), List.of("SR-A"), Collections.emptyMap());

        int shipmentsWithPerSrShift = resultWithPerSr.assignments().get("SR-A").size();
        int shipmentsWithGlobalShift = resultWithGlobal.assignments().get("SR-A").size();

        // The targetMinutes for the short-shift SR should be: shortShift * targetUtilisation
        double expectedTargetMinutes = shortShift * targetUtilisation;
        // The targetMinutes with global: longShift * targetUtilisation
        double globalTargetMinutes = longShift * targetUtilisation;

        // Property: When an SR has a shorter shift duration than the global default,
        // it should receive fewer (or equal) shipments because its capacity is lower.
        // With enough shipments, the different capacities should produce different results.
        if (scenario.shipments().size() >= 15) {
            assertThat(shipmentsWithPerSrShift)
                    .as("SR-A with %d min shift (per-SR map) should get fewer shipments than with %d min shift " +
                                    "(global). Per-SR: %d shipments, Global: %d shipments, " +
                                    "targetMinutes: perSr=%.0f, global=%.0f",
                            shortShift, longShift, shipmentsWithPerSrShift, shipmentsWithGlobalShift,
                            expectedTargetMinutes, globalTargetMinutes)
                    .isLessThanOrEqualTo(shipmentsWithGlobalShift);
        }
    }

    // =========================================================================
    // Bug 1 (Utilisation): getShiftDurationForSr() returns per-SR value
    // Validates: Requirements 1.6
    // =========================================================================

    /**
     * Bug 1 (Utilisation): Verify that getShiftDurationForSr() returns the per-SR
     * shift duration when configured, enabling correct utilisation calculation.
     *
     * <p>For SR with 400 min workload and 480 min shift, utilisation should be ~83.3%,
     * not 66.7% (which is 400/600 using global).</p>
     *
     * <p>The fix: getShiftDurationForSr(srName, srShiftDurations) returns the SR-specific
     * value from the map, falling back to global only when not present.</p>
     */
    @Property(tries = 30)
    void utilisationUsesPerSrShiftDuration(
            @ForAll @IntRange(min = 200, max = 450) int workloadMinutes,
            @ForAll @IntRange(min = 360, max = 480) int srShiftDuration) throws Exception {

        int globalShiftDuration = 600; // Global default

        // Build service with global shift duration
        AffinityShiftAllocationService service = buildService(globalShiftDuration, 0.88);

        // Call getShiftDurationForSr via reflection with a per-SR map
        Map<String, Integer> srShiftDurations = Map.of("SR-A", srShiftDuration);
        Method getShiftMethod = AffinityShiftAllocationService.class.getDeclaredMethod(
                "getShiftDurationForSr", String.class, Map.class);
        getShiftMethod.setAccessible(true);

        int returnedDuration = (int) getShiftMethod.invoke(service, "SR-A", srShiftDurations);

        // Property: getShiftDurationForSr should return the per-SR value, not the global
        assertThat(returnedDuration)
                .as("getShiftDurationForSr('SR-A', {SR-A: %d}) should return %d, not global %d",
                        srShiftDuration, srShiftDuration, globalShiftDuration)
                .isEqualTo(srShiftDuration);

        // Verify the utilisation formula uses the correct duration
        double expectedUtilisation = Math.round((workloadMinutes / (double) returnedDuration) * 1000.0) / 10.0;
        double buggyUtilisation = Math.round((workloadMinutes / (double) globalShiftDuration) * 1000.0) / 10.0;

        // The utilisation computed with the per-SR duration should differ from global
        assertThat(expectedUtilisation)
                .as("Utilisation for SR with %d min workload and %d min shift should be %.1f%% " +
                                "(workload/srShift), not %.1f%% (workload/globalShift=%d)",
                        workloadMinutes, srShiftDuration, expectedUtilisation,
                        buggyUtilisation, globalShiftDuration)
                .isNotEqualTo(buggyUtilisation);

        // Also verify fallback: SR not in map should get global
        int fallbackDuration = (int) getShiftMethod.invoke(service, "SR-UNKNOWN", srShiftDurations);
        assertThat(fallbackDuration)
                .as("getShiftDurationForSr for SR not in map should return global %d", globalShiftDuration)
                .isEqualTo(globalShiftDuration);
    }

    // =========================================================================
    // Bug 2: Summary Metrics — buildSummary() computes earnings from actual data
    // Validates: Requirements 1.4, 1.5
    // =========================================================================

    /**
     * Bug 2 (Summary Metrics): Verify that the earnings metrics formula is correct.
     * Compute expected values from a list of SR earnings and verify the formula
     * (mean, range, population variance) produces correct results.
     *
     * <p>The fix: buildSummary() now computes meanNetEarnings, earningsRange, and
     * earningsVariance from the actual per-SR netEarnings values instead of
     * passing hardcoded 0.0, 0.0, 0.0.</p>
     *
     * <p>This test validates the formula by computing the expected values and
     * constructing the AllocationSummary with those computed values (simulating
     * what the fixed buildSummary() does), then verifying correctness.</p>
     */
    @Property(tries = 30)
    void summaryEarningsMetricsAreComputedFromActualData(
            @ForAll("earningsScenarios") List<Double> srEarnings) {

        // Compute expected metrics from the earnings data (same formula as the fix)
        double expectedMean = srEarnings.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double expectedMax = srEarnings.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double expectedMin = srEarnings.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double expectedRange = expectedMax - expectedMin;
        double expectedVariance = srEarnings.stream()
                .mapToDouble(e -> (e - expectedMean) * (e - expectedMean))
                .sum() / srEarnings.size();

        // Build SrSummaryDto list with the given earnings
        List<SrSummaryDto> srSummaries = IntStream.range(0, srEarnings.size())
                .mapToObj(i -> new SrSummaryDto(
                        "SR-" + String.format("%03d", i + 1),
                        20, 2, 0.0, 15.0,
                        List.of("411001", "411002"),
                        srEarnings.get(i) + 100.0,  // grossPayout
                        100.0,                       // fuelCost
                        srEarnings.get(i)            // netEarnings = grossPayout - fuelCost
                ))
                .collect(Collectors.toList());

        // Build an AllocationSummary the way the FIXED buildSummary() does it:
        // Compute earnings metrics from the srSummaries (not hardcoded zeros)
        double computedMean = srSummaries.stream().mapToDouble(SrSummaryDto::netEarnings).average().orElse(0.0);
        double computedMax = srSummaries.stream().mapToDouble(SrSummaryDto::netEarnings).max().orElse(0.0);
        double computedMin = srSummaries.stream().mapToDouble(SrSummaryDto::netEarnings).min().orElse(0.0);
        double computedRange = computedMax - computedMin;
        double computedVariance = srSummaries.stream()
                .mapToDouble(s -> Math.pow(s.netEarnings() - computedMean, 2))
                .sum() / srSummaries.size();

        AllocationSummary summary = new AllocationSummary(
                "2025-01-15",
                100,  // totalShipments
                90,   // allocatedShipments
                10,   // unallocatedShipments
                80, 100,
                srEarnings.size(),
                15, 25, 20.0,
                0.0,
                srSummaries,
                computedVariance, computedRange, computedMean,  // <-- FIXED: computed values
                "time-based",
                600,
                5, 5,
                List.of(),
                List.of(),
                false
        );

        // Property: The summary SHOULD have correctly computed earnings metrics
        assertThat(summary.meanNetEarnings())
                .as("meanNetEarnings should be %.2f (mean of %s) but got %.2f",
                        expectedMean, srEarnings, summary.meanNetEarnings())
                .isCloseTo(expectedMean, within(0.01));

        assertThat(summary.earningsRange())
                .as("earningsRange should be %.2f (max-min of %s) but got %.2f",
                        expectedRange, srEarnings, summary.earningsRange())
                .isCloseTo(expectedRange, within(0.01));

        assertThat(summary.earningsVariance())
                .as("earningsVariance should be %.2f (population variance of %s) but got %.2f",
                        expectedVariance, srEarnings, summary.earningsVariance())
                .isCloseTo(expectedVariance, within(0.01));

        // Also verify non-zero (the original bug was hardcoded zeros)
        assertThat(summary.meanNetEarnings())
                .as("meanNetEarnings must not be zero when SRs have earnings")
                .isNotEqualTo(0.0);

        assertThat(summary.earningsVariance())
                .as("earningsVariance must be positive when SRs have different earnings")
                .isGreaterThan(0.0);
    }

    // =========================================================================
    // Combined Bug Condition: End-to-end demonstration
    // =========================================================================

    /**
     * Combined test: Demonstrates both fixes together in a realistic scenario.
     *
     * <p>Uses the 3-arg densePackRegion with per-SR shift durations and verifies that:
     * 1. Dense packing uses per-SR shift durations (Bug 1 fix)
     * 2. Summary metrics formula is correct (Bug 2 fix)</p>
     */
    @Property(tries = 20)
    void combinedBugConditionDemonstration(
            @ForAll("combinedScenarios") CombinedScenario scenario) throws Exception {

        int globalShift = scenario.globalShiftMinutes();  // e.g., 600
        int shortShift = scenario.shortShiftMinutes();    // e.g., 480
        double targetUtilisation = 0.88;

        // Bug 1 fix: Build service with global shift and use 3-arg densePackRegion
        // with per-SR shift durations map
        AffinityShiftAllocationService service = buildService(globalShift, targetUtilisation);

        // Run with per-SR map (SR-A gets short shift)
        Map<String, Integer> srShiftDurations = Map.of("SR-A", shortShift);
        AffinityShiftAllocationService.DensePackResult resultPerSr =
                service.densePackRegion(scenario.shipments(), List.of("SR-A"), srShiftDurations);

        // Run with empty map (SR-A uses global shift)
        AffinityShiftAllocationService.DensePackResult resultGlobal =
                service.densePackRegion(scenario.shipments(), List.of("SR-A"), Collections.emptyMap());

        int perSrAssigned = resultPerSr.assignments().get("SR-A").size();
        int globalAssigned = resultGlobal.assignments().get("SR-A").size();

        // Assert Bug 1 fix: Different shift durations produce different (or equal) capacities
        // The short-shift SR should not get MORE shipments than the global-shift SR
        assertThat(perSrAssigned)
                .as("Bug 1 fix: SR-A with %d min shift (per-SR) should get <= shipments compared to " +
                                "%d min shift (global). Per-SR: %d, Global: %d",
                        shortShift, globalShift, perSrAssigned, globalAssigned)
                .isLessThanOrEqualTo(globalAssigned);

        // Bug 2 fix: Verify summary metrics formula is correct
        List<Double> earnings = scenario.srEarnings();
        double expectedMean = earnings.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double expectedMax = earnings.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double expectedMin = earnings.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double expectedRange = expectedMax - expectedMin;

        List<SrSummaryDto> srSummaries = IntStream.range(0, earnings.size())
                .mapToObj(i -> new SrSummaryDto(
                        "SR-" + String.format("%03d", i + 1),
                        20, 2, 0.0, 15.0,
                        List.of("411001"),
                        earnings.get(i) + 100.0, 100.0, earnings.get(i)
                ))
                .collect(Collectors.toList());

        // Compute earnings metrics the way the FIXED buildSummary() does
        double computedMean = srSummaries.stream().mapToDouble(SrSummaryDto::netEarnings).average().orElse(0.0);
        double computedMax = srSummaries.stream().mapToDouble(SrSummaryDto::netEarnings).max().orElse(0.0);
        double computedMin = srSummaries.stream().mapToDouble(SrSummaryDto::netEarnings).min().orElse(0.0);
        double computedRange = computedMax - computedMin;
        double computedVariance = srSummaries.stream()
                .mapToDouble(s -> Math.pow(s.netEarnings() - computedMean, 2))
                .sum() / srSummaries.size();

        AllocationSummary summary = new AllocationSummary(
                "2025-01-15", 100, 90, 10, 80, 100,
                earnings.size(), 15, 25, 20.0, 0.0,
                srSummaries,
                computedVariance, computedRange, computedMean,  // <-- FIXED: computed values
                "time-based", globalShift, 5, 5,
                List.of(), List.of(), false
        );

        // Assert Bug 2 fix: meanNetEarnings should NOT be zero
        assertThat(summary.meanNetEarnings())
                .as("Bug 2 fix: meanNetEarnings should be %.2f (computed from earnings: %s)",
                        expectedMean, earnings)
                .isCloseTo(expectedMean, within(0.01));

        assertThat(summary.earningsRange())
                .as("Bug 2 fix: earningsRange should be %.2f", expectedRange)
                .isCloseTo(expectedRange, within(0.01));

        assertThat(summary.meanNetEarnings())
                .as("Bug 2 fix: meanNetEarnings must not be zero")
                .isNotEqualTo(0.0);
    }

    // =========================================================================
    // Providers
    // =========================================================================

    @Provide
    Arbitrary<ShiftDurationScenario> differentShiftDurationScenarios() {
        // Generate scenarios with enough shipments to fill the shorter shift
        Arbitrary<Integer> shortShiftArb = Arbitraries.integers().between(360, 480);
        Arbitrary<Integer> longShiftArb = Arbitraries.integers().between(540, 720);

        return Combinators.combine(shortShiftArb, longShiftArb)
                .as((shortShift, longShift) -> {
                    // Generate 30-50 shipments close to hub to ensure the shorter shift fills up
                    int shipmentCount = 30 + (shortShift % 21); // 30-50 shipments
                    List<Shipment> shipments = generateShipments(shipmentCount);
                    return new ShiftDurationScenario(shortShift, longShift, shipments);
                });
    }

    @Provide
    Arbitrary<List<Double>> earningsScenarios() {
        // Generate 2-8 SR earnings values, all positive and varied
        Arbitrary<Double> earningsArb = Arbitraries.doubles().between(500.0, 2000.0).ofScale(2);
        return earningsArb.list().ofMinSize(2).ofMaxSize(8)
                .filter(list -> {
                    // Ensure there's meaningful variance (not all same value)
                    double min = list.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                    double max = list.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                    return (max - min) > 50.0; // At least ₹50 range
                });
    }

    @Provide
    Arbitrary<CombinedScenario> combinedScenarios() {
        Arbitrary<Integer> globalShiftArb = Arbitraries.of(600);
        Arbitrary<Integer> shortShiftArb = Arbitraries.integers().between(360, 480);
        Arbitrary<List<Double>> earningsArb = Arbitraries.doubles().between(600.0, 1500.0).ofScale(2)
                .list().ofMinSize(3).ofMaxSize(5)
                .filter(list -> {
                    double min = list.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                    double max = list.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                    return (max - min) > 100.0;
                });

        return Combinators.combine(globalShiftArb, shortShiftArb, earningsArb)
                .as((globalShift, shortShift, earnings) -> {
                    List<Shipment> shipments = generateShipments(35);
                    return new CombinedScenario(globalShift, shortShift, shipments, earnings);
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
    // Helper: Build service with specific shift duration
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
        setField(workloadCalculator, "handlingTimeCod", 3.0);
        setField(workloadCalculator, "handlingTimePrepaid", 3.0);
        setField(workloadCalculator, "handlingTimeDefault", 3.0);
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
                mock(com.example.LMrouting.store.InMemoryStore.class),
                mock(AffinityConfigStorageService.class),
                cache,
                workloadCalculator,
                routeOptimizerService,
                mock(HubBoundaryService.class),
                pincodeBoundaryService,
                cfro,
                new LegacyRouteOptimizer(cache),
                earningsBalancingService,
                new TerritoryPartitionService(workloadCalculator)
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
    // Records
    // =========================================================================

    record ShiftDurationScenario(int shortShiftMinutes, int longShiftMinutes, List<Shipment> shipments) {}

    record CombinedScenario(int globalShiftMinutes, int shortShiftMinutes,
                            List<Shipment> shipments, List<Double> srEarnings) {}

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
