package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * Optimizes delivery sequence for a single SR's shipment list.
 *
 * Routing priority:
 * 1. OpenRouteService (free, real roads, Indian OSM data) — if ORS key configured
 * 2. Google Maps Directions API — if Google key configured
 * 3. Nearest-neighbor heuristic (straight-line fallback)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RouteOptimizerService {

    private final GoogleMapsService googleMapsService;
    private final OpenRouteService openRouteService;

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    private static final int TIMEOUT_SECONDS = 8;

    /**
     * Order shipments for one SR and assign route_sequence values (1-based).
     * Returns the ordered list with routeSequence set on each shipment.
     */
    public List<Shipment> optimizeRoute(String srName, List<Shipment> shipments) {
        if (shipments == null || shipments.isEmpty()) return new ArrayList<>();
        if (shipments.size() == 1) {
            shipments.get(0).setRouteSequence(1);
            return new ArrayList<>(shipments);
        }

        // Always start with nearest-neighbor as baseline
        List<Shipment> ordered = nearestNeighborOrder(shipments);

        // Try ORS first (free, real roads)
        if (openRouteService.isConfigured()) {
            try {
                List<Shipment> orsOrdered = orsOptimize(srName, shipments, ordered);
                if (orsOrdered != null) ordered = orsOrdered;
            } catch (Exception e) {
                log.warn("RouteOptimizerService: ORS fallback for SR '{}' — {}", srName, e.getMessage());
            }
        }
        // Try Google Maps if ORS not configured
        else if (googleMapsService.isApiKeyConfigured()) {
            try {
                List<Shipment> gmOrdered = googleMapsOptimize(srName, shipments, ordered);
                if (gmOrdered != null) ordered = gmOrdered;
            } catch (Exception e) {
                log.warn("RouteOptimizerService: Google Maps fallback for SR '{}' — {}", srName, e.getMessage());
            }
        }

        // Assign route_sequence
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setRouteSequence(i + 1);
        }
        return ordered;
    }

    /**
     * Estimate total Haversine distance: hub → stop[0] → … → stop[n-1] → hub.
     * Used for fairness scoring. When ORS is configured, actual road distance
     * is fetched separately via getRoutePolyline().
     */
    public double estimateDistanceKm(List<Shipment> orderedShipments) {
        if (orderedShipments == null || orderedShipments.isEmpty()) return 0.0;

        double total = 0.0;
        double prevLat = hubLat, prevLng = hubLng;

        for (Shipment s : orderedShipments) {
            total += GoogleMapsService.haversine(prevLat, prevLng, s.getDropLatitude(), s.getDropLongitude());
            prevLat = s.getDropLatitude();
            prevLng = s.getDropLongitude();
        }
        total += GoogleMapsService.haversine(prevLat, prevLng, hubLat, hubLng);
        return total;
    }

    /**
     * Get actual road polyline for a route using ORS or Google Maps.
     * Returns list of [lat, lng] pairs for drawing on the map.
     * Falls back to straight-line waypoints if no API is configured.
     */
    public List<double[]> getRoutePolyline(List<Shipment> orderedShipments) {
        return getRoutePolylineInternal(orderedShipments, false);
    }

    /**
     * Get Google Maps road polyline specifically.
     * Used for the side-by-side comparison view.
     * Falls back to straight lines if Google Maps key not configured.
     */
    public List<double[]> getGoogleMapsPolyline(List<Shipment> orderedShipments) {
        return getRoutePolylineInternal(orderedShipments, true);
    }

    /**
     * Get ORS road polyline specifically.
     * Used for the side-by-side comparison view.
     * Falls back to straight lines if ORS key not configured.
     */
    public List<double[]> getOrsPolyline(List<Shipment> orderedShipments) {
        if (orderedShipments == null || orderedShipments.isEmpty()) {
            return List.of(new double[]{hubLat, hubLng});
        }
        List<double[]> orsCoords = new ArrayList<>();
        orsCoords.add(new double[]{hubLng, hubLat});
        for (Shipment s : orderedShipments) {
            orsCoords.add(new double[]{s.getDropLongitude(), s.getDropLatitude()});
        }
        orsCoords.add(new double[]{hubLng, hubLat});

        if (openRouteService.isConfigured()) {
            try {
                OpenRouteService.RouteResult result = openRouteService.getRoute(orsCoords);
                if (result.fromApi() && !result.polylinePoints().isEmpty()) {
                    return result.polylinePoints();
                }
            } catch (Exception e) {
                log.warn("RouteOptimizerService: ORS polyline failed — {}", e.getMessage());
            }
        }
        return buildStraightLinePolyline(orderedShipments);
    }

    private List<double[]> getRoutePolylineInternal(List<Shipment> orderedShipments, boolean forceGoogle) {
        if (orderedShipments == null || orderedShipments.isEmpty()) {
            return List.of(new double[]{hubLat, hubLng});
        }

        // Try ORS first (unless forcing Google)
        if (!forceGoogle && openRouteService.isConfigured()) {
            List<double[]> orsCoords = new ArrayList<>();
            orsCoords.add(new double[]{hubLng, hubLat});
            for (Shipment s : orderedShipments) {
                orsCoords.add(new double[]{s.getDropLongitude(), s.getDropLatitude()});
            }
            orsCoords.add(new double[]{hubLng, hubLat});
            try {
                OpenRouteService.RouteResult result = openRouteService.getRoute(orsCoords);
                if (result.fromApi() && !result.polylinePoints().isEmpty()) {
                    return result.polylinePoints();
                }
            } catch (Exception e) {
                log.warn("RouteOptimizerService: ORS polyline failed — {}", e.getMessage());
            }
        }

        // Try Google Maps
        if (googleMapsService.isApiKeyConfigured()) {
            try {
                List<double[]> waypoints = new ArrayList<>();
                for (Shipment s : orderedShipments) {
                    waypoints.add(new double[]{s.getDropLatitude(), s.getDropLongitude()});
                }
                GoogleMapsService.DirectionsResult result =
                        googleMapsService.getOptimizedRoute(hubLat, hubLng, waypoints);
                if (result.polylinePoints() != null && !result.polylinePoints().isEmpty()) {
                    return result.polylinePoints().stream()
                            .map(p -> new double[]{p.getLat(), p.getLng()})
                            .collect(java.util.stream.Collectors.toList());
                }
            } catch (Exception e) {
                log.warn("RouteOptimizerService: Google Maps polyline failed — {}", e.getMessage());
            }
        }

        return buildStraightLinePolyline(orderedShipments);
    }

    private List<double[]> buildStraightLinePolyline(List<Shipment> orderedShipments) {
        List<double[]> fallback = new ArrayList<>();
        fallback.add(new double[]{hubLat, hubLng});
        for (Shipment s : orderedShipments) {
            fallback.add(new double[]{s.getDropLatitude(), s.getDropLongitude()});
        }
        fallback.add(new double[]{hubLat, hubLng});
        return fallback;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private List<Shipment> nearestNeighborOrder(List<Shipment> shipments) {
        List<Shipment> remaining = new ArrayList<>(shipments);
        List<Shipment> ordered = new ArrayList<>(shipments.size());
        double curLat = hubLat, curLng = hubLng;

        while (!remaining.isEmpty()) {
            double minDist = Double.MAX_VALUE;
            Shipment nearest = null;
            for (Shipment s : remaining) {
                double dist = GoogleMapsService.haversine(curLat, curLng,
                        s.getDropLatitude(), s.getDropLongitude());
                if (dist < minDist) { minDist = dist; nearest = s; }
            }
            remaining.remove(nearest);
            ordered.add(nearest);
            curLat = nearest.getDropLatitude();
            curLng = nearest.getDropLongitude();
        }
        return ordered;
    }

    private List<Shipment> orsOptimize(String srName, List<Shipment> shipments, List<Shipment> fallback) {
        // ORS optimization: use nearest-neighbor order but get actual road distances
        // For waypoint optimization, we use the nearest-neighbor result as the order
        // (ORS free tier doesn't include optimization endpoint, only routing)
        // The key benefit is real road polylines for display
        return null; // Return null to use nearest-neighbor; polyline is fetched separately
    }

    private List<Shipment> googleMapsOptimize(String srName, List<Shipment> shipments, List<Shipment> fallback) {
        List<Shipment> candidates = shipments.size() > 25 ? shipments.subList(0, 25) : shipments;
        List<double[]> waypoints = new ArrayList<>();
        for (Shipment s : candidates) {
            waypoints.add(new double[]{s.getDropLatitude(), s.getDropLongitude()});
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<GoogleMapsService.DirectionsResult> future = executor.submit(
                () -> googleMapsService.getOptimizedRoute(hubLat, hubLng, waypoints));
        executor.shutdown();

        try {
            GoogleMapsService.DirectionsResult result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<Integer> order = result.waypointOrder();
            if (order == null || order.isEmpty() || order.size() != candidates.size()) return fallback;

            List<Shipment> optimized = new ArrayList<>();
            for (int idx : order) optimized.add(candidates.get(idx));

            if (shipments.size() > 25) {
                List<Shipment> remainder = new ArrayList<>(shipments.subList(25, shipments.size()));
                double curLat = optimized.get(optimized.size() - 1).getDropLatitude();
                double curLng = optimized.get(optimized.size() - 1).getDropLongitude();
                while (!remainder.isEmpty()) {
                    double minDist = Double.MAX_VALUE;
                    Shipment nearest = null;
                    for (Shipment s : remainder) {
                        double dist = GoogleMapsService.haversine(curLat, curLng,
                                s.getDropLatitude(), s.getDropLongitude());
                        if (dist < minDist) { minDist = dist; nearest = s; }
                    }
                    remainder.remove(nearest);
                    optimized.add(nearest);
                    curLat = nearest.getDropLatitude();
                    curLng = nearest.getDropLongitude();
                }
            }
            return optimized;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("RouteOptimizerService: Google Maps timed out for SR '{}'", srName);
            return fallback;
        } catch (Exception e) {
            log.warn("RouteOptimizerService: Google Maps error for SR '{}' — {}", srName, e.getMessage());
            return fallback;
        }
    }
}
