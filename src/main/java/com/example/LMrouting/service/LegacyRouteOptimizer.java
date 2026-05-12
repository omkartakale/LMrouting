package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Legacy route optimization strategy: nearest-neighbour seed + 2-opt improvement.
 *
 * <p>This preserves the original Phase 7 behavior from before the cluster-first
 * route-second optimizer was introduced. It is available as a fallback via the
 * {@code allocation.route.optimizer.strategy=legacy} configuration property.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Build a nearest-neighbour ordering starting from the hub</li>
 *   <li>Apply 2-opt improvement passes (up to maxIterations)</li>
 * </ol>
 */
@Service
@Slf4j
public class LegacyRouteOptimizer implements RouteOptimizationStrategy {

    private final TravelTimeCacheService travelTimeCache;
    private final TwoOptRouteOptimizer twoOptOptimizer;

    private static final int DEFAULT_MAX_ITERATIONS = 100;

    public LegacyRouteOptimizer(TravelTimeCacheService travelTimeCache) {
        this.travelTimeCache = travelTimeCache;
        this.twoOptOptimizer = new TwoOptRouteOptimizer();
    }

    @Override
    public List<Shipment> optimize(List<Shipment> shipments, double hubLat, double hubLng) {
        if (shipments == null || shipments.isEmpty()) {
            return List.of();
        }
        if (shipments.size() <= 2) {
            return new ArrayList<>(shipments);
        }

        // Step 1: Nearest-neighbour seed from hub
        List<Shipment> nnOrdered = nearestNeighbourOrder(shipments, hubLat, hubLng);

        // Step 2: 2-opt improvement
        return twoOptOptimizer.optimize(nnOrdered, travelTimeCache, hubLat, hubLng, DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Build nearest-neighbour ordering starting from hub coordinates.
     */
    private List<Shipment> nearestNeighbourOrder(List<Shipment> shipments, double hubLat, double hubLng) {
        List<Shipment> remaining = new ArrayList<>(shipments);
        List<Shipment> ordered = new ArrayList<>(shipments.size());

        double currentLat = hubLat;
        double currentLng = hubLng;

        while (!remaining.isEmpty()) {
            int nearestIdx = -1;
            double nearestDist = Double.MAX_VALUE;

            for (int i = 0; i < remaining.size(); i++) {
                Shipment s = remaining.get(i);
                double dist = haversine(currentLat, currentLng, s.getDropLatitude(), s.getDropLongitude());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestIdx = i;
                }
            }

            Shipment nearest = remaining.remove(nearestIdx);
            ordered.add(nearest);
            currentLat = nearest.getDropLatitude();
            currentLng = nearest.getDropLongitude();
        }

        return ordered;
    }

    /**
     * Haversine distance in km between two points.
     */
    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
