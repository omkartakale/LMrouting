package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClusterFirstRouteOptimizer.
 *
 * Validates: Requirements 2.1, 2.2
 *
 * Tests cover:
 * - Angular sweep with 360° coverage → angularly contiguous clusters
 * - Cluster sizes ≤ maxClusterSize for various shipment counts
 * - Natural gap detection with tight groups separated by large angular gaps
 * - Intra-cluster TSP produces valid tours (all shipments visited exactly once)
 * - CFRS route time ≤ nearest-neighbour + 2-opt for Pune-like distributions
 * - Edge-positioned hub fallback to balanced K-Means
 * - Quality guard for small shipment counts
 */
class ClusterFirstRouteOptimizerTest {

    private static final double HUB_LAT = 18.4600561;
    private static final double HUB_LNG = 73.8884305;

    private ClusterFirstRouteOptimizer optimizer;
    private TravelTimeCacheService realCache;

    @BeforeEach
    void setUp() throws Exception {
        // Build a real cache backed by Haversine (no ORS/GMS)
        OpenRouteService ors = mock(OpenRouteService.class);
        when(ors.isConfigured()).thenReturn(false);
        GoogleMapsService gms = mock(GoogleMapsService.class);
        when(gms.isApiKeyConfigured()).thenReturn(false);

        realCache = new TravelTimeCacheService(ors, gms);
        setField(realCache, "hubLat", HUB_LAT);
        setField(realCache, "hubLng", HUB_LNG);
        setField(realCache, "avgSpeedKmh", 20.0);

        optimizer = new ClusterFirstRouteOptimizer(realCache);
        setField(optimizer, "maxClusterSize", 12);
    }

    // =========================================================================
    // Angular sweep with 360° coverage → angularly contiguous clusters
    // =========================================================================

    @Test
    void angularSweep_360DegreeCoverage_producesAngularlyContiguousClusters() throws Exception {
        // Place 24 shipments evenly around the hub (360° coverage)
        List<Shipment> shipments = buildCircularRoute(24, 0.04);

        // Use angularSweepOptimize directly (package-private)
        List<Shipment> result = optimizer.angularSweepOptimize(shipments, HUB_LAT, HUB_LNG);

        // Verify all shipments are present
        assertThat(result).hasSize(24);
        Set<String> inputIds = shipments.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        Set<String> outputIds = result.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        assertThat(outputIds).isEqualTo(inputIds);

        // Verify angular contiguity: consecutive shipments in the result should have
        // angles that don't jump wildly (within each cluster, angles should be close)
        List<Double> resultAngles = result.stream()
                .map(s -> Math.atan2(s.getDropLatitude() - HUB_LAT, s.getDropLongitude() - HUB_LNG))
                .collect(Collectors.toList());

        // Check that the route doesn't have excessive back-and-forth in angle space
        // Count large angular jumps (> π/2 = 90°)
        int largeJumps = 0;
        for (int i = 1; i < resultAngles.size(); i++) {
            double diff = Math.abs(resultAngles.get(i) - resultAngles.get(i - 1));
            if (diff > Math.PI) diff = 2 * Math.PI - diff; // wrap-around
            if (diff > Math.PI / 2) largeJumps++;
        }
        // With 24 evenly spaced shipments in clusters of 12, we expect at most
        // a few inter-cluster jumps, not many
        assertThat(largeJumps).isLessThanOrEqualTo(4);
    }

    // =========================================================================
    // Cluster sizes ≤ maxClusterSize for various shipment counts
    // =========================================================================

    @Test
    void clusterSizes_10Shipments_allClustersWithinMaxSize() throws Exception {
        verifyClusterSizesWithinMax(10, 12);
    }

    @Test
    void clusterSizes_25Shipments_allClustersWithinMaxSize() throws Exception {
        verifyClusterSizesWithinMax(25, 12);
    }

