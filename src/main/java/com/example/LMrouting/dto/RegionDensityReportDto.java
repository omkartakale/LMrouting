package com.example.LMrouting.dto;

import com.example.LMrouting.model.AffinityRegion;

import java.util.List;

/**
 * DTO for region density analysis report.
 * Contains list of affinity regions with density metrics.
 */
public record RegionDensityReportDto(
        String hubName,
        String date,
        int totalShipments,
        int totalRegions,
        List<AffinityRegion> regions
) {}
