package com.example.LMrouting.dto;

import com.example.LMrouting.model.AllocationMode;

/**
 * Request body for POST /api/allocate.
 * 
 * The allocationMode field is optional and defaults to STANDARD for backward compatibility.
 * When set to AFFINITY, the controller routes to AffinityAllocationEngineService.
 */
public record AllocateRequest(
    String date,
    AllocationMode allocationMode
) {
    /**
     * Constructor with default allocation mode for backward compatibility.
     */
    public AllocateRequest(String date) {
        this(date, AllocationMode.STANDARD);
    }
}
