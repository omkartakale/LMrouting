package com.example.LMrouting.model;

import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Audit record for supervisor overrides — pure POJO, no JPA.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OverrideAudit {
    private Long id;
    private LocalDate allocationDate;
    private String shippingId;
    private String fromSr;
    private String toSr;
    private LocalDateTime overriddenAt;
    @Builder.Default
    private boolean undone = false;
}
