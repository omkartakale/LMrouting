package com.example.LMrouting.model;

/**
 * Density classification for affinity regions based on shipment count thresholds.
 * - LOW_DENSITY: fewer than 40 shipments
 * - MEDIUM_DENSITY: between 40 and 150 shipments (inclusive)
 * - HIGH_DENSITY: more than 150 shipments
 */
public enum DensityClassification {
    LOW_DENSITY,
    MEDIUM_DENSITY,
    HIGH_DENSITY
}