    @Test
    void clusterSizes_45Shipments_allClustersWithinMaxSize() throws Exception {
        verifyClusterSizesWithinMax(45, 12);
    }

    @Test
    void clusterSizes_80Shipments_allClustersWithinMaxSize() throws Exception {
        verifyClusterSizesWithinMax(80, 12);
    }

    @Test
    void clusterSizes_withSmallMaxClusterSize_respectsLimit() throws Exception {
        setField(optimizer, "maxClusterSize", 5);
        verifyClusterSizesWithinMax(20, 5);
    }

    // =========================================================================
    // Natural gap detection
    // =========================================================================

    @Test
    void naturalGapDetection_threeGroupsWithLargeGaps_cutsAtGaps() throws Exception {
        // Place 3 tight groups with large angular gaps between them.
        // atan2 uses (lat - hubLat) as Y and (lng - hubLng) as X.
        // Group 1: angles near 0 radians (East of hub: same lat, lng > hubLng)
        // Group 2: angles near 2π/3 radians (~120°)
        // Group 3: angles near -2π/3 radians (~-120° = 240°)
        List<Shipment> shipments = new ArrayList<>();
        int id = 0;
        double radius = 0.03;

        // Group 1: angles near 0 rad (East) — small spread of ±0.05 rad
        for (int i = 0; i < 6; i++) {
            double angle = 0.0 + (i - 2) * 0.02; // tight cluster around 0 rad
            double lat = HUB_LAT + radius * Math.sin(angle);
            double lng = HUB_LNG + radius * Math.cos(angle);
            shipments.add(shipment("G1_" + id++, lat, lng));
        }

        // Group 2: angles near 2π/3 rad (120°) — small spread
        for (int i = 0; i < 6; i++) {
            double angle = 2 * Math.PI / 3 + (i - 2) * 0.02;
            double lat = HUB_LAT + radius * Math.sin(angle);
            double lng = HUB_LNG + radius * Math.cos(angle);
            shipments.add(shipment("G2_" + id++, lat, lng));
        }

        // Group 3: angles near -2π/3 rad (240°) — small spread
        for (int i = 0; i < 6; i++) {
            double angle = -2 * Math.PI / 3 + (i - 2) * 0.02;
            double lat = HUB_LAT + radius * Math.sin(angle);
            double lng = HUB_LNG + radius * Math.cos(angle);
            shipments.add(shipment("G3_" + id++, lat, lng));
        }

        // maxClusterSize must be > total count for gap detection to be the only
        // partitioning mechanism. But the method returns early if size <= maxClusterSize.
        // So we set maxClusterSize to something between group size and total count
        // to force the gap detection logic to run.
        setField(optimizer, "maxClusterSize", 10);

        // Build ShipmentAngle list and sort by angle (mimicking the internal flow)
        List<ClusterFirstRouteOptimizer.ShipmentAngle> shipmentAngles = new ArrayList<>();
        for (Shipment s : shipments) {
            double angle = Math.atan2(
                    s.getDropLatitude() - HUB_LAT,
                    s.getDropLongitude() - HUB_LNG
            );
            shipmentAngles.add(new ClusterFirstRouteOptimizer.ShipmentAngle(s, angle));
        }
        shipmentAngles.sort(Comparator.comparingDouble(ClusterFirstRouteOptimizer.ShipmentAngle::angle));

        List<List<Shipment>> clusters = optimizer.partitionWithGapDetection(shipmentAngles);

        // With 3 tight groups and large gaps (~2π/3 ≈ 2.09 rad between groups vs ~0.02 within),
        // we expect 3 clusters since the inter-group gaps far exceed 2× median gap
        assertThat(clusters).hasSize(3);

        // Each cluster should contain exactly 6 shipments
        for (List<Shipment> cluster : clusters) {
            assertThat(cluster).hasSize(6);
        }

        // Verify each cluster contains shipments from the same group
        for (List<Shipment> cluster : clusters) {
            String prefix = cluster.get(0).getShippingId().substring(0, 2);
            for (Shipment s : cluster) {
                assertThat(s.getShippingId()).startsWith(prefix);
            }
        }
    }

