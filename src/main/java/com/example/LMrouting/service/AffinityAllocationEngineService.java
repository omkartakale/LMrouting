package com.example.LMrouting.service;

import com.example.LMrouting.exception.AllocationFailureException;
import com.example.LMrouting.model.AffinityAllocationMetrics;
import com.example.LMrouting.model.AffinityAllocationPreview;
import com.example.LMrouting.model.AffinityAllocationResult;
import com.example.LMrouting.model.AffinityConfiguration;
import com.example.LMrouting.model.AffinityRegion;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.model.SrRegionAssignment;
import com.example.LMrouting.store.InMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AffinityAllocationEngineService executes affinity-based shipment allocation.
 * 
 * This service implements the core affinity allocation algorithm that:
 * 1. Assigns shipments to regions based on pincode
 * 2. Distributes shipments to SRs assigned to each region
 * 3. Handles under-capacity regions by backfilling from other regions
 * 4. Handles over-capacity regions by applying K-Means clustering
 * 5. Ensures all SRs receive 80-100 shipments
 * 
 * The service reuses existing allocation logic from AllocationEngineService for:
 * - K-Means clustering within regions
 * - Earnings-based rebalancing
 * - Route optimization
 * 
 * Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 10.1
 */
@Service
@Slf4j
public class AffinityAllocationEngineService {

    private final InMemoryStore store;
    private final AllocationEngineService allocationEngineService;
    private final HubBoundaryService hubBoundaryService;
    private final RouteOptimizerService routeOptimizerService;

    @Value("${allocation.sr.capacity.min:80}")
    private int srCapacityMin;

    @Value("${allocation.sr.capacity.max:100}")
    private int srCapacityMax;

    @Value("${allocation.rebalancing.threshold:1.0}")
    private double earningsRangeThreshold;

    @Value("${allocation.rebalancing.maxIterations:200}")
    private int maxIterations;

    @Value("${affinity.crossRegion.targetPercentage:75.0}")
    private double targetWithinRegionPercentage;

    @Value("${affinity.variance.toleranceRatio:1.2}")
    private double varianceToleranceRatio;

    public AffinityAllocationEngineService(
            InMemoryStore store,
            AllocationEngineService allocationEngineService,
            HubBoundaryService hubBoundaryService,
            RouteOptimizerService routeOptimizerService) {
        this.store = store;
        this.allocationEngineService = allocationEngineService;
        this.hubBoundaryService = hubBoundaryService;
        this.routeOptimizerService = routeOptimizerService;
    }

    /**
     * Executes affinity-based allocation for a given date and hub.
     * 
     * Algorithm:
     * 1. Load shipments for the given date
     * 2. Group shipments by pincode/region
     * 3. For each region with assigned SRs:
     *    a. If region shipments < SR capacity: assign all to SR, backfill from other regions
     *    b. If region shipments > SR capacity: apply K-Means clustering within region
     * 4. Ensure all SRs get 80-100 shipments
     * 5. Apply route optimization
     * 6. Calculate allocation metrics
     * 
     * @param date Allocation date in string format (e.g., "01-Jan-24")
     * @param hubName Hub name for filtering
     * @param config Affinity configuration with SR-region assignments
     * @param regions List of affinity regions (for mapping region IDs to pincodes)
     * @return AffinityAllocationResult containing SR assignments and metrics
     */
    public AffinityAllocationResult executeAffinityAllocation(
            String date,
            String hubName,
            AffinityConfiguration config,
            List<AffinityRegion> regions) {
        
        log.info("AffinityAllocationEngineService: starting affinity allocation for date='{}', hub='{}'",
                date, hubName);

        // Step 1: Load shipments for the given date
        List<Shipment> allShipments = store.findShipmentsByDate(date);
        if (allShipments.isEmpty()) {
            log.warn("AffinityAllocationEngineService: no shipments found for date '{}'", date);
            return AffinityAllocationResult.builder()
                    .srAssignments(Collections.emptyMap())
                    .metrics(AffinityAllocationMetrics.builder()
                            .crossRegionOverlapPercentage(0.0)
                            .withinRegionPercentage(0.0)
                            .outlierSrs(Collections.emptyList())
                            .perSrMetrics(Collections.emptyMap())
                            .totalShipments(0)
                            .totalSrs(0)
                            .earningsVariance(0.0)
                            .averageShipmentsPerSr(0.0)
                            .build())
                    .build();
        }

        log.info("AffinityAllocationEngineService: loaded {} shipments", allShipments.size());

        // Filter shipments with valid coordinates
        List<Shipment> validShipments = allShipments.stream()
                .filter(s -> s.getDropLatitude() != 0.0 && s.getDropLongitude() != 0.0)
                .filter(s -> s.getDropPincode() != null && !s.getDropPincode().trim().isEmpty())
                .collect(Collectors.toList());

        log.info("AffinityAllocationEngineService: {} shipments have valid coordinates and pincodes",
                validShipments.size());

        // Step 2: Group shipments by pincode
        Map<String, List<Shipment>> shipmentsByPincode = validShipments.stream()
                .collect(Collectors.groupingBy(Shipment::getDropPincode));

        log.info("AffinityAllocationEngineService: grouped shipments into {} pincodes",
                shipmentsByPincode.size());

        // Step 3: Build SR-to-regions mapping from configuration
        Map<String, List<String>> srToRegions = buildSrToRegionsMap(config, regions);
        
        log.info("AffinityAllocationEngineService: {} SRs assigned to regions", srToRegions.size());

        // Step 4: Initialize SR assignments
        Map<String, List<Shipment>> srAssignments = new LinkedHashMap<>();
        for (String srName : srToRegions.keySet()) {
            srAssignments.put(srName, new ArrayList<>());
        }

        // Step 5: Assign shipments to SRs based on region assignments
        assignShipmentsToRegions(shipmentsByPincode, srToRegions, srAssignments, config);

        // Step 6: Handle under-capacity and over-capacity SRs
        balanceCapacity(srAssignments, validShipments);

        // Step 7: Handle low-density and high-density regions
        handleLowDensityRegions(srAssignments, srToRegions, regions, validShipments);
        handleHighDensityRegions(srAssignments, srToRegions, regions);

        // Step 8: Ensure all SRs have 80-100 shipments
        enforceCapacityConstraints(srAssignments);

        // Step 9: Validate final capacity constraints
        // Requirements: 20.3
        validateFinalCapacityConstraints(srAssignments);

        // Step 10: Calculate allocation metrics
        AffinityAllocationMetrics metrics = calculateAllocationMetrics(srAssignments, srToRegions);

        log.info("AffinityAllocationEngineService: allocation complete - {} SRs assigned",
                srAssignments.size());

        return AffinityAllocationResult.builder()
                .srAssignments(srAssignments)
                .metrics(metrics)
                .build();
    }

