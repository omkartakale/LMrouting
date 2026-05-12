package com.example.LMrouting.service;

import com.example.LMrouting.dto.SrTimelineDto;
import com.example.LMrouting.dto.SrTimelineDto.TimelineStop;
import com.example.LMrouting.model.Shipment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a stop-by-stop SR timeline aligned with packing-time calculations.
 *
 * <p>This service uses the <strong>same</strong> Haversine-based travel-time logic
 * as {@link ShiftWorkloadCalculatorService} — specifically
 * {@link TravelTimeCacheService#getTravelTimeHaversine} for stop-to-stop travel
 * and {@link TravelTimeCacheService#getReturnToHubTimeHaversine} for the return leg.
 *
 * <p><strong>Consistency guarantee:</strong>
 * {@code buildTimeline(srName, orderedShipments, startTime).totalDurationMinutes()}
 * MUST equal
 * {@code ShiftWorkloadCalculatorService.computeWorkload(orderedShipments).totalMinutes()}
 * for the same ordered shipment list. This is guaranteed by construction — both
 * services use the same underlying {@code haversineMinutes()} method with the same
 * configurable road factor.
 *
 * <p>Handling times: COD = 6 min, Prepaid = 5 min, default = 5 min (same as packing).
 * Break buffer: 30 min (same as packing).
 *
 * @see ShiftWorkloadCalculatorService
 * @see TravelTimeCacheService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SrTimelineService {

    private final TravelTimeCacheService travelTimeCache;

    @Value("${hub.latitude:18.4600561}")
    private double hubLat;

    @Value("${hub.longitude:73.8884305}")
    private double hubLng;

    // Handling times — same as ShiftWorkloadCalculatorService
    @Value("${allocation.handling.time.cod:5.0}")
    private double handlingTimeCod;

    @Value("${allocation.handling.time.prepaid:5.0}")
    private double handlingTimePrepaid;

    @Value("${allocation.handling.time.default:5.0}")
    private double handlingTimeDefault;

    // Break buffer — same as ShiftWorkloadCalculatorService
    @Value("${allocation.shift.break.minutes:30.0}")
    private double breakBufferMinutes;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Build a stop-by-stop timeline for an SR's ordered shipment list.
     *
     * @param srName           the SR's name/identifier
     * @param orderedShipments shipments in route order (hub → stop[0] → … → stop[n-1])
     * @param startTime        shift start time (default 09:00)
     * @return SrTimelineDto with cumulative ETA progression and route summary
     */
    public SrTimelineDto buildTimeline(String srName, List<Shipment> orderedShipments, LocalTime startTime) {
        if (orderedShipments == null || orderedShipments.isEmpty()) {
            return new SrTimelineDto(
                    srName,
                    0,
                    0.0,
                    startTime,
                    0.0,
                    0.0,
                    0,
                    formatTime(startTime),
                    formatTime(startTime),
                    0.0,
                    0.0,
                    0,
                    0,
                    new SrTimelineDto.ReturnToHub(0.0, 0.0, formatTime(startTime)),
                    List.of()
            );
        }

        List<TimelineStop> stops = new ArrayList<>();
        double cumulativeEta = 0.0;
        double totalTravelMinutes = 0.0;
        double totalHandlingMinutes = 0.0;
        double totalDistanceKm = 0.0;
        double totalEarnings = 0.0;

        double prevLat = hubLat;
        double prevLng = hubLng;

        for (int i = 0; i < orderedShipments.size(); i++) {
            Shipment shipment = orderedShipments.get(i);

            // Travel time from previous stop (or hub) to this stop — Haversine only
            double travelTime = travelTimeCache.getTravelTimeHaversine(
                    prevLat, prevLng,
                    shipment.getDropLatitude(), shipment.getDropLongitude()
            );

            // Distance from previous stop (raw Haversine, no road factor)
            double distanceFromPrevious = GoogleMapsService.haversine(
                    prevLat, prevLng,
                    shipment.getDropLatitude(), shipment.getDropLongitude()
            );

            // ETA at this stop = previous departure + travel time
            double etaMinutes = cumulativeEta + travelTime;

            // Handling time for this shipment
            double handlingTime = getHandlingTime(shipment);

            // Departure from this stop = ETA + handling
            double departureMinutes = etaMinutes + handlingTime;

            // Build the address string from available fields
            String address = buildAddress(shipment);

            // Compute formatted arrival/departure times
            String arrivalTimeStr = formatTime(startTime.plusMinutes(Math.round(etaMinutes)));
            String departureTimeStr = formatTime(startTime.plusMinutes(Math.round(departureMinutes)));

            stops.add(new TimelineStop(
                    i + 1,
                    shipment.getShippingId(),
                    address,
                    shipment.getDropLatitude(),
                    shipment.getDropLongitude(),
                    etaMinutes,
                    handlingTime,
                    departureMinutes,
                    distanceFromPrevious,
                    // Frontend-friendly fields
                    travelTime,
                    distanceFromPrevious,
                    shipment.getIsHeavy() == 1,
                    shipment.getDropLatitude(),
                    shipment.getDropLongitude(),
                    shipment.getShippingId(),
                    arrivalTimeStr,
                    departureTimeStr,
                    i + 1,
                    shipment.getDropPincode(),
                    handlingTime
            ));

            // Accumulate totals
            totalTravelMinutes += travelTime;
            totalHandlingMinutes += handlingTime;
            totalDistanceKm += distanceFromPrevious;
            totalEarnings += shipment.getExpectedPayout();
            cumulativeEta = departureMinutes;

            prevLat = shipment.getDropLatitude();
            prevLng = shipment.getDropLongitude();
        }

        // Return to hub — same method as ShiftWorkloadCalculatorService
        Shipment lastShipment = orderedShipments.get(orderedShipments.size() - 1);
        double returnToHubMinutes = travelTimeCache.getReturnToHubTimeHaversine(
                lastShipment.getDropLatitude(), lastShipment.getDropLongitude()
        );

        // Return-to-hub distance (raw Haversine)
        double returnDistanceKm = GoogleMapsService.haversine(
                lastShipment.getDropLatitude(), lastShipment.getDropLongitude(),
                hubLat, hubLng
        );
        totalDistanceKm += returnDistanceKm;

        // Total duration = handling + travel + return + break (identical to ShiftWorkloadCalculatorService)
        double totalDurationMinutes = totalHandlingMinutes + totalTravelMinutes
                + returnToHubMinutes + breakBufferMinutes;

        // Expected completion time
        long totalMinutesRounded = Math.round(totalDurationMinutes);
        LocalTime expectedCompletionTime = startTime.plusMinutes(totalMinutesRounded);

        // Return-to-hub arrival time = last stop departure + return travel time
        double returnArrivalMinutes = cumulativeEta + returnToHubMinutes;
        String returnArrivalTimeStr = formatTime(startTime.plusMinutes(Math.round(returnArrivalMinutes)));

        // Break after midpoint stop
        int breakAfterStop = orderedShipments.size() / 2;

        log.debug("SrTimeline [{}]: stops={}, duration={}min, distance={}km, earnings=₹{}",
                srName, orderedShipments.size(),
                String.format("%.1f", totalDurationMinutes),
                String.format("%.1f", totalDistanceKm),
                String.format("%.0f", totalEarnings));

        return new SrTimelineDto(
                srName,
                orderedShipments.size(),
                totalDurationMinutes,
                expectedCompletionTime,
                totalDistanceKm,
                totalEarnings,
                orderedShipments.size(),
                // Frontend-friendly summary fields
                formatTime(startTime),
                formatTime(expectedCompletionTime),
                totalTravelMinutes,
                totalHandlingMinutes,
                orderedShipments.size(),
                breakAfterStop,
                // Return to hub
                new SrTimelineDto.ReturnToHub(returnDistanceKm, returnToHubMinutes, returnArrivalTimeStr),
                stops
        );
    }

    /**
     * Convenience overload with default start time of 09:00.
     */
    public SrTimelineDto buildTimeline(String srName, List<Shipment> orderedShipments) {
        return buildTimeline(srName, orderedShipments, LocalTime.of(9, 0));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Returns the handling time in minutes for a single shipment.
     * Same logic as {@link ShiftWorkloadCalculatorService#getHandlingTime(Shipment)}.
     * COD = 6 min, Prepaid = 5 min, default = 5 min.
     */
    private double getHandlingTime(Shipment s) {
        if (s == null || s.getOrderType() == null) return handlingTimeDefault;
        return switch (s.getOrderType().trim()) {
            case "COD"     -> handlingTimeCod;
            case "Prepaid" -> handlingTimePrepaid;
            default        -> handlingTimeDefault;
        };
    }

    /**
     * Format a LocalTime as HH:mm string for the frontend.
     */
    private String formatTime(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    /**
     * Build a human-readable address string from available shipment fields.
     * Uses dropPincode + cityName as the Shipment model does not have a dedicated address field.
     */
    private String buildAddress(Shipment shipment) {
        StringBuilder sb = new StringBuilder();
        if (shipment.getDropPincode() != null && !shipment.getDropPincode().isBlank()) {
            sb.append(shipment.getDropPincode());
        }
        if (shipment.getCityName() != null && !shipment.getCityName().isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(shipment.getCityName());
        }
        if (shipment.getStateName() != null && !shipment.getStateName().isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(shipment.getStateName());
        }
        return sb.length() > 0 ? sb.toString() : "Unknown";
    }
}
