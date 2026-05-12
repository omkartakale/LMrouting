package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Cluster-First Route-Second (CFRS) optimizer using Angular Sweep Partitioning.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Compute polar angle for each shipment relative to the hub</li>
 *   <li>Sort shipments by angle (sweep around the hub)</li>
 *   <li>Partition into size-constrained clusters, preferring cuts at natural angular gaps</li>
 *   <li>Solve intra-cluster TSP using 2-opt (near-optimal for small n ≤ 12-15)</li>
 *   <li>Connect clusters using nearest-pair heuristic</li>
 * </ol>
 *
 * <p>Fallback: If &gt; 80% of shipments have angles within a 180° arc (edge-positioned hub),
 * falls back to balanced K-Means with size constraints.
 *
 * <p>Quality guard: If the angular sweep route is worse than nearest-neighbour + 2-opt
 * (possible for very small n &lt; 6), keeps the better result.
 *
 * <p>Complexity: O(n log n) for the sweep + O(k × m²) for intra-cluster 2-opt where m ≤ maxClusterSize.
 */
@Service
@Slf4j
public class ClusterFirstRouteOptimizer implements RouteOptimizationStrategy {

    private final TravelTimeCacheService travelTimeCache;
    private final TwoOptRouteOptimizer twoOptOptimizer;

    @Value("${allocation.route.optimizer.cluster.size:12}")
    private int maxClusterSize;

    public ClusterFirstRouteOptimizer(TravelTimeCacheService travelTimeCache) {
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

        // Check for edge-positioned hub: if > 80% of shipments within a 180° arc
        List<Double> angles = computeAngles(shipments, hubLat, hubLng);
        if (isEdgePositionedHub(angles)) {
            log.debug("Edge-positioned hub detected, falling back to balanced K-Means partitioning");
            return optimizeWithBalancedKMeans(shipments, hubLat, hubLng);
        }

        // Angular Sweep Partitioning
        List<Shipment> sweepResult = angularSweepOptimize(shipments, hubLat, hubLng);

        // Quality guard: compare with nearest-neighbour + 2-opt
        List<Shipment> nnResult = nearestNeighbourWithTwoOpt(shipments, hubLat, hubLng);

        double sweepTime = twoOptOptimizer.routeTime(sweepResult, travelTimeCache, hubLat, hubLng);
        double nnTime = twoOptOptimizer.routeTime(nnResult, travelTimeCache, hubLat, hubLng);

        if (nnTime < sweepTime) {
            log.debug("Quality guard: NN+2-opt ({} min) beats angular sweep ({} min) for {} shipments",
                    String.format("%.1f", nnTime), String.format("%.1f", sweepTime), shipments.size());
            return nnResult;
        }

        return sweepResult;
    }

    // =========================================================================
    // Angular Sweep Partitioning
    // =========================================================================

    /**
     * Main angular sweep optimization pipeline.
     */
    List<Shipment> angularSweepOptimize(List<Shipment> shipments, double hubLat, double hubLng) {
        // Step 1: Compute angles and create indexed pairs
        List<ShipmentAngle> shipmentAngles = new ArrayList<>();
        for (Shipment s : shipments) {
            double angle = Math.atan2(
                    s.getDropLatitude() - hubLat,
                    s.getDropLongitude() - hubLng
            );
            shipmentAngles.add(new ShipmentAngle(s, angle));
        }

        // Step 2: Sort by angle
        shipmentAngles.sort(Comparator.comparingDouble(ShipmentAngle::angle));

        // Step 3: Partition into clusters with natural gap detection
        List<List<Shipment>> clusters = partitionWithGapDetection(shipmentAngles);

        // Step 4: Intra-cluster TSP using 2-opt
        List<List<Shipment>> optimizedClusters = new ArrayList<>();
        for (List<Shipment> cluster : clusters) {
            List<Shipment> optimized = twoOptOptimizer.optimize(
                    cluster, travelTimeCache, hubLat, hubLng, 100);
            optimizedClusters.add(optimized);
        }

        // Step 5: Inter-cluster connection using nearest-pair heuristic
        return connectClusters(optimizedClusters, hubLat, hubLng);
    }

