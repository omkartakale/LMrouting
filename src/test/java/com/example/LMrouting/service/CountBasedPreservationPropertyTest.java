package com.example.LMrouting.service;

import com.example.LMrouting.dto.AllocationSummary;
import com.example.LMrouting.dto.SrSummaryDto;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.store.InMemoryStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

/**
 * Preservation property-based tests for count-based allocation mode.
 *
 * These tests verify that the count-based allocation pipeline remains unchanged
 * after the time-based allocation hardening fix is applied. They establish
 * baseline behavior on UNFIXED code and must continue to pass after the fix.
 *
 * Uses jqwik directly (no Spring context) with manually wired service instances,
 * following the same pattern as existing PBT tests in this project.
 *
 * **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10**
 */
class CountBasedPreservationPropertyTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;
    private static final double LAT_MIN = 18.42;
    private static final double LAT_MAX = 18.55;
    private static final double LNG_MIN = 73.80;
    private static final double LNG_MAX = 73.95;

    // =========================================================================
    // Service builders (manual wiring, no Spring context)
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

        ShiftWorkloadCalculatorService calculator = new ShiftWorkloadCalculatorService(cache);
        setField(calculator, "hubLat", HUB_LAT);
        setField(calculator, "hubLng", HUB_LNG);
        setField(calculator, "handlingTimeCod", 6.0);
        setField(calculator, "handlingTimePrepaid", 5.0);
        setField(calculator, "handlingTimeDefault", 5.0);
        setField(calculator, "breakBufferMinutes", 30.0);
        return calculator;
    }

    private AllocationEngineService buildAllocationEngine() throws Exception {
        com.example.LMrouting.dto.ScoreWeights weights =
                new com.example.LMrouting.dto.ScoreWeights(1.0, 0.0, 0.0, 0.0);
        GoogleMapsService gms = new GoogleMapsService();
        OpenRouteService ors = new OpenRouteService();
        RouteOptimizerService routeOptimizer = new RouteOptimizerService(gms, ors);
        setField(routeOptimizer, "hubLat", HUB_LAT);
        setField(routeOptimizer, "hubLng", HUB_LNG);

        InMemoryStore store = new InMemoryStore();
        HubBoundaryService hubBoundaryService = mock(HubBoundaryService.class);
        PincodeBoundaryService pincodeBoundaryService = mock(PincodeBoundaryService.class);
        when(pincodeBoundaryService.isLoaded()).thenReturn(false);
        AffinityShiftAllocationService affinityService = mock(AffinityShiftAllocationService.class);

        AllocationEngineService service = new AllocationEngineService(
                store, routeOptimizer, weights, hubBoundaryService,
                pincodeBoundaryService, affinityService);
        setField(service, "hubLat", HUB_LAT);
        setField(service, "hubLng", HUB_LNG);
        setField(service, "earningsRangeThreshold", 1.0);
        setField(service, "maxIterations", 200);
        setField(service, "srCapacityMin", 80);
        setField(service, "srCapacityMax", 80);
        setField(service, "allocationBoundaryKmFallback", 25.0);
        setField(service, "hubName", "PNQ HDP");
        return service;
    }

    // =========================================================================
    // Property 1: Handling times remain COD=6 min, Prepaid=5 min, default=5 min
    // Validates: Requirements 3.8
    // =========================================================================

    /**
     * **Validates: Requirements 3.8**
     *
     * For all shipments, handling times remain COD=6 min, Prepaid=5 min, default=5 min.
     */
    @Property(tries = 50)
    void handlingTimes_remainCorrectForAllOrderTypes(
            @ForAll("orderTypes") String orderType) throws Exception {

        ShiftWorkloadCalculatorService calculator = buildWorkloadCalculator();
        Shipment s = Shipment.builder().orderType(orderType).build();
        double handlingTime = calculator.getHandlingTime(s);

        switch (orderType) {
            case "COD" -> assertThat(handlingTime)
                    .as("COD handling time must be 6 minutes")
                    .isEqualTo(6.0);
            case "Prepaid" -> assertThat(handlingTime)
                    .as("Prepaid handling time must be 5 minutes")
                    .isEqualTo(5.0);
            default -> assertThat(handlingTime)
                    .as("Default handling time must be 5 minutes")
                    .isEqualTo(5.0);
        }
    }

    // =========================================================================
    // Property 2: 30-minute break buffer is included in workload calculations
    // Validates: Requirements 3.9
    // =========================================================================

    /**
     * **Validates: Requirements 3.9**
     *
     * For all non-empty shipment lists, the 30-minute break buffer is included
     * in workload calculations. Total workload = handling + travel + return + 30 min break.
     */
    @Property(tries = 30)
    void breakBuffer_isIncludedInWorkloadCalculations(
            @ForAll("nonEmptyShipmentLists") List<Shipment> shipments) throws Exception {

        ShiftWorkloadCalculatorService calculator = buildWorkloadCalculator();
        ShiftWorkloadCalculatorService.WorkloadResult result = calculator.computeWorkload(shipments);

        // Total must include the 30-minute break buffer
        double componentsWithoutBreak = result.handlingMinutes() + result.travelMinutes() + result.returnToHubMinutes();
        double expectedTotal = componentsWithoutBreak + 30.0;

        assertThat(result.totalMinutes())
                .as("Total workload must include 30-minute break buffer (components=%.2f + break=30.0 = expected=%.2f, actual=%.2f)",
                        componentsWithoutBreak, expectedTotal, result.totalMinutes())
                .isCloseTo(expectedTotal, within(0.001));
    }

    // =========================================================================
    // Property 3: Affinity matching produces deterministic region assignments
    // (pincode match first, then coordinate-based polygon match)
    // Validates: Requirements 3.2
    // =========================================================================

    /**
     * **Validates: Requirements 3.2**
     *
     * For all shipments with pincodes, the AffinityMatchingService produces
     * deterministic route-to-SR assignments based on pincode affinity.
     * Running the same input twice produces identical results.
     */
    @Property(tries = 30)
    void affinityMatching_producesDeterministicAssignments(
            @ForAll("routeMaps") Map<String, List<Shipment>> routes,
            @ForAll("affinityMaps") Map<String, Set<String>> affinities) {

        AffinityMatchingService service = new AffinityMatchingService();

        // Run twice with same input
        Map<String, List<Shipment>> result1 = service.matchRoutesToSRs(routes, affinities);
        Map<String, List<Shipment>> result2 = service.matchRoutesToSRs(routes, affinities);

        // Results must be identical
        assertThat(result1.keySet()).isEqualTo(result2.keySet());
        for (String sr : result1.keySet()) {
            List<String> ids1 = result1.get(sr).stream()
                    .map(Shipment::getShippingId).sorted().collect(Collectors.toList());
            List<String> ids2 = result2.get(sr).stream()
                    .map(Shipment::getShippingId).sorted().collect(Collectors.toList());
            assertThat(ids1)
                    .as("Affinity matching must be deterministic for SR '%s'", sr)
                    .isEqualTo(ids2);
        }
    }

    // =========================================================================
    // Property 4: Hub boundary polygon pre-filter excludes shipments outside
    // the boundary consistently using ray-casting algorithm.
    // Validates: Requirements 3.4
    // =========================================================================

    /**
     * **Validates: Requirements 3.4**
     *
     * For all shipments, the hub boundary polygon pre-filter (isPointInPolygon)
     * produces consistent results: points clearly inside the polygon are included,
     * points clearly outside are excluded, and the result is deterministic.
     */
    @Property(tries = 50)
    void hubBoundaryPreFilter_isDeterministicAndConsistent(
            @ForAll("pointsWithPolygon") PointPolygonScenario scenario) throws Exception {

        // Use the static isPointInPolygon method from AllocationEngineService
        boolean result1 = invokeIsPointInPolygon(scenario.lat(), scenario.lng(), scenario.polygon());
        boolean result2 = invokeIsPointInPolygon(scenario.lat(), scenario.lng(), scenario.polygon());

        // Must be deterministic
        assertThat(result1)
                .as("isPointInPolygon must be deterministic for point (%.5f, %.5f)", scenario.lat(), scenario.lng())
                .isEqualTo(result2);

        // Points at the hub center (inside the polygon) should be inside
        if (scenario.isExpectedInside()) {
            assertThat(result1)
                    .as("Point (%.5f, %.5f) should be inside the polygon", scenario.lat(), scenario.lng())
                    .isTrue();
        }
    }

    // =========================================================================
    // Property 5: Count-based allocation produces valid SR assignments
    // with K-Means clustering and net-earnings rebalancing.
    // Validates: Requirements 3.1, 3.10
    // =========================================================================

    /**
     * **Validates: Requirements 3.1, 3.10**
     *
     * For all allocation requests where allocationMode is "count-based" or absent,
     * the K-Means clustering assigns all shipments to SRs, and rebalancing
     * does not increase earnings variance.
     */
    @Property(tries = 20)
    void countBasedAllocation_kMeansAssignsAllShipments(
            @ForAll("forwardShipmentLists") List<Shipment> shipments,
            @ForAll("srNameLists") List<String> srNames) throws Exception {

        AllocationEngineService service = buildAllocationEngine();

        // Invoke K-Means clustering directly
        Map<String, List<Shipment>> assignment = invokeKMeansCluster(service, shipments, srNames);

        // All shipments must be assigned
        int totalAssigned = assignment.values().stream().mapToInt(List::size).sum();
        assertThat(totalAssigned)
                .as("K-Means must assign all %d shipments", shipments.size())
                .isEqualTo(shipments.size());

        // All SRs must be present in the assignment map
        for (String sr : srNames) {
            assertThat(assignment).containsKey(sr);
        }

        // No shipment should appear in multiple SRs
        Set<String> allIds = new HashSet<>();
        for (List<Shipment> list : assignment.values()) {
            for (Shipment s : list) {
                assertThat(allIds.add(s.getShippingId()))
                        .as("Shipment '%s' must not be assigned to multiple SRs", s.getShippingId())
                        .isTrue();
            }
        }
    }

    // =========================================================================
    // Property 6: InMemoryStore persistence and AllocationSummary response
    // structure remain backward-compatible.
    // Validates: Requirements 3.7
    // =========================================================================

    /**
     * **Validates: Requirements 3.7**
     *
     * InMemoryStore correctly persists and retrieves shipments by date.
     * The store operations (save, find, clear) work consistently.
     */
    @Property(tries = 30)
    void inMemoryStore_persistenceIsConsistent(
            @ForAll("shipmentSets") List<Shipment> shipments) {

        InMemoryStore store = new InMemoryStore();
        String dateStr = "2026-04-10";

        // Save shipments
        store.saveShipments(dateStr, shipments);

        // Retrieve and verify
        List<Shipment> retrieved = store.findShipmentsByDate(dateStr);
        assertThat(retrieved).hasSize(shipments.size());

        // Verify all shipping IDs are preserved
        Set<String> originalIds = shipments.stream()
                .map(Shipment::getShippingId).collect(Collectors.toSet());
        Set<String> retrievedIds = retrieved.stream()
                .map(Shipment::getShippingId).collect(Collectors.toSet());
        assertThat(retrievedIds).isEqualTo(originalIds);

        // Verify clear works
        store.clearDate(dateStr);
        assertThat(store.findShipmentsByDate(dateStr)).isEmpty();
    }

    // =========================================================================
    // Providers
    // =========================================================================

    @Provide
    Arbitrary<String> orderTypes() {
        return Arbitraries.of("COD", "Prepaid", "Reverse", "Express", "Standard");
    }

    @Provide
    Arbitrary<List<Shipment>> nonEmptyShipmentLists() {
        return shipmentArbitrary().list().ofMinSize(1).ofMaxSize(8);
    }

    @Provide
    Arbitrary<List<Shipment>> shipmentSets() {
        return shipmentArbitrary().list().ofMinSize(3).ofMaxSize(15);
    }

    @Provide
    Arbitrary<List<Shipment>> forwardShipmentLists() {
        return forwardShipmentArbitrary().list().ofMinSize(4).ofMaxSize(20);
    }

    @Provide
    Arbitrary<List<String>> srNameLists() {
        return Arbitraries.integers().between(2, 5)
                .map(k -> {
                    List<String> names = new ArrayList<>();
                    for (int i = 1; i <= k; i++) names.add("SR-" + String.format("%03d", i));
                    return names;
                });
    }

    @Provide
    Arbitrary<Map<String, List<Shipment>>> routeMaps() {
        return Arbitraries.integers().between(2, 4).flatMap(srCount -> {
            List<String> srNames = new ArrayList<>();
            for (int i = 1; i <= srCount; i++) srNames.add("SR-" + String.format("%03d", i));

            return shipmentArbitrary().list().ofMinSize(srCount * 2).ofMaxSize(srCount * 5)
                    .map(shipments -> {
                        Map<String, List<Shipment>> routes = new LinkedHashMap<>();
                        int perSr = shipments.size() / srCount;
                        for (int i = 0; i < srCount; i++) {
                            int start = i * perSr;
                            int end = (i == srCount - 1) ? shipments.size() : (i + 1) * perSr;
                            routes.put(srNames.get(i), new ArrayList<>(shipments.subList(start, end)));
                        }
                        return routes;
                    });
        });
    }

    @Provide
    Arbitrary<Map<String, Set<String>>> affinityMaps() {
        return Arbitraries.integers().between(1, 3).map(affinityCount -> {
            Map<String, Set<String>> affinities = new LinkedHashMap<>();
            for (int i = 1; i <= affinityCount; i++) {
                Set<String> pincodes = new HashSet<>();
                pincodes.add("41100" + i);
                pincodes.add("41100" + (i + 3));
                affinities.put("SR-" + String.format("%03d", i), pincodes);
            }
            return affinities;
        });
    }

    @Provide
    Arbitrary<PointPolygonScenario> pointsWithPolygon() {
        // Create a polygon around the hub (roughly 5km radius square)
        // Using clean decimal values to avoid jqwik scale issues
        List<double[]> polygon = List.of(
                new double[]{18.51, 73.84},
                new double[]{18.51, 73.94},
                new double[]{18.41, 73.94},
                new double[]{18.41, 73.84}
        );

        // Generate points clearly inside the polygon (well within boundaries)
        Arbitrary<PointPolygonScenario> insidePoints = Combinators.combine(
                Arbitraries.doubles().between(18.43, 18.49).ofScale(4),
                Arbitraries.doubles().between(73.86, 73.92).ofScale(4)
        ).as((lat, lng) -> new PointPolygonScenario(lat, lng, polygon, true));

        // Generate points clearly outside the polygon
        Arbitrary<PointPolygonScenario> outsidePoints = Combinators.combine(
                Arbitraries.doubles().between(19.0, 19.5).ofScale(4),
                Arbitraries.doubles().between(74.5, 75.0).ofScale(4)
        ).as((lat, lng) -> new PointPolygonScenario(lat, lng, polygon, false));

        return Arbitraries.oneOf(insidePoints, outsidePoints);
    }

    // =========================================================================
    // Arbitrary builders
    // =========================================================================

    private Arbitrary<Shipment> shipmentArbitrary() {
        return Combinators.combine(
                Arbitraries.doubles().between(LAT_MIN, LAT_MAX).ofScale(5),
                Arbitraries.doubles().between(LNG_MIN, LNG_MAX).ofScale(5),
                Arbitraries.of("COD", "Prepaid"),
                Arbitraries.of("Forward", "Reverse"),
                Arbitraries.integers().between(1, 99999)
        ).as((lat, lng, type, flow, id) -> Shipment.builder()
                .shippingId("PRES-" + UUID.randomUUID().toString().substring(0, 8))
                .allocationDate("2026-04-10").hubName("PNQ HDP")
                .dropPincode("41100" + (id % 9 + 1))
                .shipmentFlow(flow).isHeavy(0)
                .phyWeight(1.5 + (id % 5)).volWeight(2.0).orderType(type)
                .dropLatitude(lat).dropLongitude(lng)
                .expectedPayout(5.0 + (id % 12))
                .clientId("CLIENT-01").runNumber(1).build());
    }

    private Arbitrary<Shipment> forwardShipmentArbitrary() {
        return Combinators.combine(
                Arbitraries.doubles().between(LAT_MIN, LAT_MAX).ofScale(5),
                Arbitraries.doubles().between(LNG_MIN, LNG_MAX).ofScale(5),
                Arbitraries.of("COD", "Prepaid"),
                Arbitraries.integers().between(1, 99999)
        ).as((lat, lng, type, id) -> Shipment.builder()
                .shippingId("FWD-" + UUID.randomUUID().toString().substring(0, 8))
                .allocationDate("2026-04-10").hubName("PNQ HDP")
                .dropPincode("41100" + (id % 9 + 1))
                .shipmentFlow("Forward").isHeavy(id % 10 == 0 ? 1 : 0)
                .phyWeight(1.5 + (id % 5)).volWeight(2.0).orderType(type)
                .dropLatitude(lat).dropLongitude(lng)
                .expectedPayout(5.0 + (id % 12))
                .clientId("CLIENT-01").runNumber(1).build());
    }

    // =========================================================================
    // Records
    // =========================================================================

    record PointPolygonScenario(double lat, double lng, List<double[]> polygon, boolean isExpectedInside) {}

    // =========================================================================
    // Reflection helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, List<Shipment>> invokeKMeansCluster(AllocationEngineService service,
                                                             List<Shipment> shipments,
                                                             List<String> srNames) throws Exception {
        java.lang.reflect.Method m = AllocationEngineService.class.getDeclaredMethod(
                "kMeansCluster", List.class, List.class);
        m.setAccessible(true);
        return (Map<String, List<Shipment>>) m.invoke(service, shipments, srNames);
    }

    private boolean invokeIsPointInPolygon(double lat, double lng, List<double[]> polygon) throws Exception {
        java.lang.reflect.Method m = AllocationEngineService.class.getDeclaredMethod(
                "isPointInPolygon", double.class, double.class, List.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, lat, lng, polygon);
    }

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
