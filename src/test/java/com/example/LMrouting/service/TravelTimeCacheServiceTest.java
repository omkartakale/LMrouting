package com.example.LMrouting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TravelTimeCacheService.
 * Uses Mockito to stub ORS and Google Maps so no real API calls are made.
 */
class TravelTimeCacheServiceTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;

    private OpenRouteService ors;
    private GoogleMapsService gms;
    private TravelTimeCacheService cache;

    @BeforeEach
    void setUp() throws Exception {
        ors = mock(OpenRouteService.class);
        gms = mock(GoogleMapsService.class);
        cache = new TravelTimeCacheService(ors, gms);
        setField(cache, "hubLat", HUB_LAT);
        setField(cache, "hubLng", HUB_LNG);
        setField(cache, "avgSpeedKmh", 20.0);
        setField(cache, "roadFactor", 1.15);
        cache.resetStats();
    }

    // ── Cache key format ──────────────────────────────────────────────────────

    @Test
    void buildKey_formatsCoordinatesTo3DecimalPlaces() {
        String key = TravelTimeCacheService.buildKey(18.4600561, 73.8884305, 18.512, 73.856);
        assertThat(key).isEqualTo("18.460,73.888->18.512,73.856");
    }

    @Test
    void buildKey_roundsCoordinatesCorrectly() {
        String key = TravelTimeCacheService.buildKey(18.46049, 73.88849, 18.51249, 73.85649);
        assertThat(key).isEqualTo("18.460,73.888->18.512,73.856");
    }

    // ── Non-negative return ───────────────────────────────────────────────────

    @Test
    void getTravelTime_returnsNonNegativeWithHaversineFallback() {
        when(ors.isConfigured()).thenReturn(false);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        double time = cache.getTravelTime(HUB_LAT, HUB_LNG, HUB_LAT + 0.05, HUB_LNG + 0.05);
        assertThat(time).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void getTravelTime_samePointReturnsZeroOrNearZero() {
        when(ors.isConfigured()).thenReturn(false);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        double time = cache.getTravelTime(HUB_LAT, HUB_LNG, HUB_LAT, HUB_LNG);
        assertThat(time).isGreaterThanOrEqualTo(0.0);
        assertThat(time).isLessThan(0.1); // essentially zero
    }

    // ── Cache hit / miss ──────────────────────────────────────────────────────

    @Test
    void getTravelTime_secondCallReturnsCachedValue_noAdditionalApiCall() {
        when(ors.isConfigured()).thenReturn(false);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        double first  = cache.getTravelTime(HUB_LAT, HUB_LNG, 18.51, 73.85);
        double second = cache.getTravelTime(HUB_LAT, HUB_LNG, 18.51, 73.85);

        assertThat(first).isEqualTo(second);
        // ORS and GMS should never be called (both unconfigured, but verify no interaction)
        verify(ors, atMostOnce()).isConfigured();
    }

    @Test
    void getTravelTime_cacheGrowsOnNewPairs() {
        when(ors.isConfigured()).thenReturn(false);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        int before = cache.cacheSize();
        cache.getTravelTime(HUB_LAT, HUB_LNG, 18.51, 73.85);
        cache.getTravelTime(HUB_LAT, HUB_LNG, 18.52, 73.86);
        assertThat(cache.cacheSize()).isGreaterThan(before);
    }

    @Test
    void getTravelTime_cacheDoesNotGrowOnRepeatCall() {
        when(ors.isConfigured()).thenReturn(false);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        cache.getTravelTime(HUB_LAT, HUB_LNG, 18.51, 73.85);
        int sizeAfterFirst = cache.cacheSize();
        cache.getTravelTime(HUB_LAT, HUB_LNG, 18.51, 73.85);
        assertThat(cache.cacheSize()).isEqualTo(sizeAfterFirst);
    }

    // ── ORS fallback chain ────────────────────────────────────────────────────

    @Test
    void getTravelTime_usesOrsWhenConfigured() {
        when(ors.isConfigured()).thenReturn(true);
        when(ors.getRoute(any())).thenReturn(
                new OpenRouteService.RouteResult(List.of(), 5.0, 12.5, true));

        double time = cache.getTravelTime(HUB_LAT, HUB_LNG, 18.51, 73.85);

        assertThat(time).isEqualTo(12.5);
        verify(ors).getRoute(any());
        verify(gms, never()).getLegDurationsMinutes(anyDouble(), anyDouble(), any());
    }

    @Test
    void getTravelTime_fallsBackToGoogleMapsWhenOrsNotConfigured() {
        when(ors.isConfigured()).thenReturn(false);
        when(gms.isApiKeyConfigured()).thenReturn(true);
        when(gms.getLegDurationsMinutes(anyDouble(), anyDouble(), any()))
                .thenReturn(List.of(8.0, 7.5)); // [leg1, returnLeg]

        double time = cache.getTravelTime(HUB_LAT, HUB_LNG, 18.51, 73.85);

        assertThat(time).isEqualTo(8.0);
        verify(gms).getLegDurationsMinutes(anyDouble(), anyDouble(), any());
    }

    @Test
    void getTravelTime_fallsBackToHaversineWhenBothUnconfigured() {
        when(ors.isConfigured()).thenReturn(false);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        double time = cache.getTravelTime(HUB_LAT, HUB_LNG, 18.51, 73.85);

        assertThat(time).isGreaterThan(0.0);
        verify(ors, never()).getRoute(any());
        verify(gms, never()).getLegDurationsMinutes(anyDouble(), anyDouble(), any());
    }

    @Test
    void getTravelTime_fallsBackToHaversineWhenOrsFails() {
        when(ors.isConfigured()).thenReturn(true);
        when(ors.getRoute(any())).thenThrow(new RuntimeException("ORS unavailable"));
        when(gms.isApiKeyConfigured()).thenReturn(false);

        double time = cache.getTravelTime(HUB_LAT, HUB_LNG, 18.51, 73.85);

        assertThat(time).isGreaterThanOrEqualTo(0.0);
    }

    // ── getRouteTime ──────────────────────────────────────────────────────────

    @Test
    void getRouteTime_emptyListReturnsZero() {
        double time = cache.getRouteTime(List.of());
        assertThat(time).isEqualTo(0.0);
    }

    @Test
    void getRouteTime_singleWaypointReturnsSingleLegTime() {
        when(ors.isConfigured()).thenReturn(false);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        double[] wp = {18.51, 73.85};
        double routeTime = cache.getRouteTime(List.of(wp));
        double directTime = cache.getTravelTime(HUB_LAT, HUB_LNG, 18.51, 73.85);

        assertThat(routeTime).isEqualTo(directTime);
    }

    @Test
    void getRouteTime_multipleWaypointsAccumulatesLegs() {
        when(ors.isConfigured()).thenReturn(false);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        List<double[]> waypoints = List.of(
                new double[]{18.51, 73.85},
                new double[]{18.52, 73.86}
        );
        double routeTime = cache.getRouteTime(waypoints);
        assertThat(routeTime).isGreaterThan(0.0);
    }

    // ── getReturnToHubTime ────────────────────────────────────────────────────

    @Test
    void getReturnToHubTime_returnsNonNegative() {
        when(ors.isConfigured()).thenReturn(false);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        double time = cache.getReturnToHubTime(18.51, 73.85);
        assertThat(time).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void getReturnToHubTime_fromHubReturnsNearZero() {
        when(ors.isConfigured()).thenReturn(false);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        double time = cache.getReturnToHubTime(HUB_LAT, HUB_LNG);
        assertThat(time).isLessThan(0.1);
    }

    // ── resetStats / logCacheStats ────────────────────────────────────────────

    @Test
    void resetStats_doesNotThrow() {
        cache.resetStats();
        // No exception expected
    }

    @Test
    void logCacheStats_doesNotThrow() {
        when(ors.isConfigured()).thenReturn(false);
        when(gms.isApiKeyConfigured()).thenReturn(false);
        cache.getTravelTime(HUB_LAT, HUB_LNG, 18.51, 73.85);
        cache.getTravelTime(HUB_LAT, HUB_LNG, 18.51, 73.85); // hit
        cache.logCacheStats(); // should not throw
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