    /**
     * Partition sorted shipments into clusters, preferring cuts at natural angular gaps.
     * A gap is considered "natural" if it exceeds 2× the median gap.
     */
    List<List<Shipment>> partitionWithGapDetection(List<ShipmentAngle> sortedShipments) {
        if (sortedShipments.isEmpty()) {
            return List.of();
        }
        if (sortedShipments.size() <= maxClusterSize) {
            return List.of(sortedShipments.stream()
                    .map(ShipmentAngle::shipment)
                    .collect(Collectors.toList()));
        }

        // Compute gaps between consecutive shipments
        List<Double> gaps = new ArrayList<>();
        for (int i = 0; i < sortedShipments.size() - 1; i++) {
            double gap = sortedShipments.get(i + 1).angle() - sortedShipments.get(i).angle();
            gaps.add(gap);
        }

        // Compute median gap
        double medianGap = computeMedian(gaps);
        double gapThreshold = 2.0 * medianGap;

        // Partition: cut at natural gaps or when maxClusterSize is reached
        List<List<Shipment>> clusters = new ArrayList<>();
        List<Shipment> currentCluster = new ArrayList<>();
        currentCluster.add(sortedShipments.get(0).shipment());

        for (int i = 1; i < sortedShipments.size(); i++) {
            double gap = sortedShipments.get(i).angle() - sortedShipments.get(i - 1).angle();
            boolean naturalGap = gap > gapThreshold && medianGap > 0;
            boolean clusterFull = currentCluster.size() >= maxClusterSize;

            if (naturalGap || clusterFull) {
                clusters.add(currentCluster);
                currentCluster = new ArrayList<>();
            }
            currentCluster.add(sortedShipments.get(i).shipment());
        }

        // Add the last cluster
        if (!currentCluster.isEmpty()) {
            clusters.add(currentCluster);
        }

        return clusters;
    }

    /**
     * Connect optimized clusters using nearest-pair heuristic.
     * For each pair of adjacent clusters, connect the last stop of cluster[i]
     * to the first stop of cluster[i+1] by finding the nearest pair.
     */
    List<Shipment> connectClusters(List<List<Shipment>> clusters, double hubLat, double hubLng) {
        if (clusters.isEmpty()) {
            return List.of();
        }
        if (clusters.size() == 1) {
            return new ArrayList<>(clusters.get(0));
        }

        // Order clusters by nearest-pair from hub
        List<List<Shipment>> orderedClusters = orderClustersByNearestToHub(clusters, hubLat, hubLng);

        // Concatenate clusters, potentially reordering endpoints for better connections
        List<Shipment> result = new ArrayList<>();
        for (int i = 0; i < orderedClusters.size(); i++) {
            List<Shipment> cluster = orderedClusters.get(i);
            if (i == 0) {
                result.addAll(cluster);
            } else {
                // Find best connection: check if reversing the cluster gives a shorter link
                Shipment lastOfPrev = result.get(result.size() - 1);
                Shipment firstOfCurrent = cluster.get(0);
                Shipment lastOfCurrent = cluster.get(cluster.size() - 1);

                double normalLink = travelTimeCache.getTravelTimeHaversine(
                        lastOfPrev.getDropLatitude(), lastOfPrev.getDropLongitude(),
                        firstOfCurrent.getDropLatitude(), firstOfCurrent.getDropLongitude());
                double reversedLink = travelTimeCache.getTravelTimeHaversine(
                        lastOfPrev.getDropLatitude(), lastOfPrev.getDropLongitude(),
                        lastOfCurrent.getDropLatitude(), lastOfCurrent.getDropLongitude());

                if (reversedLink < normalLink) {
                    // Reverse the cluster for better connection
                    List<Shipment> reversed = new ArrayList<>(cluster);
                    Collections.reverse(reversed);
                    result.addAll(reversed);
                } else {
                    result.addAll(cluster);
                }
            }
        }

        return result;
    }

    /**
     * Order clusters by nearest centroid to the hub, then greedily connect.
     */
    private List<List<Shipment>> orderClustersByNearestToHub(
            List<List<Shipment>> clusters, double hubLat, double hubLng) {

        List<List<Shipment>> remaining = new ArrayList<>(clusters);
        List<List<Shipment>> ordered = new ArrayList<>();

        // Start with the cluster whose centroid is nearest to the hub
        double currentLat = hubLat;
        double currentLng = hubLng;

        while (!remaining.isEmpty()) {
            int bestIdx = 0;
            double bestDist = Double.MAX_VALUE;

            for (int i = 0; i < remaining.size(); i++) {
                double[] centroid = computeCentroid(remaining.get(i));
                double dist = travelTimeCache.getTravelTimeHaversine(
                        currentLat, currentLng, centroid[0], centroid[1]);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestIdx = i;
                }
            }

            List<Shipment> next = remaining.remove(bestIdx);
            ordered.add(next);

            // Update current position to last shipment of this cluster
            Shipment last = next.get(next.size() - 1);
            currentLat = last.getDropLatitude();
            currentLng = last.getDropLongitude();
        }

