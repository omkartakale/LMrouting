package com.example.LMrouting.model;

import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Tracks the state of an allocation run — pure POJO, no JPA.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AllocationRun {
    private Long id;
    private LocalDate allocationDate;
    private AllocationStatus status;
    private int totalShipments;
    private int totalSrs;
    private double fairnessVariance;
    /** Earnings range (max − min net earnings) at the time this run was persisted. */
    private double earningsRange;
    private LocalDateTime createdAt;
    private LocalDateTime finalizedAt;
    
    // Affinity mode fields
    /** Allocation mode: STANDARD or AFFINITY */
    private AllocationMode allocationMode;
    /** Reference to the affinity configuration used (null for STANDARD mode) */
    private Long affinityConfigurationId;
    /** Percentage of shipments assigned outside their affinity regions (null for STANDARD mode) */
    private Double crossRegionPercentage;
}
