package com.example.LMrouting.dto;

import java.util.List;

/**
 * Result returned after CSV ingestion.
 */
public record IngestionResult(
        String primaryDate,
        int totalRows,
        int validCount,
        int skippedCount,
        int outOfRangeCount,
        int zeroCoordsCount,
        List<String> datesFound,
        List<String> warnings,
        List<String> errors
) {}