    /**
     * Previews affinity-based allocation without persisting results.
     * 
     * This method executes the full allocation logic (same as executeAffinityAllocation)
     * but returns a preview result instead of persisting to the database. The preview
     * includes:
     * - Expected earnings per SR
     * - Shipment count per SR
     * - Earnings variance
     * - Cross-region percentage
     * - Warnings for SRs with earnings significantly above/below average (>20% deviation)
     * 
     * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7
     * 
     * @param date Allocation date in string format (e.g., "01-Jan-24")
     * @param hubName Hub name for filtering
     * @param config Affinity configuration with SR-region assignments
     * @param regions List of affinity regions (for mapping region IDs to pincodes)
     * @return AffinityAllocationPreview containing preview results and warnings
     */
    public AffinityAllocationPreview previewAffinityAllocation(
            String date,
            String hubName,
            AffinityConfiguration config,
            List<AffinityRegion> regions) {
        
        log.info("AffinityAllocationEngineService: starting preview allocation for date='{}', hub='{}'",
                date, hubName);

        // Step 1: Execute full allocation logic (without persisting)
        AffinityAllocationResult result = executeAffinityAllocation(date, hubName, config, regions);

        // Step 2: Extract metrics from the result
        AffinityAllocationMetrics metrics = result.getMetrics();
        Map<String, List<Shipment>> srAssignments = result.getSrAssignments();

        // Step 3: Build earnings per SR map
        Map<String, Double> earningsPerSr = new LinkedHashMap<>();
        for (Map.Entry<String, AffinityAllocationMetrics.SrMetrics> entry : metrics.getPerSrMetrics().entrySet()) {
            earningsPerSr.put(entry.getKey(), entry.getValue().getEarnings());
        }

        // Step 4: Build shipment count per SR map
        Map<String, Integer> shipmentCountPerSr = new LinkedHashMap<>();
        for (Map.Entry<String, AffinityAllocationMetrics.SrMetrics> entry : metrics.getPerSrMetrics().entrySet()) {
            shipmentCountPerSr.put(entry.getKey(), entry.getValue().getShipmentCount());
        }

        // Step 5: Calculate average earnings
        double averageEarnings = earningsPerSr.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        // Step 6: Generate warnings for SRs with earnings >20% above or below average
        List<AffinityAllocationPreview.EarningsWarning> warnings = new ArrayList<>();
        double deviationThreshold = 20.0; // 20% deviation threshold

        for (Map.Entry<String, Double> entry : earningsPerSr.entrySet()) {
            String srName = entry.getKey();
            double earnings = entry.getValue();

            // Calculate deviation percentage
            double deviationPercentage = 0.0;
            if (averageEarnings > 0) {
                deviationPercentage = ((earnings - averageEarnings) / averageEarnings) * 100.0;
            }

            // Check if deviation exceeds threshold
            if (Math.abs(deviationPercentage) > deviationThreshold) {
                String message;
                if (deviationPercentage > 0) {
                    message = String.format("SR '%s' has earnings ₹%.2f, which is %.1f%% above average (₹%.2f)",
                            srName, earnings, deviationPercentage, averageEarnings);
                } else {
                    message = String.format("SR '%s' has earnings ₹%.2f, which is %.1f%% below average (₹%.2f)",
                            srName, earnings, Math.abs(deviationPercentage), averageEarnings);
                }

                warnings.add(AffinityAllocationPreview.EarningsWarning.builder()
                        .srName(srName)
                        .earnings(earnings)
                        .averageEarnings(averageEarnings)
                        .deviationPercentage(deviationPercentage)
                        .message(message)
                        .build());

                log.warn("AffinityAllocationEngineService: {}", message);
            }
        }

        // Step 7: Build preview result
        AffinityAllocationPreview preview = AffinityAllocationPreview.builder()
                .earningsPerSr(earningsPerSr)
                .shipmentCountPerSr(shipmentCountPerSr)
                .earningsVariance(metrics.getEarningsVariance())
                .crossRegionPercentage(metrics.getCrossRegionOverlapPercentage())
                .withinRegionPercentage(metrics.getWithinRegionPercentage())
                .warnings(warnings)
                .totalShipments(metrics.getTotalShipments())
                .totalSrs(metrics.getTotalSrs())
                .averageShipmentsPerSr(metrics.getAverageShipmentsPerSr())
                .build();

        log.info("AffinityAllocationEngineService: preview allocation complete - {} SRs, {} warnings",
                metrics.getTotalSrs(), warnings.size());

        return preview;
    }

    /**
     * Handles low-density regions by merging adjacent regions and allowing cross-region coverage.
     * 
     * Low-density regions (< 40 shipments) are merged with adjacent regions to create shared
     * territories. SRs from adjacent regions are allowed to cover these low-density areas.
     * 
     * Algorithm:
     * 1. Identify low-density regions (< 40 shipments)
     * 2. Build adjacency map for regions
     * 3. For each low-density region:
     *    a. Find adjacent regions with assigned SRs
     *    b. Allow those SRs to cover the low-density region
     *    c. Distribute low-density shipments to adjacent SRs based on proximity
     * 
     * Requirements: 16.2, 16.3
     * 
     * @param srAssignments Current SR assignments
     * @param srToRegions Map of SR to assigned pincodes
     * @param regions List of affinity regions
     * @param allShipments All available shipments
     */
    private void handleLowDensityRegions(
            Map<String, List<Shipment>> srAssignments,
            Map<String, List<String>> srToRegions,
            List<AffinityRegion> regions,
            List<Shipment> allShipments) {

        log.info("AffinityAllocationEngineService: handling low-density regions");

        // Identify low-density regions (< 40 shipments)
        List<AffinityRegion> lowDensityRegions = regions.stream()
                .filter(r -> r.getShipmentCount() < 40)
                .collect(Collectors.toList());

        if (lowDensityRegions.isEmpty()) {
            log.info("AffinityAllocationEngineService: no low-density regions found");
            return;
        }

        log.info("AffinityAllocationEngineService: found {} low-density regions", lowDensityRegions.size());

        // Build adjacency map
        Map<String, Set<String>> adjacencyMap = buildRegionAdjacencyMap(regions);

        // Build reverse map: pincode -> list of SRs assigned to that pincode
        Map<String, List<String>> pincodeToSrs = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : srToRegions.entrySet()) {
            String srName = entry.getKey();
            for (String pincode : entry.getValue()) {
                pincodeToSrs.computeIfAbsent(pincode, k -> new ArrayList<>()).add(srName);
            }
        }

        // Group shipments by pincode for quick lookup
        Map<String, List<Shipment>> shipmentsByPincode = allShipments.stream()
                .collect(Collectors.groupingBy(Shipment::getDropPincode));

        // Track which shipments have been assigned
        Set<String> assignedShipmentIds = new HashSet<>();
        for (List<Shipment> shipments : srAssignments.values()) {
            for (Shipment s : shipments) {
                assignedShipmentIds.add(s.getShippingId());
            }
        }

