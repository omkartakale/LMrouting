package com.example.LMrouting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * AuditLog entity for tracking configuration changes, allocation execution,
 * warnings, and errors in the affinity allocation system.
 * 
 * This entity provides a comprehensive audit trail for:
 * - Configuration changes (save, load, delete, update assignments)
 * - Allocation execution (start, completion, metrics)
 * - Warnings (regions with zero SRs, variance threshold exceeded)
 * - Errors (allocation failures with severity levels)
 * 
 * Requirements: 24.1, 24.2, 24.3, 24.4, 24.5
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {
    
    /**
     * Unique identifier for the audit log entry.
     */
    private Long id;
    
    /**
     * Timestamp when the event occurred.
     */
    private LocalDateTime timestamp;
    
    /**
     * Type of event being logged.
     */
    private EventType eventType;
    
    /**
     * Severity level of the event.
     */
    private Severity severity;
    
    /**
     * User ID or supervisor ID who triggered the event.
     * May be null for system-generated events.
     */
    private String userId;
    
    /**
     * Human-readable description of the event.
     */
    private String description;
    
    /**
     * Additional metadata about the event as key-value pairs.
     * Examples:
     * - configName: "MyConfig"
     * - regionId: "123"
     * - srName: "SR001"
     * - earningsVariance: "0.15"
     * - crossRegionPercentage: "12.5"
     */
    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();
    
    /**
     * Optional reference to the allocation run ID this event is associated with.
     */
    private Long allocationRunId;
    
    /**
     * Event types for audit logging.
     */
    public enum EventType {
        // Configuration changes
        CONFIG_CREATED,
        CONFIG_SAVED,
        CONFIG_LOADED,
        CONFIG_DELETED,
        CONFIG_UPDATED,
        ASSIGNMENT_UPDATED,
        
        // Allocation execution
        ALLOCATION_STARTED,
        ALLOCATION_COMPLETED,
        ALLOCATION_FAILED,
        
        // Warnings
        WARNING_ZERO_SRS,
        WARNING_VARIANCE_EXCEEDED,
        WARNING_INSUFFICIENT_SRS,
        WARNING_CROSS_REGION_HIGH,
        
        // Errors
        ERROR_VALIDATION,
        ERROR_ALLOCATION,
        ERROR_CONFIGURATION
    }
    
    /**
     * Severity levels for audit log entries.
     */
    public enum Severity {
        INFO,
        WARN,
        ERROR
    }
    
    /**
     * Helper method to add metadata entry.
     */
    public void addMetadata(String key, String value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
    }
    
    /**
     * Helper method to get metadata value.
     */
    public String getMetadata(String key) {
        if (this.metadata == null) {
            return null;
        }
        return this.metadata.get(key);
    }
}
