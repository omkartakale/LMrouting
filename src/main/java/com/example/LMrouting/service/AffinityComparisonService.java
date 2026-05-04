package com.example.LMrouting.service;

import com.example.LMrouting.dto.AllocationSummary;
import com.example.LMrouting.dto.SrSummaryDto;
import com.example.LMrouting.model.*;
import com.example.LMrouting.store.InMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AffinityComparisonService compares standard mode and affinity mode allocations side-by-side.
 * 
 * This service:
 * 1. Executes standard mode allocation using AllocationEngineService
 * 2. Executes affinity mode allocation using AffinityAllocationEngineService
 * 3. Compares the results side-by-side
 * 4. Returns a comparison result containing:
 *    - Earnings distribution for both modes
 *    - Earnings variance for both modes
 *    - Average distance per SR for both modes
 *    - Differences in SR assignments
 *    - Percentage improvement/degradation in key metrics
 * 
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6
 */
@Service
@Slf4j
public class AffinityComparisonService {

    private final AllocationEngineService allocationEngineService;
    private final AffinityAllocationEngineService affinityAllocationEngineService;
    private final InMemoryStore store;
    private final RegionDensityAnalyzer regionDensityAnalyzer;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH);

    public AffinityComparisonService(
            AllocationEngineService allocationEngineService,
            AffinityAllocationEngineService affinityAllocationEngineService,
            InMemoryStore store,
            RegionDensityAnalyzer regionDensityAnalyzer) {
        this.allocationEngineService = allocationEngineService;
        this.affinityAllocationEngineService = affinityAllocationEngineService;
        this.store = store;
        this.regionDensityAnalyzer = regionDensityAnalyzer;
    }

    /**
     * Compares standard mode and affinity mode allocations for a given date and hub.
     * 
     * This method:
     * 1. Executes standard mode allocation
     * 2. Executes affinity mode allocation
     * 3. Calculates side-by-side metrics
     * 4. Highlights differences in SR assignments
     * 5. Calculates percentage improvement/degradation
     * 
     * @param date Allocation date in string format (e.g., "01-Jan-24")
     * @param hubName Hub name for filtering
     * @param config Affinity configuration with SR-region assignments
     * @return AffinityComparisonResult containing comparison metrics
     */
    public AffinityComparisonResult compareAllocations(
            String date,
            String hubName,
            AffinityConfiguration config) {
        
        log.info("AffinityComparisonService: starting comparison for date='{}', hub='{}'", date, hubName);

        // Step 1: Execute standard mode allocation
        LocalDate localDate = LocalDate.parse(date, DATE_FORMATTER);
        AllocationSummary standardSummary = allocationEngineService.allocate(localDate);
        
        log.info("AffinityComparisonService: standard mode allocation complete - {} SRs, {} shipments",
                standardSummary.totalSrs(), standardSummary.allocatedShipments());

        // Step 2: Create affinity regions from shipment data
        List<Shipment> shipments = store.findShipmentsByDate(date);
        List<AffinityRegion> regions = regionDensityAnalyzer.analyzeRegionDensity(shipments, hubName);
        
        log.info("AffinityComparisonService: created {} affinity regions", regions.size());

        // Step 3: Execute affinity mode allocation
        AffinityAllocationResult affinityResult = affinityAllocationEngineService.executeAffinityAllocation(
                date, hubName, config, regions);
        
        log.info("AffinityComparisonService: affinity mode allocation complete - {} SRs, {} shipments",
                affinityResult.getMetrics().getTotalSrs(),
                affinityResult.getMetrics().getTotalShipments());

        // Step 4: Build standard mode metrics
        AffinityComparisonResult.AllocationMetrics standardMetrics = buildStandardModeMetrics(standardSummary);

        // Step 5: Build affinity mode metrics
        AffinityComparisonResult.AllocationMetrics affinityMetrics = buildAffinityModeMetrics(affinityResult);

        // Step 6: Calculate metric comparison
        AffinityComparisonResult.MetricComparison comparison = calculateMetricComparison(
                standardMetrics, affinityMetrics);

        // Step 7: Calculate assignment differences
        Map<String, AffinityComparisonResult.SrAssignmentDifference> assignmentDifferences =
                calculateAssignmentDifferences(standardSummary, affinityResult);

        // Step 8: Build comparison result
        AffinityComparisonResult result = AffinityComparisonResult.builder()
                .standardMode(standardMetrics)
                .affinityMode(affinityMetrics)
                .comparison(comparison)
                .assignmentDifferences(assignmentDifferences)
                .build();

        log.info("AffinityComparisonService: comparison complete - earnings variance change: {:.2f}%, " +
                "average distance change: {:.2f}%",
                comparison.getEarningsVarianceChange(),
                comparison.getAverageDistanceChange());

        return result;
    }

    /**
     * Builds allocation metrics from standard mode allocation summary.
     */
    private AffinityComparisonResult.AllocationMetrics buildStandardModeMetrics(AllocationSummary summary) {
        Map<String, Double> earningsDistribution = new LinkedHashMap<>();
        double totalDistance = 0.0;

        for (SrSummaryDto srSummary : summary.srSummaries()) {
            earningsDistribution.put(srSummary.srName(), srSummary.netEarnings());
            totalDistance += srSummary.estimatedDistanceKm();
        }

        double averageDistancePerSr = summary.totalSrs() > 0 
                ? totalDistance / summary.totalSrs() 
                : 0.0;

        return AffinityComparisonResult.AllocationMetrics.builder()
                .earningsDistribution(earningsDistribution)
                .earningsVariance(summary.earningsVariance())
                .averageDistancePerSr(averageDistancePerSr)
                .totalShipments(summary.allocatedShipments())
                .totalSrs(summary.totalSrs())
                .averageShipmentsPerSr(summary.avgShipmentsPerSr())
                .minShipmentsPerSr(summary.minShipmentsPerSr())
                .maxShipmentsPerSr(summary.maxShipmentsPerSr())
                .meanNetEarnings(summary.meanNetEarnings())
                .earningsRange(summary.earningsRange())
                .build();
    }

    /**
     * Builds allocation metrics from affinity mode allocation result.
     */
    private AffinityComparisonResult.AllocationMetrics buildAffinityModeMetrics(
            AffinityAllocationResult result) {
        
        Map<String, Double> earningsDistribution = new LinkedHashMap<>();
        double totalDistance = 0.0;
        int minShipments = Integer.MAX_VALUE;
        int maxShipments = Integer.MIN_VALUE;

        AffinityAllocationMetrics metrics = result.getMetrics();

        for (Map.Entry<String, AffinityAllocationMetrics.SrMetrics> entry : metrics.getPerSrMetrics().entrySet()) {
            AffinityAllocationMetrics.SrMetrics srMetrics = entry.getValue();
            earningsDistribution.put(entry.getKey(), srMetrics.getEarnings());
            totalDistance += srMetrics.getRouteDistanceKm();
            
            minShipments = Math.min(minShipments, srMetrics.getShipmentCount());
            maxShipments = Math.max(maxShipments, srMetrics.getShipmentCount());
        }

        double averageDistancePerSr = metrics.getTotalSrs() > 0 
                ? totalDistance / metrics.getTotalSrs() 
                : 0.0;

        // Calculate mean net earnings
        double meanNetEarnings = earningsDistribution.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        // Calculate earnings range
        double minEarnings = earningsDistribution.values().stream()
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(0.0);
        double maxEarnings = earningsDistribution.values().stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);
        double earningsRange = maxEarnings - minEarnings;

        return AffinityComparisonResult.AllocationMetrics.builder()
                .earningsDistribution(earningsDistribution)
                .earningsVariance(metrics.getEarningsVariance())
                .averageDistancePerSr(averageDistancePerSr)
                .totalShipments(metrics.getTotalShipments())
                .totalSrs(metrics.getTotalSrs())
                .averageShipmentsPerSr(metrics.getAverageShipmentsPerSr())
                .minShipmentsPerSr(minShipments == Integer.MAX_VALUE ? 0 : minShipments)
                .maxShipmentsPerSr(maxShipments == Integer.MIN_VALUE ? 0 : maxShipments)
                .meanNetEarnings(meanNetEarnings)
                .earningsRange(earningsRange)
                .build();
    }

    /**
     * Calculates percentage improvement/degradation in key metrics.
     */
    private AffinityComparisonResult.MetricComparison calculateMetricComparison(
            AffinityComparisonResult.AllocationMetrics standardMetrics,
            AffinityComparisonResult.AllocationMetrics affinityMetrics) {
        
        // Calculate percentage changes
        double earningsVarianceChange = calculatePercentageChange(
                standardMetrics.getEarningsVariance(),
                affinityMetrics.getEarningsVariance());

        double averageDistanceChange = calculatePercentageChange(
                standardMetrics.getAverageDistancePerSr(),
                affinityMetrics.getAverageDistancePerSr());

        double earningsRangeChange = calculatePercentageChange(
                standardMetrics.getEarningsRange(),
                affinityMetrics.getEarningsRange());

        // Count SRs with improved/degraded/unchanged earnings
        int improved = 0;
        int degraded = 0;
        int unchanged = 0;

        for (String srName : standardMetrics.getEarningsDistribution().keySet()) {
            Double standardEarnings = standardMetrics.getEarningsDistribution().get(srName);
            Double affinityEarnings = affinityMetrics.getEarningsDistribution().get(srName);

            if (standardEarnings != null && affinityEarnings != null) {
                double diff = affinityEarnings - standardEarnings;
                if (Math.abs(diff) < 0.01) { // Consider < ₹0.01 as unchanged
                    unchanged++;
                } else if (diff > 0) {
                    improved++;
                } else {
                    degraded++;
                }
            }
        }

        return AffinityComparisonResult.MetricComparison.builder()
                .earningsVarianceChange(earningsVarianceChange)
                .averageDistanceChange(averageDistanceChange)
                .earningsRangeChange(earningsRangeChange)
                .srsWithImprovedEarnings(improved)
                .srsWithDegradedEarnings(degraded)
                .srsWithUnchangedEarnings(unchanged)
                .build();
    }

    /**
     * Calculates percentage change from baseline to new value.
     * Negative values indicate improvement (reduction).
     */
    private double calculatePercentageChange(double baseline, double newValue) {
        if (baseline == 0.0) {
            return newValue == 0.0 ? 0.0 : 100.0;
        }
        return ((newValue - baseline) / baseline) * 100.0;
    }

    /**
     * Calculates differences in SR assignments between standard and affinity modes.
     */
    private Map<String, AffinityComparisonResult.SrAssignmentDifference> calculateAssignmentDifferences(
            AllocationSummary standardSummary,
            AffinityAllocationResult affinityResult) {
        
        Map<String, AffinityComparisonResult.SrAssignmentDifference> differences = new LinkedHashMap<>();

        // Build a map of SR summaries from standard mode
        Map<String, SrSummaryDto> standardSrMap = standardSummary.srSummaries().stream()
                .collect(Collectors.toMap(SrSummaryDto::srName, s -> s));

        // Build a map of SR metrics from affinity mode
        Map<String, AffinityAllocationMetrics.SrMetrics> affinitySrMap = 
                affinityResult.getMetrics().getPerSrMetrics();

        // Get all unique SR names from both modes
        Set<String> allSrNames = new HashSet<>();
        allSrNames.addAll(standardSrMap.keySet());
        allSrNames.addAll(affinitySrMap.keySet());

        // Calculate differences for each SR
        for (String srName : allSrNames) {
            SrSummaryDto standardSr = standardSrMap.get(srName);
            AffinityAllocationMetrics.SrMetrics affinitySr = affinitySrMap.get(srName);

            // Get pincodes from affinity mode
            List<String> affinityPincodes = new ArrayList<>();
            if (affinityResult.getSrAssignments().containsKey(srName)) {
                affinityPincodes = affinityResult.getSrAssignments().get(srName).stream()
                        .map(Shipment::getDropPincode)
                        .distinct()
                        .sorted()
                        .collect(Collectors.toList());
            }

            List<String> standardPincodes = standardSr != null 
                    ? new ArrayList<>(standardSr.pincodesCovered())
                    : Collections.emptyList();

            // Calculate pincode differences
            Set<String> standardPincodeSet = new HashSet<>(standardPincodes);
            Set<String> affinityPincodeSet = new HashSet<>(affinityPincodes);

            List<String> pincodesOnlyInStandard = standardPincodeSet.stream()
                    .filter(p -> !affinityPincodeSet.contains(p))
                    .sorted()
                    .collect(Collectors.toList());

            List<String> pincodesOnlyInAffinity = affinityPincodeSet.stream()
                    .filter(p -> !standardPincodeSet.contains(p))
                    .sorted()
                    .collect(Collectors.toList());

            List<String> pincodesInBoth = standardPincodeSet.stream()
                    .filter(affinityPincodeSet::contains)
                    .sorted()
                    .collect(Collectors.toList());

            AffinityComparisonResult.SrAssignmentDifference difference = 
                    AffinityComparisonResult.SrAssignmentDifference.builder()
                    .srName(srName)
                    .standardModeShipmentCount(standardSr != null ? standardSr.shipmentCount() : 0)
                    .affinityModeShipmentCount(affinitySr != null ? affinitySr.getShipmentCount() : 0)
                    .standardModeEarnings(standardSr != null ? standardSr.netEarnings() : 0.0)
                    .affinityModeEarnings(affinitySr != null ? affinitySr.getEarnings() : 0.0)
                    .standardModeDistance(standardSr != null ? standardSr.estimatedDistanceKm() : 0.0)
                    .affinityModeDistance(affinitySr != null ? affinitySr.getRouteDistanceKm() : 0.0)
                    .standardModePincodes(standardPincodes)
                    .affinityModePincodes(affinityPincodes)
                    .pincodesOnlyInStandard(pincodesOnlyInStandard)
                    .pincodesOnlyInAffinity(pincodesOnlyInAffinity)
                    .pincodesInBoth(pincodesInBoth)
                    .build();

            differences.put(srName, difference);
        }

        return differences;
    }
}