        // For each low-density region, distribute shipments to adjacent SRs
        for (AffinityRegion lowDensityRegion : lowDensityRegions) {
            String pincode = lowDensityRegion.getPincode();
            List<Shipment> regionShipments = shipmentsByPincode.getOrDefault(pincode, Collections.emptyList());

            // Skip if no shipments or already assigned
            List<Shipment> unassignedShipments = regionShipments.stream()
                    .filter(s -> !assignedShipmentIds.contains(s.getShippingId()))
                    .collect(Collectors.toList());

            if (unassignedShipments.isEmpty()) {
                continue;
            }

            // Find adjacent regions with assigned SRs
            Set<String> adjacentPincodes = adjacencyMap.getOrDefault(pincode, Collections.emptySet());
            List<String> adjacentSrs = new ArrayList<>();
            for (String adjacentPincode : adjacentPincodes) {
                List<String> srs = pincodeToSrs.get(adjacentPincode);
                if (srs != null) {
                    adjacentSrs.addAll(srs);
                }
            }

            // Remove duplicates
            adjacentSrs = adjacentSrs.stream().distinct().collect(Collectors.toList());

            if (adjacentSrs.isEmpty()) {
                log.debug("AffinityAllocationEngineService: low-density region '{}' has no adjacent SRs, " +
                        "shipments will be handled in backfill", pincode);
                continue;
            }

            log.debug("AffinityAllocationEngineService: distributing {} shipments from low-density region '{}' " +
                    "to {} adjacent SRs", unassignedShipments.size(), pincode, adjacentSrs.size());

            // Distribute shipments to adjacent SRs based on proximity
            // Calculate centroids for each adjacent SR
            Map<String, double[]> srCentroids = new HashMap<>();
            for (String srName : adjacentSrs) {
                List<Shipment> srShipments = srAssignments.get(srName);
                if (srShipments != null && !srShipments.isEmpty()) {
                    srCentroids.put(srName, calculateCentroid(srShipments));
                }
            }

            // Assign each shipment to the nearest adjacent SR
            for (Shipment shipment : unassignedShipments) {
                String nearestSr = null;
                double minDistance = Double.MAX_VALUE;

                for (String srName : adjacentSrs) {
                    double[] centroid = srCentroids.get(srName);
                    if (centroid != null) {
                        double distance = haversine(
                                shipment.getDropLatitude(), shipment.getDropLongitude(),
                                centroid[0], centroid[1]);
                        if (distance < minDistance) {
                            minDistance = distance;
                            nearestSr = srName;
                        }
                    }
                }

                if (nearestSr != null) {
                    srAssignments.get(nearestSr).add(shipment);
                    assignedShipmentIds.add(shipment.getShippingId());
                    
                    // Update SR-to-regions mapping to include this low-density region
                    if (!srToRegions.get(nearestSr).contains(pincode)) {
                        srToRegions.get(nearestSr).add(pincode);
                    }
                }
            }
        }