    // =========================================================================
    // Intra-cluster TSP produces valid tours (all shipments visited exactly once)
    // =========================================================================

    @Test
    void intraClusterTSP_allShipmentsVisitedExactlyOnce() {
        List<Shipment> shipments = buildCircularRoute(30, 0.04);

        List<Shipment> result = optimizer.optimize(shipments, HUB_LAT, HUB_LNG);

        // All shipments must appear exactly once
        assertThat(result).hasSize(30);
        Set<String> ids = result.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        assertThat(ids).hasSize(30);

        Set<String> inputIds = shipments.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        assertThat(ids).isEqualTo(inputIds);
    }

    @Test
    void intraClusterTSP_noDuplicateShipments() {
        List<Shipment> shipments = buildPuneLikeDistribution(20);

        List<Shipment> result = optimizer.optimize(shipments, HUB_LAT, HUB_LNG);

        // Check no duplicates
        List<String> resultIds = result.stream().map(Shipment::getShippingId).toList();
        Set<String> uniqueIds = new HashSet<>(resultIds);
        assertThat(uniqueIds).hasSize(resultIds.size());
    }

    // =========================================================================
    // CFRS route time ≤ nearest-neighbour + 2-opt for Pune-like distributions
    // =========================================================================

    @Test
    void cfrsRouteTime_puneLikeDistribution_notWorseThanNNTwoOpt() {
        // Generate a distribution spread around the hub (not edge-positioned)
        // to ensure the angular sweep + quality guard path is taken
        List<Shipment> shipments = buildSpreadDistribution(30);

        // Verify edge detection does NOT trigger for this distribution
        List<Double> angles = shipments.stream()
                .map(s -> Math.atan2(s.getDropLatitude() - HUB_LAT, s.getDropLongitude() - HUB_LNG))
                .collect(Collectors.toList());
        assertThat(optimizer.isEdgePositionedHub(angles)).isFalse();

        List<Shipment> cfrsResult = optimizer.optimize(shipments, HUB_LAT, HUB_LNG);

        // Compute NN + 2-opt baseline
        TwoOptRouteOptimizer twoOpt = new TwoOptRouteOptimizer();
        List<Shipment> nnResult = optimizer.nearestNeighbourWithTwoOpt(shipments, HUB_LAT, HUB_LNG);

        double cfrsTime = twoOpt.routeTime(cfrsResult, realCache, HUB_LAT, HUB_LNG);
        double nnTime = twoOpt.routeTime(nnResult, realCache, HUB_LAT, HUB_LNG);

        // Quality guard in optimize() ensures CFRS ≤ NN+2-opt
        assertThat(cfrsTime).isLessThanOrEqualTo(nnTime + 0.01);
    }

    @Test
    void cfrsRouteTime_largerPuneDistribution_notWorseThanNNTwoOpt() {
        List<Shipment> shipments = buildSpreadDistribution(45);

        // Verify edge detection does NOT trigger
        List<Double> angles = shipments.stream()
                .map(s -> Math.atan2(s.getDropLatitude() - HUB_LAT, s.getDropLongitude() - HUB_LNG))
                .collect(Collectors.toList());
        assertThat(optimizer.isEdgePositionedHub(angles)).isFalse();

        List<Shipment> cfrsResult = optimizer.optimize(shipments, HUB_LAT, HUB_LNG);

        TwoOptRouteOptimizer twoOpt = new TwoOptRouteOptimizer();
        List<Shipment> nnResult = optimizer.nearestNeighbourWithTwoOpt(shipments, HUB_LAT, HUB_LNG);

        double cfrsTime = twoOpt.routeTime(cfrsResult, realCache, HUB_LAT, HUB_LNG);
        double nnTime = twoOpt.routeTime(nnResult, realCache, HUB_LAT, HUB_LNG);

        assertThat(cfrsTime).isLessThanOrEqualTo(nnTime + 0.01);
    }

