package com.example.LMrouting.dto;

import com.example.LMrouting.model.AffinityConfiguration;
import com.example.LMrouting.model.AffinityRegion;

import java.util.List;

/**
 * Request DTO for updating SR assignments for a region.
 */
public record UpdateAssignmentRequest(
        AffinityConfiguration config,
        Long regionId,
        List<String> srNames,
        List<String> presentSRs,
        List<AffinityRegion> allRegions
) {}
