package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

/**
 * Validates ETA and duration consistency between the packing workload calculator
 * (ShiftWorkloadCalculatorService) and the SR timeline computation
 * (AllocationController timeline endpoint logic).
 *
 * Both modules MUST use the same Haversine-based travel time computation
 * (TravelTimeCacheService.haversineMinutes()) with the same configurable road factor,
 * ensuring zero divergence between packing ETA and timeline ETA.
 *
 * Validates: Requirements 2.3
 */
class EtaConsistencyTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;
    private static final double BREAK_BUFFER_MIN = 30.0;
    private static final double HANDLING_COD = 6.0;
    private static final double HANDLING_PREPAID = 5.0;
    private static final double HANDLING_DEFAULT = 5.0;

    private TravelTimeCacheService travelTimeCache;
    private ShiftWorkloadCalculatorService workloadCalculator;

    @BeforeEach
    void setUp() throws Exception {
        // Build a real TravelTimeCacheService backed by Haversine only (no ORS/Google Maps)
        OpenRouteService ors = mock(OpenRouteService.class);
        when(ors.isConfigured()).thenReturn(false);
        GoogleMapsService gms = mock(GoogleMapsService.class);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        travelTimeCache = new TravelTimeCacheService(ors, gms);
        setField(travelTimeCache, "hubLat", HUB_LAT);
        setField(travelTimeCache, "hubLng", HUB_LNG);
        setField(travelTimeCache, "avgSpeedKmh", 20.0);
        setField(travelTimeCache, "roadFactor", 1.15);

        workloadCalculator = new ShiftWorkloadCalculatorService(travelTimeCache);
        setField(workloadCalculator, "hubLat", HUB_LAT);
        setField(workloadCalculator, "hubLng", HUB_LNG);
        setField(workloadCalculator, "handlingTimeCod", HANDLING_COD);
        setField(workloadCalculator, "handlingTimePrepaid", HANDLING_PREPAID);
        setField(workloadCalculator, "handlingTimeDefault", HANDLING_DEFAULT);
        setField(workloadCalculator, "breakBufferMinutes", BREAK_BUFFER_MIN);
    }

    // =========================================================================
    // Test 1: totalDurationMinutes from timeline MUST equal computeWorkload().totalMinutes()
    // =========================================================================

    @Test
    void timelineDuration_equalsWorkloadTotal_forMixedShipmentList() {
        List<Shipment> shipments = List.of(
                shipmentAt("COD", 18.51, 73.85),
                shipmentAt("Prepaid", 18.52, 73.86),
                shipmentAt("COD", 18.48, 73.90),
                shipmentAt("Prepaid", 18.45, 73.87),
                shipmentAt("COD", 18.50, 73.92)
        );

        double timelineDuration = computeTimelineDuration(shipments);
        double workloadTotal = workloadCalculator.computeWorkload(shipments).totalMinutes();

        assertThat(timelineDuration)
                .as("Timeline duration must exactly equal workload total for the same shipment list")
                .isCloseTo(workloadTotal, within(1e-9));
    }

    @Test
    void timelineDuration_equalsWorkloadTotal_forSingleShipment() {
        List<Shipment> shipments = List.of(shipmentAt("COD", 18.51, 73.85));

        double timelineDuration = computeTimelineDuration(shipments);
        double workloadTotal = workloadCalculator.computeWorkload(shipments).totalMinutes();

        assertThat(timelineDuration)
                .as("Timeline duration must equal workload total for a single shipment")
                .isCloseTo(workloadTotal, within(1e-9));
    }

    @Test
    void timelineDuration_equalsWorkloadTotal_forLargeRoute() {
        List<Shipment> shipments = buildPuneRoute(20);

        double timelineDuration = computeTimelineDuration(shipments);
        double workloadTotal = workloadCalculator.computeWorkload(shipments).totalMinutes();

        assertThat(timelineDuration)
                .as("Timeline duration must equal workload total for a 20-stop route")
                .isCloseTo(workloadTotal, within(1e-9));
    }

    @Test
    void timelineDuration_equalsWorkloadTotal_forAllPrepaidRoute() {
        List<Shipment> shipments = List.of(
                shipmentAt("Prepaid", 18.49, 73.86),
                shipmentAt("Prepaid", 18.50, 73.87),
                shipmentAt("Prepaid", 18.51, 73.88),
                shipmentAt("Prepaid", 18.52, 73.89)
        );

        double timelineDuration = computeTimelineDuration(shipments);
        double workloadTotal = workloadCalculator.computeWorkload(shipments).totalMinutes();

        assertThat(timelineDuration)
                .as("Timeline duration must equal workload total for all-Prepaid route")
                .isCloseTo(workloadTotal, within(1e-9));
    }

    // =========================================================================
    // Test 2: Divergence between packing ETA and timeline ETA is exactly 0%
    // =========================================================================

    @Test
    void etaDivergence_isExactlyZero_forVariousRoutes() {
        List<List<Shipment>> routes = List.of(
                List.of(shipmentAt("COD", 18.51, 73.85)),
                List.of(shipmentAt("COD", 18.51, 73.85), shipmentAt("Prepaid", 18.55, 73.90)),
                buildPuneRoute(10),
                buildPuneRoute(15),
                List.of(
                        shipmentAt("COD", 18.42, 73.80),
                        shipmentAt("Prepaid", 18.55, 73.95),
                        shipmentAt("COD", 18.48, 73.88)
                )
        );

        for (List<Shipment> route : routes) {
            double timelineDuration = computeTimelineDuration(route);
            double workloadTotal = workloadCalculator.computeWorkload(route).totalMinutes();

            double divergence = workloadTotal > 0
                    ? Math.abs(timelineDuration - workloadTotal) / workloadTotal
                    : 0.0;

            assertThat(divergence)
                    .as("ETA divergence must be exactly 0%% for route with %d stops", route.size())
                    .isEqualTo(0.0);
        }
    }

    @Test
    void perLegTravelSum_matchesRouteTimeHaversine() {
        List<Shipment> shipments = List.of(
                shipmentAt("COD", 18.51, 73.85),
                shipmentAt("Prepaid", 18.52, 73.86),
                shipmentAt("COD", 18.53, 73.87)
        );

        // Workload calculator uses getRouteTimeHaversine (hub -> all stops in one call)
        List<double[]> waypoints = shipments.stream()
                .map(s -> new double[]{s.getDropLatitude(), s.getDropLongitude()})
                .toList();
        double workloadTravel = travelTimeCache.getRouteTimeHaversine(waypoints);

        // Timeline computes per-leg travel using getTravelTimeHaversine
        double timelineTravel = 0.0;
        double prevLat = HUB_LAT, prevLng = HUB_LNG;
        for (double[] wp : waypoints) {
            timelineTravel += travelTimeCache.getTravelTimeHaversine(prevLat, prevLng, wp[0], wp[1]);
            prevLat = wp[0];
            prevLng = wp[1];
        }

        assertThat(timelineTravel)
                .as("Per-leg travel sum must equal route travel time (same code path)")
                .isCloseTo(workloadTravel, within(1e-9));
    }

    // =========================================================================
    // Test 3: Changing road.factor affects both packing and timeline equally
    // =========================================================================

    @Test
    void changingRoadFactor_affectsBothModulesEqually() throws Exception {
        List<Shipment> shipments = List.of(
                shipmentAt("COD", 18.51, 73.85),
                shipmentAt("Prepaid", 18.55, 73.90),
                shipmentAt("COD", 18.48, 73.88)
        );

        // Compute with road factor 1.15 (default)
        double timelineDuration115 = computeTimelineDuration(shipments);
        double workloadTotal115 = workloadCalculator.computeWorkload(shipments).totalMinutes();

        // Change road factor to 1.30
        setField(travelTimeCache, "roadFactor", 1.30);
        clearCache(travelTimeCache);

        double timelineDuration130 = computeTimelineDuration(shipments);
        double workloadTotal130 = workloadCalculator.computeWorkload(shipments).totalMinutes();

        // Both should have increased
        assertThat(timelineDuration130).isGreaterThan(timelineDuration115);
        assertThat(workloadTotal130).isGreaterThan(workloadTotal115);

        // The increase must be identical (same code path)
        double timelineIncrease = timelineDuration130 - timelineDuration115;
        double workloadIncrease = workloadTotal130 - workloadTotal115;

        assertThat(timelineIncrease)
                .as("Road factor change must affect timeline and workload identically")
                .isCloseTo(workloadIncrease, within(1e-9));
    }

    @Test
    void changingRoadFactor_maintainsZeroDivergence() throws Exception {
        List<Shipment> shipments = buildPuneRoute(12);

        double[] roadFactors = {1.0, 1.10, 1.15, 1.20, 1.30, 1.50};

        for (double rf : roadFactors) {
            setField(travelTimeCache, "roadFactor", rf);
            clearCache(travelTimeCache);

            double timelineDuration = computeTimelineDuration(shipments);
            double workloadTotal = workloadCalculator.computeWorkload(shipments).totalMinutes();

            assertThat(timelineDuration)
                    .as("Zero divergence must hold with road factor %.2f", rf)
                    .isCloseTo(workloadTotal, within(1e-9));
        }
    }

    @Test
    void changingRoadFactor_travelTimeScalesProportionally() throws Exception {
        List<Shipment> shipments = buildPuneRoute(8);

        setField(travelTimeCache, "roadFactor", 1.15);
        clearCache(travelTimeCache);
        double travel115 = computeTravelMinutes(shipments);

        setField(travelTimeCache, "roadFactor", 1.30);
        clearCache(travelTimeCache);
        double travel130 = computeTravelMinutes(shipments);

        double expectedRatio = 1.30 / 1.15;
        assertThat(travel130 / travel115)
                .as("Travel time must scale proportionally with road factor")
                .isCloseTo(expectedRatio, within(1e-9));
    }

    // =========================================================================
    // Helper: Compute timeline duration the same way AllocationController does
    // =========================================================================

    /**
     * Replicates the timeline duration computation from AllocationController.getSrTimeline().
     * Formula: sum(per-leg travel) + sum(handling per stop) + return-to-hub + break buffer
     * This uses the same TravelTimeCacheService methods as the controller.
     */
    private double computeTimelineDuration(List<Shipment> orderedShipments) {
        if (orderedShipments == null || orderedShipments.isEmpty()) return 0.0;

        // Per-leg travel (hub -> stop1 -> stop2 -> ... -> stopN)
        double totalTravel = 0.0;
        double prevLat = HUB_LAT, prevLng = HUB_LNG;
        for (Shipment s : orderedShipments) {
            totalTravel += travelTimeCache.getTravelTimeHaversine(
                    prevLat, prevLng, s.getDropLatitude(), s.getDropLongitude());
            prevLat = s.getDropLatitude();
            prevLng = s.getDropLongitude();
        }

        // Handling time per stop
        double totalHandling = 0.0;
        for (Shipment s : orderedShipments) {
            totalHandling += getHandlingTime(s);
        }

        // Return to hub
        Shipment last = orderedShipments.get(orderedShipments.size() - 1);
        double returnToHub = travelTimeCache.getReturnToHubTimeHaversine(
                last.getDropLatitude(), last.getDropLongitude());

        // Total = travel + handling + return + break (same as ShiftWorkloadCalculatorService)
        return totalTravel + totalHandling + returnToHub + BREAK_BUFFER_MIN;
    }

    /**
     * Compute only the travel minutes (route + return) for road factor scaling tests.
     */
    private double computeTravelMinutes(List<Shipment> orderedShipments) {
        if (orderedShipments == null || orderedShipments.isEmpty()) return 0.0;

        double totalTravel = 0.0;
        double prevLat = HUB_LAT, prevLng = HUB_LNG;
        for (Shipment s : orderedShipments) {
            totalTravel += travelTimeCache.getTravelTimeHaversine(
                    prevLat, prevLng, s.getDropLatitude(), s.getDropLongitude());
            prevLat = s.getDropLatitude();
            prevLng = s.getDropLongitude();
        }

        Shipment last = orderedShipments.get(orderedShipments.size() - 1);
        totalTravel += travelTimeCache.getReturnToHubTimeHaversine(
                last.getDropLatitude(), last.getDropLongitude());

        return totalTravel;
    }

    /**
     * Returns handling time matching the workload calculator logic.
     */
    private double getHandlingTime(Shipment s) {
        if (s == null || s.getOrderType() == null) return HANDLING_DEFAULT;
        return switch (s.getOrderType().trim()) {
            case "COD" -> HANDLING_COD;
            case "Prepaid" -> HANDLING_PREPAID;
            default -> HANDLING_DEFAULT;
        };
    }

    // =========================================================================
    // Test data builders
    // =========================================================================

    private static Shipment shipmentAt(String orderType, double lat, double lng) {
        return Shipment.builder()
                .shippingId("TEST-" + lat + "-" + lng)
                .orderType(orderType)
                .dropLatitude(lat)
                .dropLongitude(lng)
                .build();
    }

    /**
     * Build a route of N shipments spread across the Pune delivery area.
     * Alternates between COD and Prepaid order types.
     */
    private List<Shipment> buildPuneRoute(int count) {
        List<Shipment> shipments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double lat = HUB_LAT + (i * 0.005) - (count * 0.0025);
            double lng = HUB_LNG + (i * 0.003) - (count * 0.0015);
            String type = (i % 2 == 0) ? "COD" : "Prepaid";
            shipments.add(shipmentAt(type, lat, lng));
        }
        return shipments;
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

    @SuppressWarnings("unchecked")
    private static void clearCache(TravelTimeCacheService cache) throws Exception {
        Field f = findField(cache.getClass(), "cache");
        f.setAccessible(true);
        ((ConcurrentHashMap<String, Double>) f.get(cache)).clear();
    }
}