    // =========================================================================
    // Edge-positioned hub fallback: all shipments in a 90° arc
    // =========================================================================

    @Test
    void edgePositionedHub_allShipmentsIn90DegreeArc_fallsBackToBalancedKMeans() {
        // Place all shipments in a narrow 90° arc (NE quadrant only)
        List<Shipment> shipments = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double angle = Math.toRadians(10 + i * 4); // 10° to 86° — all within 90° arc
            double radius = 0.02 + (i % 5) * 0.01;
            double lat = HUB_LAT + radius * Math.sin(angle);
            double lng = HUB_LNG + radius * Math.cos(angle);
            shipments.add(shipment("E" + i, lat, lng));
        }

        // Verify isEdgePositionedHub detects this
        List<Double> angles = shipments.stream()
                .map(s -> Math.atan2(s.getDropLatitude() - HUB_LAT, s.getDropLongitude() - HUB_LNG))
                .collect(Collectors.toList());
        assertThat(optimizer.isEdgePositionedHub(angles)).isTrue();

        // The optimize method should still produce a valid result (via K-Means fallback)
        List<Shipment> result = optimizer.optimize(shipments, HUB_LAT, HUB_LNG);

        assertThat(result).hasSize(20);
        Set<String> inputIds = shipments.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        Set<String> outputIds = result.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        assertThat(outputIds).isEqualTo(inputIds);
    }

    @Test
    void edgePositionedHub_360DegreeSpread_notDetectedAsEdge() {
        // Evenly distributed shipments should NOT trigger edge detection
        List<Shipment> shipments = buildCircularRoute(20, 0.04);
        List<Double> angles = shipments.stream()
                .map(s -> Math.atan2(s.getDropLatitude() - HUB_LAT, s.getDropLongitude() - HUB_LNG))
                .collect(Collectors.toList());

        assertThat(optimizer.isEdgePositionedHub(angles)).isFalse();
    }

    @Test
    void edgePositionedHub_balancedKMeans_producesValidRoute() {
        // Place all shipments in a narrow arc to trigger K-Means fallback
        List<Shipment> shipments = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            double angle = Math.toRadians(30 + i * 3); // 30° to 72° — narrow arc
            double lat = HUB_LAT + 0.03 * Math.sin(angle);
            double lng = HUB_LNG + 0.03 * Math.cos(angle);
            shipments.add(shipment("K" + i, lat, lng));
        }

        List<Shipment> result = optimizer.optimizeWithBalancedKMeans(shipments, HUB_LAT, HUB_LNG);

        // All shipments visited exactly once
        assertThat(result).hasSize(15);
        Set<String> ids = result.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        assertThat(ids).hasSize(15);
    }

    // =========================================================================
    // Quality guard: 4 shipments in a line → better-of-two result is kept
    // =========================================================================

    @Test
    void qualityGuard_smallShipmentCount_keepsBetterResult() {
        // 4 shipments in a line (small n where NN+2-opt might beat angular sweep)
        List<Shipment> shipments = List.of(
                shipment("L1", HUB_LAT + 0.01, HUB_LNG + 0.01),
                shipment("L2", HUB_LAT + 0.02, HUB_LNG + 0.02),
                shipment("L3", HUB_LAT + 0.03, HUB_LNG + 0.03),
                shipment("L4", HUB_LAT + 0.04, HUB_LNG + 0.04)
        );

        List<Shipment> result = optimizer.optimize(shipments, HUB_LAT, HUB_LNG);

        // Verify all shipments present
        assertThat(result).hasSize(4);
        Set<String> ids = result.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        assertThat(ids).containsExactlyInAnyOrder("L1", "L2", "L3", "L4");

        // The result should be the better of angular sweep vs NN+2-opt
        TwoOptRouteOptimizer twoOpt = new TwoOptRouteOptimizer();
        double resultTime = twoOpt.routeTime(result, realCache, HUB_LAT, HUB_LNG);

        // Compute NN+2-opt independently
        List<Shipment> nnResult = optimizer.nearestNeighbourWithTwoOpt(shipments, HUB_LAT, HUB_LNG);
        double nnTime = twoOpt.routeTime(nnResult, realCache, HUB_LAT, HUB_LNG);

        // Result should be ≤ NN+2-opt (quality guard ensures best is kept)
        assertThat(resultTime).isLessThanOrEqualTo(nnTime + 0.01);
    }

    @Test
    void qualityGuard_3Shipments_returnsValidRoute() {
        // Very small count — should still work correctly
        List<Shipment> shipments = List.of(
                shipment("Q1", HUB_LAT + 0.02, HUB_LNG),
                shipment("Q2", HUB_LAT, HUB_LNG + 0.02),
                shipment("Q3", HUB_LAT - 0.02, HUB_LNG)
        );

        List<Shipment> result = optimizer.optimize(shipments, HUB_LAT, HUB_LNG);

        assertThat(result).hasSize(3);
        Set<String> ids = result.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        assertThat(ids).containsExactlyInAnyOrder("Q1", "Q2", "Q3");
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    void optimize_nullInput_returnsEmptyList() {
        List<Shipment> result = optimizer.optimize(null, HUB_LAT, HUB_LNG);
        assertThat(result).isEmpty();
    }

    @Test
    void optimize_emptyInput_returnsEmptyList() {
        List<Shipment> result = optimizer.optimize(List.of(), HUB_LAT, HUB_LNG);
        assertThat(result).isEmpty();
    }

    @Test
    void optimize_singleShipment_returnsSameShipment() {
        List<Shipment> shipments = List.of(shipment("S1", 18.50, 73.90));
        List<Shipment> result = optimizer.optimize(shipments, HUB_LAT, HUB_LNG);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getShippingId()).isEqualTo("S1");
    }

    @Test
    void optimize_twoShipments_returnsBothShipments() {
        List<Shipment> shipments = List.of(
                shipment("S1", 18.50, 73.90),
                shipment("S2", 18.52, 73.85)
        );
        List<Shipment> result = optimizer.optimize(shipments, HUB_LAT, HUB_LNG);
        assertThat(result).hasSize(2);
        Set<String> ids = result.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        assertThat(ids).containsExactlyInAnyOrder("S1", "S2");
    }

    // =========================================================================
    // connectClusters validation
    // =========================================================================

    @Test
    void connectClusters_singleCluster_returnsClusterAsIs() {
        List<Shipment> cluster = List.of(
                shipment("C1", 18.50, 73.90),
                shipment("C2", 18.51, 73.91)
        );
        List<List<Shipment>> clusters = List.of(cluster);

        List<Shipment> result = optimizer.connectClusters(clusters, HUB_LAT, HUB_LNG);
        assertThat(result).hasSize(2);
    }

    @Test
    void connectClusters_multipleClusters_allShipmentsPresent() {
        List<Shipment> cluster1 = List.of(
                shipment("A1", 18.48, 73.85),
                shipment("A2", 18.49, 73.86)
        );
        List<Shipment> cluster2 = List.of(
                shipment("B1", 18.52, 73.92),
                shipment("B2", 18.53, 73.93)
        );
        List<List<Shipment>> clusters = List.of(cluster1, cluster2);

        List<Shipment> result = optimizer.connectClusters(clusters, HUB_LAT, HUB_LNG);
        assertThat(result).hasSize(4);
        Set<String> ids = result.stream().map(Shipment::getShippingId).collect(Collectors.toSet());
        assertThat(ids).containsExactlyInAnyOrder("A1", "A2", "B1", "B2");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void verifyClusterSizesWithinMax(int shipmentCount, int maxSize) throws Exception {
        setField(optimizer, "maxClusterSize", maxSize);
        List<Shipment> shipments = buildCircularRoute(shipmentCount, 0.04);

        // Build ShipmentAngle list and sort
        List<ClusterFirstRouteOptimizer.ShipmentAngle> shipmentAngles = new ArrayList<>();
        for (Shipment s : shipments) {
            double angle = Math.atan2(
                    s.getDropLatitude() - HUB_LAT,
                    s.getDropLongitude() - HUB_LNG
            );
            shipmentAngles.add(new ClusterFirstRouteOptimizer.ShipmentAngle(s, angle));
        }
        shipmentAngles.sort(Comparator.comparingDouble(ClusterFirstRouteOptimizer.ShipmentAngle::angle));

        List<List<Shipment>> clusters = optimizer.partitionWithGapDetection(shipmentAngles);

        // All clusters must be ≤ maxSize
        for (List<Shipment> cluster : clusters) {
            assertThat(cluster.size()).isLessThanOrEqualTo(maxSize);
        }

        // Total shipments across all clusters must equal input
        int totalInClusters = clusters.stream().mapToInt(List::size).sum();
        assertThat(totalInClusters).isEqualTo(shipmentCount);
    }

    /**
     * Build shipments evenly distributed in a circle around the hub.
     */
    private static List<Shipment> buildCircularRoute(int n, double radius) {
        List<Shipment> shipments = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            double lat = HUB_LAT + radius * Math.cos(angle);
            double lng = HUB_LNG + radius * Math.sin(angle);
            shipments.add(shipment("S" + i, lat, lng));
        }
        return shipments;
    }

    /**
     * Build a Pune-like distribution: shipments scattered in the delivery area
     * LAT 18.40-18.62, LNG 73.75-74.05 with some clustering.
     */
    private static List<Shipment> buildPuneLikeDistribution(int n) {
        List<Shipment> shipments = new ArrayList<>();
        Random rng = new Random(42); // deterministic seed

        // Create clusters in different parts of Pune
        double[][] clusterCenters = {
                {18.45, 73.82}, // West Pune
                {18.52, 73.95}, // East Pune
                {18.55, 73.88}, // North Pune
                {18.42, 73.90}, // South Pune
                {18.50, 73.85}, // Central
        };

        for (int i = 0; i < n; i++) {
            double[] center = clusterCenters[i % clusterCenters.length];
            double lat = center[0] + (rng.nextDouble() - 0.5) * 0.04;
            double lng = center[1] + (rng.nextDouble() - 0.5) * 0.04;
            // Clamp to delivery area
            lat = Math.max(18.40, Math.min(18.62, lat));
            lng = Math.max(73.75, Math.min(74.05, lng));
            shipments.add(shipment("P" + i, lat, lng));
        }
        return shipments;
    }

    /**
     * Build a distribution spread evenly around the hub in all directions.
     * This ensures isEdgePositionedHub returns false, so the angular sweep
     * + quality guard path is exercised.
     */
    private static List<Shipment> buildSpreadDistribution(int n) {
        List<Shipment> shipments = new ArrayList<>();
        Random rng = new Random(123); // deterministic seed

        for (int i = 0; i < n; i++) {
            // Distribute evenly around the hub with some random perturbation
            double baseAngle = 2 * Math.PI * i / n;
            double angle = baseAngle + (rng.nextDouble() - 0.5) * 0.3;
            double radius = 0.02 + rng.nextDouble() * 0.04; // 0.02 to 0.06 degrees
            double lat = HUB_LAT + radius * Math.sin(angle);
            double lng = HUB_LNG + radius * Math.cos(angle);
            shipments.add(shipment("D" + i, lat, lng));
        }
        return shipments;
    }

    private static Shipment shipment(String id, double lat, double lng) {
        return Shipment.builder()
                .shippingId(id)
                .dropLatitude(lat)
                .dropLongitude(lng)
                .orderType("Prepaid")
                .build();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
