package com.example.LMrouting.dto;

import com.example.LMrouting.model.AffinityRegion;

import java.util.List;

/**
 * Request DTO for auto-distributing SRs to regions.
 */
public record AutoDistributeRequest(
        List<AffinityRegion> regions,
        List<String> presentSRs
) {}
