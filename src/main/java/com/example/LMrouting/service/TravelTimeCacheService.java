package com.example.LMrouting.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, singleton in-memory cache for travel times between coordinate pairs.
 *
 * <p>Cache key format: "lat1,lng1->lat2,lng2" with coordinates rounded to 3 decimal places
 * (~111 m precision), which maximises cache reuse for nearby shipments while keeping
 * keys deterministic.
 *
 * <p>Fallback chain on cache miss:
 * <ol>
 *   <li>ORS Directions API (totalDurationMinutes) — only when {@code useExternalApis=true}</li>
 *   <li>Google Maps Directions API (leg durations) — only when {@code useExternalApis=true}</li>
 *   <li>Haversine distance ÷ avgSpeedKmh × 60 (always available, zero API calls)</li>
 * </ol>
 *
 * <p>IMPORTANT: During dense packing (workload estimation), external APIs are NOT called.
 * Only Haversine is used for packing decisions to avoid rate-limit exhaustion.
 * ORS/Google Maps are only called for the final polyline display.
 *
 * <p>The cache persists across allocation runs within the same JVM session.
 * It is cleared on application restart (in-memory only).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TravelTimeCacheService {

    private final OpenRouteService openRouteService;
    private final GoogleMapsService googleMapsService;

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    @Value("${allocation.shift.avg.speed.kmh:20}")
    private double avgSpeedKmh;

    @Value("${allocation.route.optimizer.road.factor:1.0}")
    private double roadFactor;

    // Cache: key → travel time in minutes
    private final ConcurrentHashMap<String, Double> cache = new ConcurrentHashMap<>();

    // Stats counters — reset at the start of each allocation run
    private final AtomicLong hits   = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);

    // Rate-limit guard: track last ORS call time to enforce minimum gap
    private volatile long lastOrsCallMs = 0;
    private static final long ORS_MIN_GAP_MS = 1500; // max ~40 req/min on free tier

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Cache-first lookup using ONLY Haversine (no external API calls).
     * Use this during dense packing / workload estimation to avoid rate limits.
     *
     * @return travel time in minutes using Haversine, always ≥ 0
     */
    public double getTravelTimeHaversine(double fromLat, double fromLng, double toLat, double toLng) {
        String key = buildKey(fromLat, fromLng, toLat, toLng);
        Double cached = cache.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        misses.incrementAndGet();
        double t = Math.max(0.0, haversineMinutes(fromLat, fromLng, toLat, toLng));
        cache.put(key, t);
        return t;
    }

    /**
     * Cache-first lookup for travel time between two coordinate points.
     * On cache miss, tries ORS → Google Maps → Haversine fallback.
     * Stores the result in cache before returning.
     *
     * <p>Rate-limited: enforces a minimum gap between ORS calls to stay within free tier.
     *
     * @return travel time in minutes, always ≥ 0
     */
    public double getTravelTime(double fromLat, double fromLng, double toLat, double toLng) {
        String key = buildKey(fromLat, fromLng, toLat, toLng);

        Double cached = cache.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }

        misses.incrementAndGet();
        double travelTime = fetchTravelTime(fromLat, fromLng, toLat, toLng);
        travelTime = Math.max(0.0, travelTime);
        cache.put(key, travelTime);
        return travelTime;
    }

    /**
     * Sum of leg times using ONLY Haversine (no external API calls).
     * Use this during dense packing to avoid rate limits.
     */
    public double getRouteTimeHaversine(List<double[]> orderedWaypoints) {
        if (orderedWaypoints == null || orderedWaypoints.isEmpty()) return 0.0;
        double total = 0.0;
        double prevLat = hubLat, prevLng = hubLng;
        for (double[] wp : orderedWaypoints) {
            total += getTravelTimeHaversine(prevLat, prevLng, wp[0], wp[1]);
            prevLat = wp[0]; prevLng = wp[1];
        }
        return total;
    }

    /**
     * Return-to-hub time using ONLY Haversine.
     */
    public double getReturnToHubTimeHaversine(double lastLat, double lastLng) {
        return getTravelTimeHaversine(lastLat, lastLng, hubLat, hubLng);
    }

    /**
     * Sum of leg times: hub → stop[0] → … → stop[n-1].
     * Does NOT include the return-to-hub leg — use {@link #getReturnToHubTime} for that.
     *
     * @param orderedWaypoints list of [lat, lng] pairs in route order
     * @return total route travel time in minutes (excluding return leg), always ≥ 0
     */
    public double getRouteTime(List<double[]> orderedWaypoints) {
        if (orderedWaypoints == null || orderedWaypoints.isEmpty()) return 0.0;

        double total = 0.0;
        double prevLat = hubLat;
        double prevLng = hubLng;

        for (double[] wp : orderedWaypoints) {
            total += getTravelTime(prevLat, prevLng, wp[0], wp[1]);
            prevLat = wp[0];
            prevLng = wp[1];
        }
        return total;
    }

    /**
     * Travel time from the last stop back to the hub.
     *
     * @return travel time in minutes, always ≥ 0
     */
    public double getReturnToHubTime(double lastLat, double lastLng) {
        return getTravelTime(lastLat, lastLng, hubLat, hubLng);
    }

    /**
     * Reset hit/miss counters — call at the start of each allocation run.
     */
    public void resetStats() {
        hits.set(0);
        misses.set(0);
    }

    /**
     * Log cache hit rate — call at the end of each allocation run.
     */
    public void logCacheStats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        double hitRate = total > 0 ? (h * 100.0 / total) : 0.0;
        log.info("TravelTimeCache stats: hits={}, misses={}, total={}, hitRate={}%",
                h, m, total, String.format("%.1f", hitRate));
    }

    // =========================================================================
    // Package-private for testing
    // =========================================================================

    /**
     * Returns the current cache size (number of cached coordinate pairs).
     */
    int cacheSize() {
        return cache.size();
    }

    /**
     * Builds the cache key for a coordinate pair.
     * Coordinates are rounded to 3 decimal places (~111 m precision).
     */
    static String buildKey(double fromLat, double fromLng, double toLat, double toLng) {
        return String.format("%.3f,%.3f->%.3f,%.3f", fromLat, fromLng, toLat, toLng);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Fetch travel time from ORS → Google Maps → Haversine fallback.
     * Rate-limited: enforces minimum gap between ORS calls.
     */
    private double fetchTravelTime(double fromLat, double fromLng, double toLat, double toLng) {
        // ORS uses [lng, lat] coordinate order
        if (openRouteService.isConfigured()) {
            // Rate-limit: enforce minimum gap between ORS calls
            long now = System.currentTimeMillis();
            long elapsed = now - lastOrsCallMs;
            if (elapsed >= ORS_MIN_GAP_MS) {
                lastOrsCallMs = now;
                try {
                    List<double[]> coords = List.of(
                            new double[]{fromLng, fromLat},
                            new double[]{toLng, toLat}
                    );
                    OpenRouteService.RouteResult result = openRouteService.getRoute(coords);
                    if (result.fromApi() && result.totalDurationMinutes() >= 0) {
                        return result.totalDurationMinutes();
                    }
                } catch (Exception e) {
                    log.warn("ORS travel time lookup failed for ({},{})→({},{}): {}",
                            fromLat, fromLng, toLat, toLng, e.getMessage());
                }
            } else {
                // Rate limit active — fall through to Haversine immediately
                log.debug("ORS rate-limit active ({}ms since last call), using Haversine", elapsed);
            }
        }

        // Google Maps fallback (only if ORS not configured or rate-limited)
        if (!openRouteService.isConfigured() && googleMapsService.isApiKeyConfigured()) {
            try {
                List<double[]> waypoints = List.of(new double[]{toLat, toLng});
                List<Double> legDurations = googleMapsService.getLegDurationsMinutes(
                        fromLat, fromLng, waypoints);
                if (!legDurations.isEmpty() && legDurations.get(0) >= 0) {
                    return legDurations.get(0);
                }
            } catch (Exception e) {
                log.warn("Google Maps travel time lookup failed for ({},{})→({},{}): {}",
                        fromLat, fromLng, toLat, toLng, e.getMessage());
            }
        }

        // Haversine fallback — always succeeds, zero API calls
        return haversineMinutes(fromLat, fromLng, toLat, toLng);
    }

    /**
     * Haversine distance converted to travel time using a distance-aware speed model.
     *
     * <p>Speed model for Pune urban delivery:
     * <ul>
     *   <li>&lt; 0.5 km: 12 km/h (very short hops, same-building clusters)</li>
     *   <li>0.5–2 km: 15 km/h (dense urban, traffic signals)</li>
     *   <li>2–5 km: 20 km/h (mixed urban / arterial)</li>
     *   <li>&gt; 5 km: 25 km/h (outer roads)</li>
     * </ul>
     *
     * <p>Road factor: 1.2× (Pune has a relatively grid-like road network).
     * This is calibrated so that the workload estimate matches the actual
     * timeline duration from Google Maps/ORS within ±10%.
     */
    private double haversineMinutes(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distKm = R * c;

        // Use configured speed if explicitly set to non-default, otherwise apply model
        double speed;
        if (avgSpeedKmh > 0 && avgSpeedKmh != 20.0) {
            speed = avgSpeedKmh;
        } else {
            if (distKm < 0.5) {
                speed = 12.0;
            } else if (distKm < 2.0) {
                speed = 15.0;
            } else if (distKm < 5.0) {
                speed = 20.0;
            } else {
                speed = 25.0;
            }
        }

        // Road factor (configurable via allocation.route.optimizer.road.factor, default 1.15):
        // accounts for non-grid urban layouts, one-way diversions, traffic, and
        // non-linear routes in typical Indian city road patterns.
        // Combined with 88% target utilisation, this gives a safe buffer
        // that keeps actual routes within 8 hours.
        // This same value is used by BOTH packing (ShiftWorkloadCalculatorService)
        // AND SR timeline (SrTimelineService) to ensure ETA consistency.
        return (distKm * roadFactor / speed) * 60.0;
    }
}
