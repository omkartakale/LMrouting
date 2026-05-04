package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Affinity Matching Service.
 *
 * After standard routing creates K routes (one per SR), this service
 * re-assigns routes to SRs based on their pincode affinity.
 *
 * Flow:
 *   1. Standard routing produces: { "SR-001": [shipments], "SR-002": [shipments], ... }
 *   2. User defines affinity: { "SR-001": ["411001","411002"], "SR-003": ["411038"] }
 *   3. This service matches routes to SRs:
 *      - For each SR with affinity, find the route with most shipments in their affinity pincodes
 *      - Assign that route to that SR
 *      - SRs without affinity get remaining routes (by best fit or round-robin)
 */
@Service
@Slf4j
public class AffinityMatchingService {

    /**
     * Re-assign routes to SRs based on affinity.
     *
     * @param routes          Standard routing output: srName → list of shipments
     * @param srAffinities    SR affinity map: srName → set of preferred pincodes
     * @return                New assignment: srName → list of shipments (same routes, different SR names)
     */
    public Map<String, List<Shipment>> matchRoutesToSRs(
            Map<String, List<Shipment>> routes,
            Map<String, Set<String>> srAffinities) {

        if (routes == null || routes.isEmpty()) return routes;
        if (srAffinities == null || srAffinities.isEmpty()) {
            log.info("AffinityMatching: no affinities defined, keeping standard assignment");
            return routes;
        }

        // Extract route labels and their shipments
        List<String> routeLabels = new ArrayList<>(routes.keySet());
        List<List<Shipment>> routeShipments = routeLabels.stream()
                .map(routes::get).collect(Collectors.toList());

        // Track which routes are assigned
        boolean[] routeAssigned = new boolean[routeLabels.size()];
        Map<String, List<Shipment>> result = new LinkedHashMap<>();

        // Phase 1: Assign routes to SRs WITH affinity (greedy best-match)
        // Sort SRs by specificity (fewer affinity pincodes = more specific = assign first)
        List<Map.Entry<String, Set<String>>> affinitySRs = srAffinities.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .sorted(Comparator.comparingInt(e -> e.getValue().size()))
                .collect(Collectors.toList());

        for (Map.Entry<String, Set<String>> entry : affinitySRs) {
            String srName = entry.getKey();
            Set<String> affinityPincodes = entry.getValue();

            // Find the unassigned route with the most shipments in this SR's affinity pincodes
            int bestRouteIdx = -1;
            int bestScore = -1;

            for (int i = 0; i < routeShipments.size(); i++) {
                if (routeAssigned[i]) continue;

                int score = countShipmentsInPincodes(routeShipments.get(i), affinityPincodes);
                if (score > bestScore) {
                    bestScore = score;
                    bestRouteIdx = i;
                }
            }

            if (bestRouteIdx >= 0) {
                routeAssigned[bestRouteIdx] = true;
                result.put(srName, routeShipments.get(bestRouteIdx));
                log.info("AffinityMatching: {} → route {} ({} affinity shipments out of {})",
                        srName, routeLabels.get(bestRouteIdx), bestScore,
                        routeShipments.get(bestRouteIdx).size());
            }
        }

        // Phase 2: Assign remaining routes to SRs WITHOUT affinity
        List<String> allSRs = new ArrayList<>(routes.keySet());
        List<String> unassignedSRs = allSRs.stream()
                .filter(sr -> !result.containsKey(sr))
                .collect(Collectors.toList());

        int unassignedSRIdx = 0;
        for (int i = 0; i < routeShipments.size(); i++) {
            if (routeAssigned[i]) continue;
            if (unassignedSRIdx >= unassignedSRs.size()) break;

            String srName = unassignedSRs.get(unassignedSRIdx++);
            result.put(srName, routeShipments.get(i));
            log.info("AffinityMatching: {} → route {} (no affinity, assigned remaining)",
                    srName, routeLabels.get(i));
        }

        log.info("AffinityMatching: {} SRs matched ({} with affinity, {} without)",
                result.size(), affinitySRs.size(), unassignedSRs.size());

        return result;
    }

    /**
     * Count how many shipments in a route fall within the given pincodes.
     */
    private int countShipmentsInPincodes(List<Shipment> shipments, Set<String> pincodes) {
        if (shipments == null || pincodes == null) return 0;
        return (int) shipments.stream()
                .filter(s -> s.getDropPincode() != null && pincodes.contains(s.getDropPincode()))
                .count();
    }

    /**
     * Compute affinity score for a given assignment.
     * Returns: { srName → { "affinityShipments": N, "totalShipments": M, "affinityPct": P } }
     */
    public Map<String, Map<String, Object>> computeAffinityScores(
            Map<String, List<Shipment>> assignment,
            Map<String, Set<String>> srAffinities) {

        Map<String, Map<String, Object>> scores = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> entry : assignment.entrySet()) {
            String sr = entry.getKey();
            List<Shipment> shipments = entry.getValue();
            Set<String> affinity = srAffinities.getOrDefault(sr, Set.of());

            int affinityCount = countShipmentsInPincodes(shipments, affinity);
            int total = shipments.size();
            double pct = total > 0 ? (affinityCount * 100.0 / total) : 0;

            Map<String, Object> score = new LinkedHashMap<>();
            score.put("affinityShipments", affinityCount);
            score.put("totalShipments", total);
            score.put("affinityPct", Math.round(pct * 10.0) / 10.0);
            score.put("affinityPincodes", affinity);
            scores.put(sr, score);
        }
        return scores;
    }
}