        return ordered;
    }

    // =========================================================================
    // Edge-positioned hub fallback: Balanced K-Means
    // =========================================================================

    /**
     * Fallback for edge-positioned hubs: balanced K-Means with size constraints.
     * Partitions shipments into roughly equal groups based on distance from hub,
     * then optimizes each group with 2-opt.
     */
    List<Shipment> optimizeWithBalancedKMeans(List<Shipment> shipments, double hubLat, double hubLng) {
        int k = Math.max(1, (int) Math.ceil((double) shipments.size() / maxClusterSize));

        // Simple balanced partitioning: sort by distance from hub, then distribute round-robin
        List<Shipment> sortedByDistance = new ArrayList<>(shipments);
        sortedByDistance.sort(Comparator.comparingDouble(s ->
                travelTimeCache.getTravelTimeHaversine(hubLat, hubLng,
                        s.getDropLatitude(), s.getDropLongitude())));

        // Create k clusters with balanced sizes
        List<List<Shipment>> clusters = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            clusters.add(new ArrayList<>());
        }

        // Distribute shipments to clusters (nearest-distance grouping)
        int clusterIdx = 0;
        for (Shipment s : sortedByDistance) {
            clusters.get(clusterIdx).add(s);
            if (clusters.get(clusterIdx).size() >= maxClusterSize) {
                clusterIdx = Math.min(clusterIdx + 1, k - 1);
            }
        }

        // Remove empty clusters
        clusters.removeIf(List::isEmpty);

        // Optimize each cluster with 2-opt
        List<List<Shipment>> optimizedClusters = new ArrayList<>();
        for (List<Shipment> cluster : clusters) {
            List<Shipment> optimized = twoOptOptimizer.optimize(
                    cluster, travelTimeCache, hubLat, hubLng, 100);
            optimizedClusters.add(optimized);
        }

        // Connect clusters
        return connectClusters(optimizedClusters, hubLat, hubLng);
    }

    // =========================================================================
    // Nearest-Neighbour + 2-opt (for quality guard comparison)
    // =========================================================================

    /**
     * Nearest-neighbour heuristic followed by 2-opt improvement.
     * Used as a quality guard baseline.
     */
    List<Shipment> nearestNeighbourWithTwoOpt(List<Shipment> shipments, double hubLat, double hubLng) {
        List<Shipment> nnRoute = nearestNeighbourOrder(shipments, hubLat, hubLng);
        return twoOptOptimizer.optimize(nnRoute, travelTimeCache, hubLat, hubLng, 100);
    }

    /**
     * Greedy nearest-neighbour route construction starting from the hub.
     */
    private List<Shipment> nearestNeighbourOrder(List<Shipment> shipments, double hubLat, double hubLng) {
        List<Shipment> remaining = new ArrayList<>(shipments);
        List<Shipment> route = new ArrayList<>();

        double currentLat = hubLat;
        double currentLng = hubLng;

        while (!remaining.isEmpty()) {
            int nearestIdx = 0;
            double nearestTime = Double.MAX_VALUE;

            for (int i = 0; i < remaining.size(); i++) {
                Shipment s = remaining.get(i);
                double time = travelTimeCache.getTravelTimeHaversine(
                        currentLat, currentLng,
                        s.getDropLatitude(), s.getDropLongitude());
                if (time < nearestTime) {
                    nearestTime = time;
                    nearestIdx = i;
                }
            }

            Shipment nearest = remaining.remove(nearestIdx);
            route.add(nearest);
            currentLat = nearest.getDropLatitude();
            currentLng = nearest.getDropLongitude();
        }

        return route;
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    /**
     * Compute polar angles for all shipments relative to the hub.
     */
    private List<Double> computeAngles(List<Shipment> shipments, double hubLat, double hubLng) {
        return shipments.stream()
                .map(s -> Math.atan2(s.getDropLatitude() - hubLat, s.getDropLongitude() - hubLng))
                .collect(Collectors.toList());
    }

    /**
     * Detect if the hub is edge-positioned: &gt; 80% of shipments within a 180° arc.
     */
    boolean isEdgePositionedHub(List<Double> angles) {
        if (angles.size() < 3) {
            return false;
        }

        int n = angles.size();
        int threshold = (int) Math.ceil(n * 0.8);

        // Sort angles
        List<Double> sorted = new ArrayList<>(angles);
        Collections.sort(sorted);

        // Check every possible 180° (π radians) window
        for (int i = 0; i < n; i++) {
            double windowStart = sorted.get(i);
            double windowEnd = windowStart + Math.PI; // 180° arc

            // Count shipments within this window
            int count = 0;
            for (double angle : sorted) {
                // Normalize angle to be within [windowStart, windowStart + 2π)
                double normalized = angle;
                while (normalized < windowStart) {
                    normalized += 2 * Math.PI;
                }
                if (normalized <= windowEnd) {
                    count++;
                }
            }

            if (count >= threshold) {
                return true;
            }
        }

        return false;
    }

    /**
     * Compute the centroid (average lat/lng) of a cluster.
     */
    private double[] computeCentroid(List<Shipment> cluster) {
        double sumLat = 0, sumLng = 0;
        for (Shipment s : cluster) {
            sumLat += s.getDropLatitude();
            sumLng += s.getDropLongitude();
        }
        return new double[]{sumLat / cluster.size(), sumLng / cluster.size()};
    }

    /**
     * Compute the median of a list of doubles.
     */
    private double computeMedian(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
        }
        return sorted.get(mid);
    }

    // =========================================================================
    // Internal record for angle-shipment pairing
    // =========================================================================

    record ShipmentAngle(Shipment shipment, double angle) {}
}
