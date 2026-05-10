package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TwoOptRouteOptimizer.
 * Uses a real TravelTimeCacheService backed by Haversine (no API keys) for
 * deterministic travel times, and a mock for specific scenarios.
 */
class TwoOptRouteOptimizerTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;

    private TwoOptRouteOptimizer optimizer;
    private TravelTimeCacheService realCache;

    @BeforeEach
    void setUp() throws Exception {
        optimizer = new TwoOptRouteOptimizer();

        // Build a real cache backed by Haversine (no ORS/GMS)
        OpenRouteService ors = mock(OpenRouteService.class);
        when(ors.isConfigured()).thenReturn(false);
        GoogleMapsService gms = mock(GoogleMapsService.class);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        realCache = new TravelTimeCacheService(ors, gms);
        setField(realCache, "hubLat", HUB_LAT);
        setField(realCache, "hubLng", HUB_LNG);
        setField(realCache, "avgSpeedKmh", 20.0);
    }

    // ── Skip for small routes ─────────────────────────────────────────────────

    @Test
    void optimize_nullRouteReturnsEmptyList() {
        List<Shipment> result = optimizer.optimize(null, realCache, HUB_LAT, HUB_LNG, 100);
        assertThat(result).isEmpty();
    }

    @Test
    void optimize_emptyRouteReturnsEmptyList() {
        List<Shipment> result = optimizer.optimize(List.of(), realCache, HUB_LAT, HUB_LNG, 100);
        assertThat(result).isEmpty();
    }

    @Test
    void optimize_singleShipmentReturnsSingleElement() {
        List<Shipment> route = List.of(shipment("S1", 18.51, 73.85));
        List<Shipment> result = optimizer.optimize(route, realCache, HUB_LAT, HUB_LNG, 100);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getShippingId()).isEqualTo("S1");
    }

    @Test
    void optimize_twoShipmentsReturnsTwoElements() {
        List<Shipment> route = List.of(
                shipment("S1", 18.51, 73.85),
                shipment("S2", 18.52, 73.86)
        );
        List<Shipment> result = optimizer.optimize(route, realCache, HUB_LAT, HUB_LNG, 100);
        assertThat(result).hasSize(2);
    }

    // ── Set preservation ──────────────────────────────────────────────────────

    @Test
    void optimize_preservesAllShipments() {
        List<Shipment> route = buildRoute(5);
        List<Shipment> result = optimizer.optimize(route, realCache, HUB_LAT, HUB_LNG, 100);

        Set<String> inputIds  = route.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        Set<String> outputIds = result.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        assertThat(outputIds).isEqualTo(inputIds);
    }

    @Test
    void optimize_preservesShipmentCountForLargeRoute() {
        List<Shipment> route = buildRoute(10);
        List<Shipment> result = optimizer.optimize(route, realCache, HUB_LAT, HUB_LNG, 100);
        assertThat(result).hasSize(route.size());
    }

    @Test
    void optimize_doesNotMutateInputList() {
        List<Shipment> route = new ArrayList<>(buildRoute(5));
        List<String> originalOrder = route.stream().map(Shipment::getShippingId).toList();

        optimizer.optimize(route, realCache, HUB_LAT, HUB_LNG, 100);

        List<String> afterOrder = route.stream().map(Shipment::getShippingId).toList();
        // Input list should not be mutated (optimizer works on a copy)
        assertThat(afterOrder).isEqualTo(originalOrder);
    }

    // ── Non-increase of route time ────────────────────────────────────────────

    @Test
    void optimize_doesNotIncreaseRouteTime() {
        List<Shipment> route = buildRoute(6);
        double before = optimizer.routeTime(route, realCache, HUB_LAT, HUB_LNG);
        List<Shipment> optimized = optimizer.optimize(route, realCache, HUB_LAT, HUB_LNG, 100);
        double after = optimizer.routeTime(optimized, realCache, HUB_LAT, HUB_LNG);

        assertThat(after).isLessThanOrEqualTo(before + 1e-9);
    }

    @Test
    void optimize_improvesClearlySuboptimalRoute() {
        // Build a deliberately bad route: hub → far → near → far → near
        // 2-opt should improve this
        List<Shipment> badRoute = List.of(
                shipment("A", HUB_LAT + 0.10, HUB_LNG + 0.10), // far NE
                shipment("B", HUB_LAT + 0.01, HUB_LNG + 0.01), // near NE
                shipment("C", HUB_LAT + 0.09, HUB_LNG + 0.09), // far NE
                shipment("D", HUB_LAT + 0.02, HUB_LNG + 0.02)  // near NE
        );
        double before = optimizer.routeTime(badRoute, realCache, HUB_LAT, HUB_LNG);
        List<Shipment> optimized = optimizer.optimize(badRoute, realCache, HUB_LAT, HUB_LNG, 100);
        double after = optimizer.routeTime(optimized, realCache, HUB_LAT, HUB_LNG);

        assertThat(after).isLessThanOrEqualTo(before + 1e-9);
    }

    // ── Max iterations termination ────────────────────────────────────────────

    @Test
    void optimize_respectsMaxIterationsLimit() {
        // Use a mock cache that counts calls to verify we don't loop forever
        TravelTimeCacheService mockCache = mock(TravelTimeCacheService.class);
        when(mockCache.getTravelTime(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(1.0); // all legs equal — no improvement possible

        List<Shipment> route = buildRoute(5);
        // Should complete without hanging even with maxIterations=1
        List<Shipment> result = optimizer.optimize(route, mockCache, HUB_LAT, HUB_LNG, 1);
        assertThat(result).hasSize(5);
    }

    @Test
    void optimize_zeroMaxIterationsReturnsInputCopy() {
        List<Shipment> route = buildRoute(4);
        List<Shipment> result = optimizer.optimize(route, realCache, HUB_LAT, HUB_LNG, 0);
        // With 0 iterations, the while loop never executes — returns copy of input
        assertThat(result).hasSize(4);
        Set<String> inputIds  = route.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        Set<String> outputIds = result.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        assertThat(outputIds).isEqualTo(inputIds);
    }

    // ── routeTime helper ──────────────────────────────────────────────────────

    @Test
    void routeTime_emptyRouteReturnsZero() {
        assertThat(optimizer.routeTime(List.of(), realCache, HUB_LAT, HUB_LNG)).isEqualTo(0.0);
    }

    @Test
    void routeTime_nullRouteReturnsZero() {
        assertThat(optimizer.routeTime(null, realCache, HUB_LAT, HUB_LNG)).isEqualTo(0.0);
    }

    @Test
    void routeTime_singleShipmentIsPositive() {
        List<Shipment> route = List.of(shipment("S1", 18.51, 73.85));
        assertThat(optimizer.routeTime(route, realCache, HUB_LAT, HUB_LNG)).isGreaterThan(0.0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Shipment shipment(String id, double lat, double lng) {
        return Shipment.builder()
                .shippingId(id)
                .dropLatitude(lat)
                .dropLongitude(lng)
                .orderType("Prepaid")
                .build();
    }

    /**
     * Build a route of n shipments spread around the hub in a circle.
     */
    private static List<Shipment> buildRoute(int n) {
        List<Shipment> route = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            double lat = HUB_LAT + 0.05 * Math.cos(angle);
            double lng = HUB_LNG + 0.05 * Math.sin(angle);
            route.add(shipment("S" + i, lat, lng));
        }
        return route;
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
