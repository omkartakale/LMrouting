package com.example.LMrouting;

import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.service.GoogleMapsService;
import com.example.LMrouting.service.OpenRouteService;
import com.example.LMrouting.service.TravelTimeCacheService;
import com.example.LMrouting.service.TwoOptRouteOptimizer;
import net.jqwik.api.*;
import net.jqwik.api.constraints.NotEmpty;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for TwoOptRouteOptimizer.
 *
 * Feature: affinity-shift-allocation
 * Property 6: 2-opt does not increase route time
 * Property 7: 2-opt preserves route contents
 */
class TwoOptPropertyTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;
    private static final double LAT_MIN = 18.40;
    private static final double LAT_MAX = 18.60;
    private static final double LNG_MIN = 73.75;
    private static final double LNG_MAX = 74.00;

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

    // ── Property 6: 2-opt does not increase route time ────────────────────────

    /**
     * Feature: affinity-shift-allocation, Property 6:
     * For any route of 3 or more shipments,
     * the total route time after optimize() SHALL be ≤ the input route time.
     */
    @Property(tries = 100)
    void twoOptDoesNotIncreaseRouteTime(
            @ForAll("routesOfAtLeastThree") @NotEmpty List<Shipment> route) throws Exception {

        TravelTimeCacheService cache = buildCache();
        TwoOptRouteOptimizer optimizer = new TwoOptRouteOptimizer();

        double before = optimizer.routeTime(route, cache, HUB_LAT, HUB_LNG);
        List<Shipment> optimized = optimizer.optimize(route, cache, HUB_LAT, HUB_LNG, 100);
        double after = optimizer.routeTime(optimized, cache, HUB_LAT, HUB_LNG);

        assertThat(after)
                .as("2-opt must not increase route time (before=%.4f, after=%.4f)", before, after)
                .isLessThanOrEqualTo(before + 1e-9);
    }

    // ── Property 7: 2-opt preserves route contents ────────────────────────────

    /**
     * Feature: affinity-shift-allocation, Property 7:
     * For any route, the set of shipments in the output of optimize()
     * SHALL be identical to the set of shipments in the input.
     */
    @Property(tries = 100)
    void twoOptPreservesRouteContents(
            @ForAll("anyRoutes") List<Shipment> route) throws Exception {

        TravelTimeCacheService cache = buildCache();
        TwoOptRouteOptimizer optimizer = new TwoOptRouteOptimizer();

        List<Shipment> optimized = optimizer.optimize(route, cache, HUB_LAT, HUB_LNG, 100);

        Set<String> inputIds  = route.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        Set<String> outputIds = optimized.stream().map(Shipment::getShippingId).collect(Collectors.toSet());

        assertThat(outputIds)
                .as("2-opt must preserve all shipments in the route")
                .isEqualTo(inputIds);

        assertThat(optimized)
                .as("2-opt must preserve route size")
                .hasSize(route.size());
    }

    // ── Providers ─────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<Shipment>> routesOfAtLeastThree() {
        return shipmentArbitrary().list().ofMinSize(3).ofMaxSize(8);
    }

    @Provide
    Arbitrary<List<Shipment>> anyRoutes() {
        return shipmentArbitrary().list().ofMinSize(0).ofMaxSize(8);
    }

    private Arbitrary<Shipment> shipmentArbitrary() {
        Arbitrary<Double> latArb = Arbitraries.doubles().between(LAT_MIN, LAT_MAX).ofScale(5);
        Arbitrary<Double> lngArb = Arbitraries.doubles().between(LNG_MIN, LNG_MAX).ofScale(5);

        return Combinators.combine(latArb, lngArb)
                .as((lat, lng) -> Shipment.builder()
                        .shippingId(String.format("%.5f,%.5f", lat, lng))
                        .dropLatitude(lat)
                        .dropLongitude(lng)
                        .orderType("Prepaid")
                        .build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
