package com.example.LMrouting.dto;

/**
 * Request body for POST /api/allocate.
 *
 * allocationMode: "count-based" (default, existing pipeline) or "time-based" (new affinity-first pipeline).
 * When null or absent, the existing count-based pipeline is used.
 */
public record AllocateRequest(String date, String allocationMode) {

    /**
     * Backward-compatible constructor for callers that only pass date.
     * Defaults allocationMode to null (count-based pipeline).
     */
    public AllocateRequest(String date) {
        this(date, null);
    }
}
