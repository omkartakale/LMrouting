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
    private LocalDateTime createdAt;
    private LocalDateTime finalizedAt;
}
