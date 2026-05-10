package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;

import java.util.ArrayList;
import java.util.List;

/**
 * 2-opt route optimiser for SR shipment routes.
 *
 * <p>This is a plain Java class (no Spring annotation) so it can be unit-tested
 * without a Spring context. It is instantiated directly by
 * {@link AffinityShiftAllocationService}.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>For each pair (i, j) where 0 ≤ i &lt; j &lt; n, compute the gain from
 *       reversing the segment [i..j].</li>
 *   <li>If gain &gt; 0, apply the reversal and restart the pass.</li>
 *   <li>Stop when a full pass finds no improvement, or {@code maxIterations} is reached.</li>
 * </ol>
 *
 * <p>Uses cached travel times exclusively — no ORS or Google Maps API calls are made
 * during the optimisation pass.
 *
 * <p>Skips optimisation and returns the input unchanged when {@code route.size() < 3}
 * (already optimal for 0–2 stops).
 */
public class TwoOptRouteOptimizer {

    /**
     * Apply 2-opt improvement to an ordered route.
     * Uses Haversine-only cached travel times — no API calls during optimisation.
     *
     * @param route         ordered shipments (nearest-neighbour seed)
     * @param cache         travel time cache (read-only during optimisation)
     * @param hubLat        hub latitude
     * @param hubLng        hub longitude
     * @param maxIterations stop after this many full passes with no improvement
     * @return improved route (new list; input is not mutated)
     */
    public List<Shipment> optimize(List<Shipment> route,
                                   TravelTimeCacheService cache,
                                   double hubLat, double hubLng,
                                   int maxIterations) {
        if (route == null || route.size() < 3) {
            return route == null ? List.of() : new ArrayList<>(route);
        }

        List<Shipment> current = new ArrayList<>(route);
        int n = current.size();
        int iterations = 0;

        while (iterations < maxIterations) {
            boolean improved = false;

            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    double gain = twoOptGain(current, i, j, cache, hubLat, hubLng);
                    if (gain > 1e-9) {
                        reverse(current, i, j);
                        improved = true;
                    }
                }
            }

            iterations++;
            if (!improved) break;
        }

        return current;
    }

    /**
     * Compute total route time using Haversine-only (no API calls).
     */
    public double routeTime(List<Shipment> route,
                             TravelTimeCacheService cache,
                             double hubLat, double hubLng) {
        if (route == null || route.isEmpty()) return 0.0;

        double total = 0.0;
        double prevLat = hubLat;
        double prevLng = hubLng;

        for (Shipment s : route) {
            total += cache.getTravelTimeHaversine(prevLat, prevLng, s.getDropLatitude(), s.getDropLongitude());
            prevLat = s.getDropLatitude();
            prevLng = s.getDropLongitude();
        }
        total += cache.getTravelTimeHaversine(prevLat, prevLng, hubLat, hubLng);
        return total;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Compute the gain from reversing segment [i..j] in the route.
     * Positive gain means the reversal reduces total route time.
     *
     * <p>For a route: … → A → [i..j] → B → …
     * The current cost of edges adjacent to the segment is:
     *   cost(prev(i) → i) + cost(j → next(j))
     * After reversal:
     *   cost(prev(i) → j) + cost(i → next(j))
     *
     * <p>Where prev(0) = hub and next(n-1) = hub.
     */
    private double twoOptGain(List<Shipment> route, int i, int j,
                               TravelTimeCacheService cache,
                               double hubLat, double hubLng) {
        int n = route.size();

        double prevLat = (i == 0) ? hubLat : route.get(i - 1).getDropLatitude();
        double prevLng = (i == 0) ? hubLng : route.get(i - 1).getDropLongitude();

        double nextLat = (j == n - 1) ? hubLat : route.get(j + 1).getDropLatitude();
        double nextLng = (j == n - 1) ? hubLng : route.get(j + 1).getDropLongitude();

        double iLat = route.get(i).getDropLatitude();
        double iLng = route.get(i).getDropLongitude();
        double jLat = route.get(j).getDropLatitude();
        double jLng = route.get(j).getDropLongitude();

        // Use Haversine-only — no API calls during 2-opt
        double currentCost = cache.getTravelTimeHaversine(prevLat, prevLng, iLat, iLng)
                           + cache.getTravelTimeHaversine(jLat, jLng, nextLat, nextLng);

        double newCost = cache.getTravelTimeHaversine(prevLat, prevLng, jLat, jLng)
                       + cache.getTravelTimeHaversine(iLat, iLng, nextLat, nextLng);

        return currentCost - newCost;
    }

    /**
     * Reverse the sub-list from index {@code from} to {@code to} (inclusive) in-place.
     */
    private static void reverse(List<Shipment> route, int from, int to) {
        while (from < to) {
            Shipment tmp = route.get(from);
            route.set(from, route.get(to));
            route.set(to, tmp);
            from++;
            to--;
        }
    }
}
