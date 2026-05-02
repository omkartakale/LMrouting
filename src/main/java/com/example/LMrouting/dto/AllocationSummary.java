package com.example.LMrouting.dto;

import java.util.List;

/**
 * Response from POST /api/allocate and GET /api/allocate/{date}/summary.
 */
public record AllocationSummary(
        String date,
        int totalShipments,       // total in the uploaded file for this date
        int allocatedShipments,   // actually assigned to SRs (may be < total if capacity exceeded)
        int unallocatedShipments, // shipments that exceeded SR capacity and were not assigned
        int srCapacity,           // max shipments per SR (from config)
        int totalSrs,
        int minShipmentsPerSr,
        int maxShipmentsPerSr,
        double avgShipmentsPerSr,
        double fairnessVariance,
        List<SrSummaryDto> srSummaries
) {}
