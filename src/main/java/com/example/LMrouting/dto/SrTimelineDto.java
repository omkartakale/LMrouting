package com.example.LMrouting.dto;

import java.time.LocalTime;
import java.util.List;

/**
 * SR Timeline View — stop-by-stop ETA progression aligned with packing-time calculations.
 *
 * <p>This DTO provides a detailed timeline for a single SR's delivery route,
 * including cumulative ETA at each stop, handling times, and distance metrics.
 * All travel-time calculations use the same Haversine logic (with configurable road factor)
 * as {@code ShiftWorkloadCalculatorService.computeWorkload()}, ensuring consistency
 * between allocation decisions and the timeline displayed to supervisors.
 *
 * <p><strong>Consistency guarantee:</strong>
 * {@code totalDurationMinutes} MUST equal
 * {@code ShiftWorkloadCalculatorService.computeWorkload().totalMinutes()}
 * for the same ordered shipment list.
 *
 * <p>Existing {@code RouteResponse} / {@code ShipmentStopDto} remain for backward compatibility.
 *
 * @see RouteResponse
 * @see ShipmentStopDto
 */
public record SrTimelineDto(
        // ── SR identity ───────────────────────────────────────────────────────
        String srName,

        // ── Route summary ─────────────────────────────────────────────────────
        int totalStops,
        double totalDurationMinutes,
        LocalTime expectedCompletionTime,
        double totalDistanceKm,
        double totalEarnings,
        int shipmentCount,

        // ── Time breakdown (for frontend stats row) ───────────────────────────
        String startTime,
        String endTime,
        double travelMinutes,
        double deliveryMinutes,
        int stopCount,
        int breakAfterStop,

        // ── Return to hub info ────────────────────────────────────────────────
        ReturnToHub returnToHub,

        // ── Stop-by-stop timeline ─────────────────────────────────────────────
        List<TimelineStop> stops
) {

    /**
     * Return-to-hub leg information displayed at the end of the timeline.
     */
    public record ReturnToHub(
            double distKm,
            double travelMin,
            String arrivalTime
    ) {}

    /**
     * A single stop in the SR's delivery timeline.
     *
     * <p>{@code etaMinutes} is cumulative from the start of the shift (not from the previous stop).
     * {@code departureMinutes} = {@code etaMinutes} + {@code handlingTimeMinutes}.
     * {@code distanceFromPreviousKm} is the Haversine distance from the previous stop
     * (or from the hub for the first stop).
     */
    public record TimelineStop(
            int stopNumber,
            String shipmentId,
            String address,
            double latitude,
            double longitude,
            double etaMinutes,
            double handlingTimeMinutes,
            double departureMinutes,
            double distanceFromPreviousKm,

            // ── Frontend-friendly fields ──────────────────────────────────────
            /** Travel time from previous stop in minutes */
            double travelFromPrevMin,
            /** Distance from previous stop in km */
            double distFromPrevKm,
            /** Whether this is a heavy shipment */
            boolean isHeavy,
            /** Latitude (alias for frontend) */
            double lat,
            /** Longitude (alias for frontend) */
            double lng,
            /** Shipping ID (alias for frontend) */
            String shippingId,
            /** Arrival time as formatted string (e.g. "09:15") */
            String arrivalTime,
            /** Departure time as formatted string (e.g. "09:21") */
            String departureTime,
            /** Stop sequence number (alias for frontend) */
            int sequence,
            /** Drop pincode */
            String pincode,
            /** Handling/delivery time in minutes (alias for frontend) */
            double deliveryMin
    ) {}
}
