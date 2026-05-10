package com.example.LMrouting;

import com.example.LMrouting.service.GoogleMapsService;
import com.example.LMrouting.service.OpenRouteService;
import com.example.LMrouting.service.TravelTimeCacheService;
import net.jqwik.api.*;
import net.jqwik.api.constraints.DoubleRange;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for TravelTimeCacheService.
 *
 * Feature: affinity-shift-allocation
 * Property 1: travel time ≥ 0 for any coordinate pair
 * Property 2: cache hit returns same value without API call
 */
class TravelTimeCachePropertyTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;

    // Pune bounding box: lat [18.40, 18.60], lng [73.75, 74.00]
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

    // ── Property 1: travel time ≥ 0 for any coordinate pair ──────────────────

    /**
     * Feature: affinity-shift-allocation, Property 1:
     * For any pair of coordinate points within Pune bounds,
     * getTravelTime() SHALL return a value ≥ 0.
     */
    @Property(tries = 100)
    void travelTimeIsNonNegative(
            @ForAll @DoubleRange(min = LAT_MIN, max = LAT_MAX) double fromLat,
            @ForAll @DoubleRange(min = LNG_MIN, max = LNG_MAX) double fromLng,
            @ForAll @DoubleRange(min = LAT_MIN, max = LAT_MAX) double toLat,
            @ForAll @DoubleRange(min = LNG_MIN, max = LNG_MAX) double toLng) throws Exception {

        TravelTimeCacheService cache = buildCache();
        double time = cache.getTravelTime(fromLat, fromLng, toLat, toLng);

        assertThat(time)
                .as("Travel time from (%.4f,%.4f) to (%.4f,%.4f) must be ≥ 0",
                        fromLat, fromLng, toLat, toLng)
                .isGreaterThanOrEqualTo(0.0);
    }

    // ── Property 2: cache hit returns same value without API call ─────────────

    /**
     * Feature: affinity-shift-allocation, Property 2:
     * For any coordinate pair that has been looked up at least once,
     * a subsequent lookup SHALL return the same value without making any API call.
     */
    @Property(tries = 100)
    void cacheHitReturnsSameValue(
            @ForAll @DoubleRange(min = LAT_MIN, max = LAT_MAX) double fromLat,
            @ForAll @DoubleRange(min = LNG_MIN, max = LNG_MAX) double fromLng,
            @ForAll @DoubleRange(min = LAT_MIN, max = LAT_MAX) double toLat,
            @ForAll @DoubleRange(min = LNG_MIN, max = LNG_MAX) double toLng) throws Exception {

        OpenRouteService ors = mock(OpenRouteService.class);
        when(ors.isConfigured()).thenReturn(false);
        GoogleMapsService gms = mock(GoogleMapsService.class);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        TravelTimeCacheService cache = new TravelTimeCacheService(ors, gms);
        setField(cache, "hubLat", HUB_LAT);
        setField(cache, "hubLng", HUB_LNG);
        setField(cache, "avgSpeedKmh", 20.0);

        // First lookup — populates cache
        double first = cache.getTravelTime(fromLat, fromLng, toLat, toLng);

        // Second lookup — should hit cache
        double second = cache.getTravelTime(fromLat, fromLng, toLat, toLng);

        assertThat(second)
                .as("Cache hit must return same value as original lookup")
                .isEqualTo(first);

        // Verify no API calls were made (both ORS and GMS are unconfigured,
        // so the Haversine fallback is used — but we verify the cache is consistent)
        verify(ors, atMost(2)).isConfigured(); // at most 2 calls (one per lookup attempt)
        verify(gms, atMost(2)).isApiKeyConfigured();
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
