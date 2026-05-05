package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pincode-based clustering for affinity allocation.
 *
 * Instead of angular pie-slices, this groups shipments by pincode
 * and merges adjacent pincodes into N clusters (one per SR).
 *
 * Algorithm:
 *   1. Group shipments by pincode (using PincodeBoundaryService for lookup)
 *   2. Compute centroid of each pincode group
 *   3. Sort pincodes by angle from hub (like pie slices but pincode-aware)
 *   4. Greedily assign pincodes to N buckets, balancing shipment count
 *   5. Each bucket = one SR's route
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PincodeClusteringService {

    private final PincodeBoundaryService pincodeBoundaryService;

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    /**
     * Cluster shipments into N groups based on pincode boundaries.
     *
     * @param shipments  Valid shipments (with coordinates, within boundary)
     * @param numClusters Number of clusters (= number of SRs)
     * @return List of N lists of shipments (one per SR)
     */
    public List<List<Shipment>> clusterByPincode(List<Shipment> shipments, int numClusters) {
        if (shipments == null || shipments.isEmpty() || numClusters <= 0) {
            return List.of();
        }

        // Step 1: Group shipments by pincode
        Map<String, List<Shipment>> byPincode = new LinkedHashMap<>();
        List<Shipment> noPincode = new ArrayList<>();

        for (Shipment s : shipments) {
            String pin = s.getDropPincode();
            if (pin == null || pin.isBlank()) {
                // Try to resolve from coordinates
                if (pincodeBoundaryService.isLoaded()) {
                    pin = pincodeBoundaryService.findPincodeForPoint(s.getDropLatitude(), s.getDropLongitude());
                }
            }
            if (pin != null && !pin.isBlank()) {
                byPincode.computeIfAbsent(pin, k -> new ArrayList<>()).add(s);
            } else {
                noPincode.add(s);
            }
        }

        log.info("PincodeClustering: {} pincodes found, {} shipments without pincode",
                byPincode.size(), noPincode.size());

        if (byPincode.isEmpty()) {
            // Fallback: just split evenly
            return splitEvenly(shipments, numClusters);
        }

        // Step 2: Compute centroid of each pincode group and sort by angle from hub
        List<PincodeGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<Shipment>> entry : byPincode.entrySet()) {
            String pin = entry.getKey();
            List<Shipment> pinShipments = entry.getValue();
            double avgLat = pinShipments.stream().mapToDouble(Shipment::getDropLatitude).average().orElse(hubLat);
            double avgLng = pinShipments.stream().mapToDouble(Shipment::getDropLongitude).average().orElse(hubLng);
            double angle = Math.atan2(avgLng - hubLng, avgLat - hubLat);
            groups.add(new PincodeGroup(pin, pinShipments, avgLat, avgLng, angle));
        }

        // Sort by angle (geographic continuity)
        groups.sort(Comparator.comparingDouble(g -> g.angle));

        // Step 3: Greedily assign pincode groups to N buckets
        // Distribute pincodes contiguously, ensuring all buckets get shipments
        int totalShipments = (int) groups.stream().mapToLong(g -> g.shipments.size()).sum();
        int targetPerBucket = Math.max(1, totalShipments / numClusters);

        List<List<Shipment>> clusters = new ArrayList<>();
        for (int i = 0; i < numClusters; i++) clusters.add(new ArrayList<>());

        int bucketIdx = 0;
        for (int g = 0; g < groups.size(); g++) {
            PincodeGroup group = groups.get(g);
            clusters.get(bucketIdx).addAll(group.shipments);

            // Move to next bucket if:
            // - current bucket has enough shipments
            // - there are enough remaining pincodes to fill remaining buckets
            int remainingBuckets = numClusters - 1 - bucketIdx;
            int remainingGroups = groups.size() - 1 - g;
            if (clusters.get(bucketIdx).size() >= targetPerBucket
                    && bucketIdx < numClusters - 1
                    && remainingGroups > remainingBuckets) {
                bucketIdx++;
            }
        }

        // Distribute no-pincode shipments to nearest cluster
        for (Shipment s : noPincode) {
            int nearest = findNearestCluster(s, clusters);
            clusters.get(nearest).add(s);
        }

        // Log cluster sizes
        for (int i = 0; i < clusters.size(); i++) {
            Set<String> pins = clusters.get(i).stream()
                    .map(Shipment::getDropPincode)
                    .filter(p -> p != null && !p.isBlank())
                    .collect(Collectors.toSet());
            log.info("PincodeClustering: cluster {} → {} shipments, pincodes: {}",
                    i, clusters.get(i).size(), pins);
        }

        return clusters;
    }

    /**
     * Get pincode groups with their shipment counts (for UI display).
     */
    public List<Map<String, Object>> getPincodeGroups(List<Shipment> shipments) {
        Map<String, List<Shipment>> byPincode = new LinkedHashMap<>();
        for (Shipment s : shipments) {
            String pin = s.getDropPincode();
            if (pin != null && !pin.isBlank()) {
                byPincode.computeIfAbsent(pin, k -> new ArrayList<>()).add(s);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<Shipment>> entry : byPincode.entrySet()) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("pincode", entry.getKey());
            group.put("count", entry.getValue().size());
            double avgLat = entry.getValue().stream().mapToDouble(Shipment::getDropLatitude).average().orElse(0);
            double avgLng = entry.getValue().stream().mapToDouble(Shipment::getDropLongitude).average().orElse(0);
            group.put("centroidLat", avgLat);
            group.put("centroidLng", avgLng);
            result.add(group);
        }
        result.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));
        return result;
    }

    private int findNearestCluster(Shipment s, List<List<Shipment>> clusters) {
        double minDist = Double.MAX_VALUE;
        int nearest = 0;
        for (int i = 0; i < clusters.size(); i++) {
            if (clusters.get(i).isEmpty()) continue;
            double cLat = clusters.get(i).stream().mapToDouble(Shipment::getDropLatitude).average().orElse(hubLat);
            double cLng = clusters.get(i).stream().mapToDouble(Shipment::getDropLongitude).average().orElse(hubLng);
            double dist = Math.pow(s.getDropLatitude() - cLat, 2) + Math.pow(s.getDropLongitude() - cLng, 2);
            if (dist < minDist) { minDist = dist; nearest = i; }
        }
        return nearest;
    }

    private List<List<Shipment>> splitEvenly(List<Shipment> shipments, int n) {
        List<List<Shipment>> result = new ArrayList<>();
        for (int i = 0; i < n; i++) result.add(new ArrayList<>());
        for (int i = 0; i < shipments.size(); i++) {
            result.get(i % n).add(shipments.get(i));
        }
        return result;
    }

    private record PincodeGroup(String pincode, List<Shipment> shipments,
                                 double centroidLat, double centroidLng, double angle) {}
}
