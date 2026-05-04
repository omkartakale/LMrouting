package com.example.LMrouting.dto;

import com.example.LMrouting.model.Shipment;

import java.util.List;

/**
 * Request DTO for analyzing regions and generating density report.
 */
public record AnalyzeRegionsRequest(
        String date,
        String hubName,
        List<Shipment> shipments
) {}
