package com.example.LMrouting.service;

import com.example.LMrouting.model.Shipment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Computes the estimated shift workload in minutes for an ordered list of shipments.
 *
 * <p>Workload formula:
 * <pre>
 *   totalMinutes = Σ handlingTime(shipment) + routeTravelTime + returnToHubTime
 * </pre>
 *
 * <p>Handling times (configurable via application.properties):
 * <ul>
 *   <li>COD shipments: {@code allocation.handling.time.cod} minutes (default 3.0)</li>
 *   <li>Prepaid shipments: {@code allocation.handling.time.prepaid} minutes (default 3.0)</li>
 *   <li>Any other type: {@code allocation.handling.time.default} minutes (default 3.0)</li>
 * </ul>
 *
 * <p>Travel times are sourced from {@link TravelTimeCacheService} (ORS → Google Maps → Haversine).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShiftWorkloadCalculatorService {

    private final TravelTimeCacheService travelTimeCache;

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    // Handling times — configurable to match actual delivery time constants.
    @Value("${allocation.handling.time.cod:6.0}")
    private double handlingTimeCod;

    @Value("${allocation.handling.time.prepaid:5.0}")
    private double handlingTimePrepaid;

    @Value("${allocation.handling.time.default:5.0}")
    private double handlingTimeDefault;

    // Break buffer — same value used in the timeline endpoint
    @Value("${allocation.shift.break.minutes:30.0}")
    private double breakBufferMinutes;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Compute the full workload breakdown for an ordered list of shipments.
     * Includes break buffer (30 min) to match the timeline calculation exactly.
     * Uses Haversine-only travel times (no external API calls) to avoid rate limits.
     *
     * @param orderedShipments shipments in route order (hub → stop[0] → … → stop[n-1])
     * @return WorkloadResult with total and component breakdowns; all values ≥ 0
     */
    public WorkloadResult computeWorkload(List<Shipment> orderedShipments) {
        if (orderedShipments == null || orderedShipments.isEmpty()) {
            return new WorkloadResult(0.0, 0.0, 0.0, 0.0);
        }

        // Handling time: sum per shipment
        double handlingMinutes = orderedShipments.stream()
                .mapToDouble(this::getHandlingTime)
                .sum();

        // Route travel time using Haversine ONLY — no ORS/Google Maps calls during packing
        List<double[]> waypoints = orderedShipments.stream()
                .map(s -> new double[]{s.getDropLatitude(), s.getDropLongitude()})
                .toList();
        double travelMinutes = travelTimeCache.getRouteTimeHaversine(waypoints);

        // Return to hub using Haversine ONLY
        Shipment last = orderedShipments.get(orderedShipments.size() - 1);
        double returnToHubMinutes = travelTimeCache.getReturnToHubTimeHaversine(
                last.getDropLatitude(), last.getDropLongitude());

        // Include break buffer (same as timeline) to ensure packer and timeline agree
        double totalMinutes = handlingMinutes + travelMinutes + returnToHubMinutes + breakBufferMinutes;
        return new WorkloadResult(totalMinutes, handlingMinutes, travelMinutes, returnToHubMinutes);
    }

    /**
     * Pure function — returns the handling time in minutes for a single shipment.
     * COD = 6 min, Prepaid = 5 min, default = 5 min (configurable via application.properties).
     *
     * @param s the shipment
     * @return handling time in minutes
     */
    public double getHandlingTime(Shipment s) {
        if (s == null || s.getOrderType() == null) return handlingTimeDefault;
        return switch (s.getOrderType().trim()) {
            case "COD"     -> handlingTimeCod;
            case "Prepaid" -> handlingTimePrepaid;
            default        -> handlingTimeDefault;
        };
    }

    // =========================================================================
    // Nested result record
    // =========================================================================

    /**
     * Immutable workload breakdown for a single SR's route.
     *
     * @param totalMinutes       total estimated shift workload (handling + travel + return)
     * @param handlingMinutes    sum of per-shipment handling times
     * @param travelMinutes      route travel time (hub → all stops)
     * @param returnToHubMinutes travel time from last stop back to hub
     */
    public record WorkloadResult(
            double totalMinutes,
            double handlingMinutes,
            double travelMinutes,
            double returnToHubMinutes
    ) {}
}
