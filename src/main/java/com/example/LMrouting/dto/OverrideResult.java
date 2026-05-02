package com.example.LMrouting.dto;

/**
 * Response from POST /api/allocate/{date}/override.
 */
public record OverrideResult(
        String shippingId,
        String fromSr,
        String toSr,
        int newSequenceInTarget
) {}