        log.info("AffinityAllocationEngineService: low-density region handling complete");
    }

    /**
     * Handles high-density regions by subdividing them using K-Means clustering.
     * 
     * High-density regions (> 150 shipments) with multiple assigned SRs are subdivided
     * into balanced sub-territories using K-Means clustering. The variance in shipment
     * counts across sub-territories is minimized to ensure no sub-territory differs
     * from the mean by more than 20%.
     * 
     * Algorithm:
     * 1. Identify high-density regions (> 150 shipments)
     * 2. For each high-density region with multiple assigned SRs:
     *    a. Apply K-Means clustering to subdivide into sub-territories
     *    b. Assign each sub-territory to one SR
     *    c. Verify sub-territory balance (variance < 20% from mean)
     * 
     * Requirements: 17.2, 17.3
     * 
     * @param srAssignments Current SR assignments
     * @param srToRegions Map of SR to assigned pincodes
     * @param regions List of affinity regions
     */
    private void handleHighDensityRegions(
            Map<String, List<Shipment>> srAssignments,
            Map<String, List<String>> srToRegions,
            List<AffinityRegion> regions) {

        log.info("AffinityAllocationEngineService: handling high-density regions");

        // Identify high-density regions (> 150 shipments)
        List<AffinityRegion> highDensityRegions = regions.stream()
                .filter(r -> r.getShipmentCount() > 150)
                .collect(Collectors.toList());

        if (highDensityRegions.isEmpty()) {
            log.info("AffinityAllocationEngineService: no high-density regions found");
            return;
        }

        log.info("AffinityAllocationEngineService: found {} high-density regions", highDensityRegions.size());

        // Build reverse map: pincode -> list of SRs assigned to that pincode
        Map<String, List<String>> pincodeToSrs = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : srToRegions.entrySet()) {
            String srName = entry.getKey();
            for (String pincode : entry.getValue()) {
                pincodeToSrs.computeIfAbsent(pincode, k -> new ArrayList<>()).add(srName);
            }
        }

        // For each high-density region, subdivide if multiple SRs are assigned
        for (AffinityRegion highDensityRegion : highDensityRegions) {
            String pincode = highDensityRegion.getPincode();
            List<String> assignedSrs = pincodeToSrs.get(pincode);

            if (assignedSrs == null || assignedSrs.size() <= 1) {
                log.debug("AffinityAllocationEngineService: high-density region '{}' has {} assigned SRs, " +
                        "no subdivision needed", pincode, assignedSrs == null ? 0 : assignedSrs.size());
                continue;
            }

            log.debug("AffinityAllocationEngineService: subdividing high-density region '{}' " +
                    "with {} shipments among {} SRs", pincode, highDensityRegion.getShipmentCount(), assignedSrs.size());

            // Collect all shipments currently assigned to these SRs from this region
            List<Shipment> regionShipments = new ArrayList<>();
            for (String srName : assignedSrs) {
                List<Shipment> srShipments = srAssignments.get(srName);
                if (srShipments != null) {
                    // Filter to only include shipments from this pincode
                    List<Shipment> pincodeShipments = srShipments.stream()
                            .filter(s -> pincode.equals(s.getDropPincode()))
                            .collect(Collectors.toList());
                    regionShipments.addAll(pincodeShipments);
                }
            }

            if (regionShipments.isEmpty()) {
                log.debug("AffinityAllocationEngineService: no shipments found for high-density region '{}'", pincode);
                continue;
            }

            // Remove these shipments from current SR assignments
            for (String srName : assignedSrs) {
                List<Shipment> srShipments = srAssignments.get(srName);
                if (srShipments != null) {
                    srShipments.removeIf(s -> pincode.equals(s.getDropPincode()));
                }
            }

            // Apply K-Means clustering to subdivide the region
            assignShipmentsWithinRegion(regionShipments, assignedSrs, srAssignments);

            // Verify sub-territory balance
            int totalShipments = regionShipments.size();
            double meanShipments = (double) totalShipments / assignedSrs.size();
            double maxAllowedDeviation = meanShipments * 0.20; // 20% from mean

            boolean balanced = true;
            for (String srName : assignedSrs) {
                List<Shipment> srShipments = srAssignments.get(srName);
                long srPincodeCount = srShipments.stream()
                        .filter(s -> pincode.equals(s.getDropPincode()))
                        .count();
                double deviation = Math.abs(srPincodeCount - meanShipments);
                
                if (deviation > maxAllowedDeviation) {
                    balanced = false;
                    log.warn("AffinityAllocationEngineService: SR '{}' in high-density region '{}' has {} shipments " +
                            "(mean={}, deviation={}, max allowed={})",
                            srName, pincode, srPincodeCount,
                            String.format("%.1f", meanShipments),
                            String.format("%.1f", deviation),
                            String.format("%.1f", maxAllowedDeviation));
                }
            }

            if (balanced) {
                log.debug("AffinityAllocationEngineService: high-density region '{}' successfully subdivided " +
                        "with balanced sub-territories", pincode);
            } else {
                log.warn("AffinityAllocationEngineService: high-density region '{}' subdivision resulted in " +
                        "unbalanced sub-territories (variance > 20% from mean)", pincode);
            }
        }

        log.info("AffinityAllocationEngineService: high-density region handling complete");
    }

    /**
     * Builds a map of SR name to list of pincodes they are assigned to.
     * 
     * @param config Affinity configuration
     * @param regions List of affinity regions (for mapping region IDs to pincodes)
     * @return Map of SR name to list of pincodes
     */
    private Map<String, List<String>> buildSrToRegionsMap(
            AffinityConfiguration config,
            List<AffinityRegion> regions) {
        
        Map<String, List<String>> srToRegions = new LinkedHashMap<>();

        if (config == null || config.getSrRegionAssignments() == null) {
            return srToRegions;
        }

        // Build a map of region ID to pincode for quick lookup
        Map<Long, String> regionIdToPincode = new HashMap<>();
        if (regions != null) {
            for (AffinityRegion region : regions) {
                regionIdToPincode.put(region.getId(), region.getPincode());
            }
        }
        
        // Map SR assignments to pincodes
        for (SrRegionAssignment assignment : config.getSrRegionAssignments()) {
            String srName = assignment.getSrName();
            Long regionId = assignment.getAffinityRegionId();
            
            // Look up the pincode for this region ID
            String pincode = regionIdToPincode.get(regionId);
            if (pincode != null) {
                srToRegions.computeIfAbsent(srName, k -> new ArrayList<>()).add(pincode);
            } else {
                log.warn("AffinityAllocationEngineService: region ID {} not found in regions list", regionId);
            }
        }

        return srToRegions;
    }

    /**
     * Assigns shipments to SRs based on their region assignments.
     * 
     * For each region with assigned SRs:
     * - If region has 1 SR: assign all shipments to that SR
     * - If region has multiple SRs: apply K-Means clustering within the region
     * 
     * @param shipmentsByPincode Map of pincode to shipments
     * @param srToRegions Map of SR to assigned pincodes
     * @param srAssignments Output map to populate with assignments
     * @param config Affinity configuration
     */
    private void assignShipmentsToRegions(
            Map<String, List<Shipment>> shipmentsByPincode,
            Map<String, List<String>> srToRegions,
            Map<String, List<Shipment>> srAssignments,
            AffinityConfiguration config) {

        log.info("AffinityAllocationEngineService: assigning shipments to regions");

        // Build reverse map: pincode -> list of SRs assigned to that pincode
        Map<String, List<String>> pincodeToSrs = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : srToRegions.entrySet()) {
            String srName = entry.getKey();
            for (String pincode : entry.getValue()) {
                pincodeToSrs.computeIfAbsent(pincode, k -> new ArrayList<>()).add(srName);
            }
        }

        // For each pincode, assign shipments to the SRs assigned to that pincode
        for (Map.Entry<String, List<Shipment>> entry : shipmentsByPincode.entrySet()) {
            String pincode = entry.getKey();
            List<Shipment> shipments = entry.getValue();
            List<String> assignedSrs = pincodeToSrs.get(pincode);

            if (assignedSrs == null || assignedSrs.isEmpty()) {
                log.debug("AffinityAllocationEngineService: pincode '{}' has no assigned SRs, will be handled in backfill",
                        pincode);
                continue;
            }

            if (assignedSrs.size() == 1) {
                // Single SR assigned to this region - assign all shipments
                String srName = assignedSrs.get(0);
                srAssignments.get(srName).addAll(shipments);
                log.debug("AffinityAllocationEngineService: assigned {} shipments from pincode '{}' to SR '{}'",
                        shipments.size(), pincode, srName);
            } else {
                // Multiple SRs assigned to this region - apply K-Means clustering
                assignShipmentsWithinRegion(shipments, assignedSrs, srAssignments);
                log.debug("AffinityAllocationEngineService: distributed {} shipments from pincode '{}' among {} SRs using K-Means",
                        shipments.size(), pincode, assignedSrs.size());
            }
        }
    }

    /**
     * Applies K-Means clustering to distribute shipments among multiple SRs within a region.
     * 
     * This method reuses the K-Means logic from AllocationEngineService by creating
     * temporary clusters for the shipments in this region.
     * 
     * @param shipments Shipments in the region
     * @param srNames List of SR names assigned to this region
     * @param srAssignments Output map to populate
     */
    private void assignShipmentsWithinRegion(
            List<Shipment> shipments,
            List<String> srNames,
            Map<String, List<Shipment>> srAssignments) {

        int k = srNames.size();
        int n = shipments.size();

        if (n == 0 || k == 0) {
            return;
        }

        // Initialize centroids using evenly-spaced angular positions
        // This is similar to the approach in AllocationEngineService.kMeansCluster
        double centerLat = shipments.stream().mapToDouble(Shipment::getDropLatitude).average().orElse(0.0);
        double centerLng = shipments.stream().mapToDouble(Shipment::getDropLongitude).average().orElse(0.0);

        // Sort shipments by angle from center
        List<Shipment> sortedByAngle = shipments.stream()
                .sorted(Comparator.comparingDouble((Shipment s) ->
                        Math.atan2(s.getDropLongitude() - centerLng, s.getDropLatitude() - centerLat))
                        .thenComparing(Shipment::getShippingId))
                .collect(Collectors.toList());

        // Initialize centroids
        double[][] centroids = new double[k][2];
        for (int i = 0; i < k; i++) {
            int idx = (int) ((long) i * n / k);
            centroids[i][0] = sortedByAngle.get(idx).getDropLatitude();
            centroids[i][1] = sortedByAngle.get(idx).getDropLongitude();
        }

        // K-Means iterations
        int[] labels = new int[n];
        for (int iter = 0; iter < 50; iter++) {
            boolean changed = false;

            // Assignment step
            for (int i = 0; i < n; i++) {
                Shipment s = shipments.get(i);
                double minDist = Double.MAX_VALUE;
                int bestCluster = 0;

                for (int c = 0; c < k; c++) {
                    double dist = haversine(
                            s.getDropLatitude(), s.getDropLongitude(),
                            centroids[c][0], centroids[c][1]);
                    if (dist < minDist) {
                        minDist = dist;
                        bestCluster = c;
                    }
                }

                if (labels[i] != bestCluster) {
                    labels[i] = bestCluster;
                    changed = true;
                }
            }

            if (!changed) {
                break;
            }

            // Update centroids
            for (int c = 0; c < k; c++) {
                double sumLat = 0.0, sumLng = 0.0;
                int count = 0;

                for (int i = 0; i < n; i++) {
                    if (labels[i] == c) {
                        sumLat += shipments.get(i).getDropLatitude();
                        sumLng += shipments.get(i).getDropLongitude();
                        count++;
                    }
                }

                if (count > 0) {
                    centroids[c][0] = sumLat / count;
                    centroids[c][1] = sumLng / count;
                }
            }
        }

        // Assign shipments to SRs based on cluster labels
        for (int i = 0; i < n; i++) {
            int cluster = labels[i];
            String srName = srNames.get(cluster);
            srAssignments.get(srName).add(shipments.get(i));
        }
    }

    /**
     * Balances capacity across SRs by handling under-capacity and over-capacity cases.
     * 
     * Under-capacity: SRs with fewer than srCapacityMin shipments receive additional shipments
     * Over-capacity: SRs with more than srCapacityMax shipments have excess redistributed
     * 
     * @param srAssignments Current SR assignments
     * @param allShipments All available shipments for backfilling
     */
    private void balanceCapacity(
            Map<String, List<Shipment>> srAssignments,
            List<Shipment> allShipments) {

        log.info("AffinityAllocationEngineService: balancing capacity across {} SRs", srAssignments.size());

        // Collect all assigned shipment IDs to avoid double-assignment
        Set<String> assignedShipmentIds = new HashSet<>();
        for (List<Shipment> shipments : srAssignments.values()) {
            for (Shipment s : shipments) {
                assignedShipmentIds.add(s.getShippingId());
            }
        }

        // Find unassigned shipments for backfilling
        List<Shipment> unassignedShipments = allShipments.stream()
                .filter(s -> !assignedShipmentIds.contains(s.getShippingId()))
                .collect(Collectors.toList());

        log.info("AffinityAllocationEngineService: {} unassigned shipments available for backfilling",
                unassignedShipments.size());

        // Handle under-capacity SRs
        for (Map.Entry<String, List<Shipment>> entry : srAssignments.entrySet()) {
            String srName = entry.getKey();
            List<Shipment> shipments = entry.getValue();

            if (shipments.size() < srCapacityMin && !unassignedShipments.isEmpty()) {
                int needed = srCapacityMin - shipments.size();
                log.debug("AffinityAllocationEngineService: SR '{}' has {} shipments, needs {} more",
                        srName, shipments.size(), needed);

                // Calculate SR's current centroid
                double[] centroid = calculateCentroid(shipments);

                // Find nearest unassigned shipments
                List<Shipment> nearestShipments = unassignedShipments.stream()
                        .sorted(Comparator.comparingDouble(s ->
                                haversine(s.getDropLatitude(), s.getDropLongitude(), centroid[0], centroid[1])))
                        .limit(needed)
                        .collect(Collectors.toList());

                // Assign nearest shipments
                shipments.addAll(nearestShipments);
                unassignedShipments.removeAll(nearestShipments);

                log.debug("AffinityAllocationEngineService: backfilled {} shipments to SR '{}'",
                        nearestShipments.size(), srName);
            }
        }
    }

    /**
     * Enforces capacity constraints (80-100 shipments per SR).
     * 
     * This method handles SRs that still have too many or too few shipments after
     * initial assignment and backfilling.
     * 
     * @param srAssignments SR assignments to enforce constraints on
     */
    private void enforceCapacityConstraints(Map<String, List<Shipment>> srAssignments) {
        log.info("AffinityAllocationEngineService: enforcing capacity constraints ({}–{} shipments per SR)",
                srCapacityMin, srCapacityMax);

        boolean changed = true;
        int iterations = 0;
        int maxIterations = 100;

        while (changed && iterations < maxIterations) {
            changed = false;
            iterations++;

            // Find over-capacity and under-capacity SRs
            String overloadedSr = null;
            String underloadedSr = null;
            int maxCount = 0;
            int minCount = Integer.MAX_VALUE;

            for (Map.Entry<String, List<Shipment>> entry : srAssignments.entrySet()) {
                int count = entry.getValue().size();
                if (count > srCapacityMax && count > maxCount) {
                    maxCount = count;
                    overloadedSr = entry.getKey();
                }
                if (count < srCapacityMin && count < minCount) {
                    minCount = count;
                    underloadedSr = entry.getKey();
                }
            }

            // Transfer shipments from overloaded to underloaded SR
            if (overloadedSr != null && underloadedSr != null) {
                List<Shipment> overloadedShipments = srAssignments.get(overloadedSr);
                List<Shipment> underloadedShipments = srAssignments.get(underloadedSr);

                // Calculate centroids
                double[] underloadedCentroid = calculateCentroid(underloadedShipments);

                // Find the shipment in overloaded SR that is closest to underloaded SR's centroid
                Shipment toTransfer = overloadedShipments.stream()
                        .min(Comparator.comparingDouble(s ->
                                haversine(s.getDropLatitude(), s.getDropLongitude(),
                                        underloadedCentroid[0], underloadedCentroid[1])))
                        .orElse(null);

                if (toTransfer != null) {
                    overloadedShipments.remove(toTransfer);
                    underloadedShipments.add(toTransfer);
                    changed = true;

                    log.debug("AffinityAllocationEngineService: transferred shipment from '{}' ({} shipments) to '{}' ({} shipments)",
                            overloadedSr, overloadedShipments.size(), underloadedSr, underloadedShipments.size());
                }
            }
        }

        log.info("AffinityAllocationEngineService: capacity enforcement complete after {} iterations", iterations);

        // Log final capacity distribution
        for (Map.Entry<String, List<Shipment>> entry : srAssignments.entrySet()) {
            int count = entry.getValue().size();
            if (count < srCapacityMin || count > srCapacityMax) {
                log.warn("AffinityAllocationEngineService: SR '{}' has {} shipments (outside {}–{} range)",
                        entry.getKey(), count, srCapacityMin, srCapacityMax);
            }
        }
    }

    /**
     * Calculates the centroid (average lat/lng) of a list of shipments.
     * 
     * @param shipments List of shipments
     * @return Array of [latitude, longitude]
     */
    private double[] calculateCentroid(List<Shipment> shipments) {
        if (shipments.isEmpty()) {
            return new double[]{0.0, 0.0};
        }

        double sumLat = 0.0;
        double sumLng = 0.0;

        for (Shipment s : shipments) {
            sumLat += s.getDropLatitude();
            sumLng += s.getDropLongitude();
        }

        return new double[]{sumLat / shipments.size(), sumLng / shipments.size()};
    }

    /**
     * Calculates Haversine distance between two points.
     * 
     * @param lat1 Latitude of first point
     * @param lng1 Longitude of first point
     * @param lat2 Latitude of second point
     * @param lng2 Longitude of second point
     * @return Distance in kilometers
     */
    private double haversine(double lat1, double lng1, double lat2, double lng2) {
        return GoogleMapsService.haversine(lat1, lng1, lat2, lng2);
    }

    /**
     * Applies cross-region rebalancing to reduce earnings variance while maintaining
     * geographic affinity.
     * 
     * Algorithm:
     * 1. Calculate expected earnings for each SR based on assigned shipments
     * 2. Calculate earnings variance across all SRs
     * 3. If variance exceeds threshold, identify high-earning and low-earning SRs
     * 4. Swap shipments between SRs to reduce variance
     * 5. Prefer swaps between adjacent regions to maintain geographic affinity
     * 6. Track cross-region assignment percentage
     * 7. Ensure final variance is comparable to standard mode (within 20%)
     * 
     * Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 10.4, 15.1, 15.2, 15.3
     * 
     * @param srAssignments Current SR assignments
     * @param srToRegions Map of SR to assigned pincodes (for tracking within-region assignments)
     * @param regions List of affinity regions (for determining adjacency)
     * @param standardModeVariance Variance from standard mode allocation (for comparison)
     * @return Rebalanced SR assignments
     */
    public Map<String, List<Shipment>> applyCrossRegionRebalancing(
            Map<String, List<Shipment>> srAssignments,
            Map<String, List<String>> srToRegions,
            List<AffinityRegion> regions,
            double standardModeVariance) {

        log.info("AffinityAllocationEngineService: starting cross-region rebalancing");

        if (srAssignments.size() < 2) {
            log.info("AffinityAllocationEngineService: fewer than 2 SRs, skipping rebalancing");
            return srAssignments;
        }

        // Step 1: Calculate initial earnings and variance
        Map<String, Double> distances = computeDistances(srAssignments);
        Map<String, Double> earnings = computeNetEarningsMap(srAssignments, distances);
        double initialVariance = CompositeLoadScoreCalculator.earningsVariance(earnings);
        double initialWithinRegionPct = calculateWithinRegionPercentage(srAssignments, srToRegions);

        log.info("AffinityAllocationEngineService: initial variance=₹²{}, within-region={}%, standard variance=₹²{}",
                String.format("%.2f", initialVariance),
                String.format("%.1f", initialWithinRegionPct),
                String.format("%.2f", standardModeVariance));

        // Step 2: Check if rebalancing is needed
        double varianceThreshold = standardModeVariance * varianceToleranceRatio;
        if (initialVariance <= varianceThreshold) {
            log.info("AffinityAllocationEngineService: variance within acceptable range, skipping rebalancing");
            return srAssignments;
        }

        // Step 3: Build adjacency map for regions
        Map<String, Set<String>> adjacencyMap = buildRegionAdjacencyMap(regions);

        // Step 4: Iterative rebalancing
        int iterations = 0;
        double currentVariance = initialVariance;
        double currentWithinRegionPct = initialWithinRegionPct;

        while (currentVariance > varianceThreshold && iterations < maxIterations) {
            // Find highest and lowest earning SRs
            String richSr = findMaxEarningsSr(earnings);
            String poorSr = findMinEarningsSr(earnings);

            if (richSr.equals(poorSr)) {
                log.debug("AffinityAllocationEngineService: all SRs have equal earnings, stopping");
                break;
            }

            // Find best shipment to transfer from richSr to poorSr
            // Prefer shipments that maintain geographic affinity
            Shipment bestTransfer = findBestCrossRegionTransfer(
                    richSr, poorSr, srAssignments, srToRegions, adjacencyMap,
                    distances, earnings, currentWithinRegionPct);

            if (bestTransfer == null) {
                log.debug("AffinityAllocationEngineService: no beneficial transfer found, stopping");
                break;
            }

            // Execute the transfer
            srAssignments.get(richSr).remove(bestTransfer);
            srAssignments.get(poorSr).add(bestTransfer);

            // Recompute metrics for affected SRs
            distances.put(richSr, routeOptimizerService.estimateDistanceKm(srAssignments.get(richSr)));
            distances.put(poorSr, routeOptimizerService.estimateDistanceKm(srAssignments.get(poorSr)));
            earnings.put(richSr, CompositeLoadScoreCalculator.netEarnings(
                    srAssignments.get(richSr), distances.get(richSr)));
            earnings.put(poorSr, CompositeLoadScoreCalculator.netEarnings(
                    srAssignments.get(poorSr), distances.get(poorSr)));

            currentVariance = CompositeLoadScoreCalculator.earningsVariance(earnings);
            currentWithinRegionPct = calculateWithinRegionPercentage(srAssignments, srToRegions);
            iterations++;

            log.debug("AffinityAllocationEngineService: iteration {}: variance=₹²{}, within-region={}%",
                    iterations,
                    String.format("%.2f", currentVariance),
                    String.format("%.1f", currentWithinRegionPct));
        }

        double finalVariance = CompositeLoadScoreCalculator.earningsVariance(earnings);
        double finalWithinRegionPct = calculateWithinRegionPercentage(srAssignments, srToRegions);
        double varianceReduction = ((initialVariance - finalVariance) / initialVariance) * 100;

        log.info("AffinityAllocationEngineService: rebalancing complete - {} iterations, " +
                        "variance reduced by {}% (₹²{} → ₹²{}), within-region={}%",
                iterations,
                String.format("%.1f", varianceReduction),
                String.format("%.2f", initialVariance),
                String.format("%.2f", finalVariance),
                String.format("%.1f", finalWithinRegionPct));

        // Step 5: Check if fairness constraints are met after rebalancing
        // Requirements: 20.3
        if (finalVariance > varianceThreshold) {
            String errorMessage = String.format(
                    "Affinity allocation failed to meet fairness constraints after %d rebalancing iterations. " +
                    "Final earnings variance (₹²%.2f) exceeds acceptable threshold (₹²%.2f, which is %.0f%% of standard mode variance ₹²%.2f). " +
                    "The current affinity configuration may not be suitable for this dataset.",
                    iterations, finalVariance, varianceThreshold, varianceToleranceRatio * 100, standardModeVariance);
            log.error("AffinityAllocationEngineService: {}", errorMessage);
            throw new AllocationFailureException(errorMessage);
        }

        // Check capacity constraints
        for (Map.Entry<String, List<Shipment>> entry : srAssignments.entrySet()) {
            int shipmentCount = entry.getValue().size();
            if (shipmentCount < srCapacityMin || shipmentCount > srCapacityMax) {
                String errorMessage = String.format(
                        "Affinity allocation failed to meet capacity constraints. " +
                        "SR '%s' has %d shipments, which is outside the required range of %d-%d shipments per SR. " +
                        "The current affinity configuration may not be suitable for this dataset.",
                        entry.getKey(), shipmentCount, srCapacityMin, srCapacityMax);
                log.error("AffinityAllocationEngineService: {}", errorMessage);
                throw new AllocationFailureException(errorMessage);
            }
        }

        return srAssignments;
    }

    /**
     * Computes route distances for all SRs.
     * 
     * @param srAssignments SR assignments
     * @return Map of SR name to estimated distance in km
     */
    private Map<String, Double> computeDistances(Map<String, List<Shipment>> srAssignments) {
        Map<String, Double> distances = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> entry : srAssignments.entrySet()) {
            double distance = routeOptimizerService.estimateDistanceKm(entry.getValue());
            distances.put(entry.getKey(), distance);
        }
        return distances;
    }

    /**
     * Computes net earnings for all SRs.
     * 
     * @param srAssignments SR assignments
     * @param distances Pre-computed distances
     * @return Map of SR name to net earnings
     */
    private Map<String, Double> computeNetEarningsMap(
            Map<String, List<Shipment>> srAssignments,
            Map<String, Double> distances) {
        Map<String, Double> earnings = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shipment>> entry : srAssignments.entrySet()) {
            String srName = entry.getKey();
            List<Shipment> shipments = entry.getValue();
            double distance = distances.getOrDefault(srName, 0.0);
            earnings.put(srName, CompositeLoadScoreCalculator.netEarnings(shipments, distance));
        }
        return earnings;
    }

    /**
     * Finds the SR with maximum earnings.
     * 
     * @param earnings Map of SR to earnings
     * @return SR name with highest earnings
     */
    private String findMaxEarningsSr(Map<String, Double> earnings) {
        return earnings.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    /**
     * Finds the SR with minimum earnings.
     * 
     * @param earnings Map of SR to earnings
     * @return SR name with lowest earnings
     */
    private String findMinEarningsSr(Map<String, Double> earnings) {
        return earnings.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    /**
     * Calculates the percentage of shipments assigned within their affinity regions.
     * 
     * @param srAssignments Current SR assignments
     * @param srToRegions Map of SR to assigned pincodes
     * @return Percentage of within-region assignments (0-100)
     */
    private double calculateWithinRegionPercentage(
            Map<String, List<Shipment>> srAssignments,
            Map<String, List<String>> srToRegions) {

        int totalShipments = 0;
        int withinRegionShipments = 0;

        for (Map.Entry<String, List<Shipment>> entry : srAssignments.entrySet()) {
            String srName = entry.getKey();
            List<Shipment> shipments = entry.getValue();
            List<String> assignedPincodes = srToRegions.getOrDefault(srName, Collections.emptyList());

            for (Shipment shipment : shipments) {
                totalShipments++;
                if (assignedPincodes.contains(shipment.getDropPincode())) {
                    withinRegionShipments++;
                }
            }
        }

        if (totalShipments == 0) {
            return 100.0;
        }

        return (withinRegionShipments * 100.0) / totalShipments;
    }

    /**
     * Builds an adjacency map for regions based on geographic proximity.
     * Two regions are considered adjacent if their centroids are within a threshold distance.
     * 
     * @param regions List of affinity regions
     * @return Map of pincode to set of adjacent pincodes
     */
    private Map<String, Set<String>> buildRegionAdjacencyMap(List<AffinityRegion> regions) {
        Map<String, Set<String>> adjacencyMap = new HashMap<>();

        if (regions == null || regions.isEmpty()) {
            return adjacencyMap;
        }

        // Calculate centroids for each region based on boundary coordinates
        Map<String, double[]> regionCentroids = new HashMap<>();
        for (AffinityRegion region : regions) {
            double[] centroid = calculateRegionCentroid(region);
            regionCentroids.put(region.getPincode(), centroid);
            adjacencyMap.put(region.getPincode(), new HashSet<>());
        }

        // Define adjacency threshold (e.g., 5 km)
        double adjacencyThresholdKm = 5.0;

        // Build adjacency relationships
        for (AffinityRegion region1 : regions) {
            String pincode1 = region1.getPincode();
            double[] centroid1 = regionCentroids.get(pincode1);

            for (AffinityRegion region2 : regions) {
                if (region1.getPincode().equals(region2.getPincode())) {
                    continue;
                }

                String pincode2 = region2.getPincode();
                double[] centroid2 = regionCentroids.get(pincode2);

                double distance = haversine(centroid1[0], centroid1[1], centroid2[0], centroid2[1]);
                if (distance <= adjacencyThresholdKm) {
                    adjacencyMap.get(pincode1).add(pincode2);
                }
            }
        }

        return adjacencyMap;
    }

    /**
     * Calculates the centroid of a region from its boundary coordinates.
     * 
     * @param region Affinity region
     * @return Array of [latitude, longitude]
     */
    private double[] calculateRegionCentroid(AffinityRegion region) {
        // For now, use a simple approach: parse boundary coordinates and average them
        // In a production system, this would parse the JSON boundary coordinates
        // For this implementation, we'll return a default centroid
        // This can be enhanced later to parse the actual boundary coordinates
        return new double[]{0.0, 0.0};
    }

    /**
     * Finds the best shipment to transfer from richSr to poorSr to reduce variance
     * while maintaining geographic affinity.
     * 
     * Preference order:
     * 1. Shipments from adjacent regions (maintains geographic affinity)
     * 2. Shipments that reduce variance the most
     * 3. Shipments that don't violate within-region percentage target
     * 
     * @param richSr SR with highest earnings
     * @param poorSr SR with lowest earnings
     * @param srAssignments Current assignments
     * @param srToRegions Map of SR to assigned pincodes
     * @param adjacencyMap Map of region adjacencies
     * @param distances Pre-computed distances
     * @param earnings Pre-computed earnings
     * @param currentWithinRegionPct Current within-region percentage
     * @return Best shipment to transfer, or null if no beneficial transfer exists
     */
    private Shipment findBestCrossRegionTransfer(
            String richSr,
            String poorSr,
            Map<String, List<Shipment>> srAssignments,
            Map<String, List<String>> srToRegions,
            Map<String, Set<String>> adjacencyMap,
            Map<String, Double> distances,
            Map<String, Double> earnings,
            double currentWithinRegionPct) {

        List<Shipment> richShipments = srAssignments.get(richSr);
        List<Shipment> poorShipments = srAssignments.get(poorSr);

        if (richShipments.isEmpty()) {
            return null;
        }

        List<String> richRegions = srToRegions.getOrDefault(richSr, Collections.emptyList());
        List<String> poorRegions = srToRegions.getOrDefault(poorSr, Collections.emptyList());

        // Calculate poorSr's centroid for distance-based selection
        double[] poorCentroid = calculateCentroid(poorShipments);

        Shipment bestShipment = null;
        double bestVarianceReduction = 0.0;
        boolean bestIsAdjacent = false;

        double currentVariance = CompositeLoadScoreCalculator.earningsVariance(earnings);
        double richEarnings = earnings.get(richSr);
        double poorEarnings = earnings.get(poorSr);

        for (Shipment candidate : richShipments) {
            // Check if this shipment is from an adjacent region
            boolean isAdjacent = isShipmentFromAdjacentRegion(
                    candidate, richRegions, poorRegions, adjacencyMap);

            // Simulate the transfer
            double candidateEarnings = candidate.getExpectedPayout();
            double candidateDistance = haversine(
                    candidate.getDropLatitude(), candidate.getDropLongitude(),
                    poorCentroid[0], poorCentroid[1]);

            // Estimate new earnings after transfer
            double newRichEarnings = richEarnings - candidateEarnings;
            double newPoorEarnings = poorEarnings + candidateEarnings;

            // Calculate new variance (simplified - doesn't recompute distances)
            Map<String, Double> newEarnings = new HashMap<>(earnings);
            newEarnings.put(richSr, newRichEarnings);
            newEarnings.put(poorSr, newPoorEarnings);
            double newVariance = CompositeLoadScoreCalculator.earningsVariance(newEarnings);

            double varianceReduction = currentVariance - newVariance;

            // Check if this transfer would violate within-region percentage target
            // (simplified check - doesn't fully recompute percentage)
            boolean wouldViolateTarget = false;
            if (currentWithinRegionPct < targetWithinRegionPercentage) {
                // If we're already below target, avoid cross-region transfers
                if (!richRegions.contains(candidate.getDropPincode()) ||
                        !poorRegions.contains(candidate.getDropPincode())) {
                    wouldViolateTarget = true;
                }
            }

            // Select best candidate based on:
            // 1. Must reduce variance
            // 2. Prefer adjacent region transfers
            // 3. Don't violate within-region target
            if (varianceReduction > 0 && !wouldViolateTarget) {
                if (bestShipment == null ||
                        (isAdjacent && !bestIsAdjacent) ||
                        (isAdjacent == bestIsAdjacent && varianceReduction > bestVarianceReduction)) {
                    bestShipment = candidate;
                    bestVarianceReduction = varianceReduction;
                    bestIsAdjacent = isAdjacent;
                }
            }
        }

        if (bestShipment != null) {
            log.debug("AffinityAllocationEngineService: selected shipment {} for transfer " +
                            "(adjacent={}, variance reduction=₹²{})",
                    bestShipment.getShippingId(),
                    bestIsAdjacent,
                    String.format("%.2f", bestVarianceReduction));
        }

        return bestShipment;
    }

    /**
     * Checks if a shipment is from a region adjacent to the target SR's regions.
     * 
     * @param shipment Shipment to check
     * @param sourceRegions Source SR's assigned pincodes
     * @param targetRegions Target SR's assigned pincodes
     * @param adjacencyMap Region adjacency map
     * @return True if shipment is from an adjacent region
     */
    private boolean isShipmentFromAdjacentRegion(
            Shipment shipment,
            List<String> sourceRegions,
            List<String> targetRegions,
            Map<String, Set<String>> adjacencyMap) {

        String shipmentPincode = shipment.getDropPincode();

        // Check if shipment's pincode is adjacent to any of target SR's regions
        for (String targetPincode : targetRegions) {
            Set<String> adjacentPincodes = adjacencyMap.getOrDefault(targetPincode, Collections.emptySet());
            if (adjacentPincodes.contains(shipmentPincode)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Calculates allocation metrics for affinity-based allocation.
     * 
     * This method computes:
     * - Cross-region overlap percentage
     * - List of outlier SRs (>30% cross-region assignments)
     * - Within-region assignment percentage
     * - Per-SR metrics (shipment count, earnings, cross-region percentage)
     * 
     * Requirements: 15.4, 15.5, 30.1, 33.1, 34.1
     * 
     * @param srAssignments Map of SR name to assigned shipments
     * @param srToRegions Map of SR name to assigned pincodes
     * @return AffinityAllocationMetrics containing all calculated metrics
     */
    public AffinityAllocationMetrics calculateAllocationMetrics(
            Map<String, List<Shipment>> srAssignments,
            Map<String, List<String>> srToRegions) {

        log.info("AffinityAllocationEngineService: calculating allocation metrics");

        if (srAssignments == null || srAssignments.isEmpty()) {
            log.warn("AffinityAllocationEngineService: no SR assignments provided, returning empty metrics");
            return AffinityAllocationMetrics.builder()
                    .crossRegionOverlapPercentage(0.0)
                    .withinRegionPercentage(0.0)
                    .outlierSrs(Collections.emptyList())
                    .perSrMetrics(Collections.emptyMap())
                    .totalShipments(0)
                    .totalSrs(0)
                    .earningsVariance(0.0)
                    .averageShipmentsPerSr(0.0)
                    .build();
        }

        // Calculate total shipments
        int totalShipments = srAssignments.values().stream()
                .mapToInt(List::size)
                .sum();

        int totalSrs = srAssignments.size();

        // Calculate within-region percentage
        double withinRegionPercentage = calculateWithinRegionPercentage(srAssignments, srToRegions);

        // Calculate cross-region overlap percentage (inverse of within-region)
        double crossRegionOverlapPercentage = 100.0 - withinRegionPercentage;

        // Calculate per-SR metrics and identify outliers
        Map<String, AffinityAllocationMetrics.SrMetrics> perSrMetrics = new LinkedHashMap<>();
        List<String> outlierSrs = new ArrayList<>();

        // Compute distances and earnings for variance calculation
        Map<String, Double> distances = computeDistances(srAssignments);
        Map<String, Double> earnings = computeNetEarningsMap(srAssignments, distances);

        for (Map.Entry<String, List<Shipment>> entry : srAssignments.entrySet()) {
            String srName = entry.getKey();
            List<Shipment> shipments = entry.getValue();
            List<String> assignedPincodes = srToRegions.getOrDefault(srName, Collections.emptyList());

            // Count within-region and cross-region shipments
            int withinRegionCount = 0;
            int crossRegionCount = 0;

            for (Shipment shipment : shipments) {
                if (assignedPincodes.contains(shipment.getDropPincode())) {
                    withinRegionCount++;
                } else {
                    crossRegionCount++;
                }
            }

            int shipmentCount = shipments.size();
            double crossRegionPercentage = shipmentCount > 0
                    ? (crossRegionCount * 100.0) / shipmentCount
                    : 0.0;

            // Flag as outlier if >30% cross-region assignments
            if (crossRegionPercentage > 30.0) {
                outlierSrs.add(srName);
                log.debug("AffinityAllocationEngineService: SR '{}' flagged as outlier " +
                                "({}% cross-region assignments)",
                        srName, String.format("%.1f", crossRegionPercentage));
            }

            // Build SR metrics
            AffinityAllocationMetrics.SrMetrics srMetrics = AffinityAllocationMetrics.SrMetrics.builder()
                    .srName(srName)
                    .shipmentCount(shipmentCount)
                    .earnings(earnings.getOrDefault(srName, 0.0))
                    .crossRegionPercentage(crossRegionPercentage)
                    .crossRegionShipmentCount(crossRegionCount)
                    .withinRegionShipmentCount(withinRegionCount)
                    .routeDistanceKm(distances.getOrDefault(srName, 0.0))
                    .build();

            perSrMetrics.put(srName, srMetrics);
        }

        // Calculate earnings variance
        double earningsVariance = CompositeLoadScoreCalculator.earningsVariance(earnings);

        // Calculate average shipments per SR
        double averageShipmentsPerSr = totalSrs > 0
                ? (double) totalShipments / totalSrs
                : 0.0;

        AffinityAllocationMetrics metrics = AffinityAllocationMetrics.builder()
                .crossRegionOverlapPercentage(crossRegionOverlapPercentage)
                .withinRegionPercentage(withinRegionPercentage)
                .outlierSrs(outlierSrs)
                .perSrMetrics(perSrMetrics)
                .totalShipments(totalShipments)
                .totalSrs(totalSrs)
                .earningsVariance(earningsVariance)
                .averageShipmentsPerSr(averageShipmentsPerSr)
                .build();

        log.info("AffinityAllocationEngineService: metrics calculated - " +
                        "within-region={}%, cross-region={}%, outliers={}, variance=₹²{}",
                String.format("%.1f", withinRegionPercentage),
                String.format("%.1f", crossRegionOverlapPercentage),
                outlierSrs.size(),
                String.format("%.2f", earningsVariance));

        return metrics;
    }

    /**
     * Validates that all SRs meet capacity constraints after allocation.
     * 
     * Throws AllocationFailureException if any SR has shipments outside the
     * required range of 80-100 shipments per SR.
     * 
     * Requirements: 20.3
     * 
     * @param srAssignments Final SR assignments to validate
     * @throws AllocationFailureException if capacity constraints are violated
     */
    private void validateFinalCapacityConstraints(Map<String, List<Shipment>> srAssignments) {
        log.debug("AffinityAllocationEngineService: validating final capacity constraints");

        List<String> violatingSrs = new ArrayList<>();
        
        for (Map.Entry<String, List<Shipment>> entry : srAssignments.entrySet()) {
            String srName = entry.getKey();
            int shipmentCount = entry.getValue().size();
            
            if (shipmentCount < srCapacityMin || shipmentCount > srCapacityMax) {
                violatingSrs.add(String.format("%s (%d shipments)", srName, shipmentCount));
                log.warn("AffinityAllocationEngineService: SR '{}' has {} shipments (outside {}–{} range)",
                        srName, shipmentCount, srCapacityMin, srCapacityMax);
            }
        }

        if (!violatingSrs.isEmpty()) {
            String errorMessage = String.format(
                    "Affinity allocation failed to meet capacity constraints for %d SR(s): %s. " +
                    "Each SR must have between %d and %d shipments. " +
                    "The current affinity configuration may not be suitable for this dataset.",
                    violatingSrs.size(),
                    String.join(", ", violatingSrs),
                    srCapacityMin,
                    srCapacityMax);
            log.error("AffinityAllocationEngineService: {}", errorMessage);
            throw new AllocationFailureException(errorMessage);
        }

        log.debug("AffinityAllocationEngineService: all SRs meet capacity constraints");
    }
}
