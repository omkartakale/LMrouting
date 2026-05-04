package com.example.LMrouting.service;

import com.example.LMrouting.model.AuditLog;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AuditLogService manages audit logging for the affinity allocation system.
 * 
 * This service provides methods to log:
 * - Configuration changes (save, load, delete, update assignments)
 * - Allocation execution (start, completion, metrics)
 * - Warnings (regions with zero SRs, variance threshold exceeded)
 * - Errors (allocation failures with severity levels)
 * 
 * All audit logs are stored in InMemoryStore and can be retrieved by allocation run ID.
 * 
 * Requirements: 24.1, 24.2, 24.3, 24.4, 24.5
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuditLogService {
    
    private final InMemoryStore store;
    
    /**
     * Logs a configuration change event.
     * 
     * Requirements: 24.1
     * 
     * @param eventType Type of configuration event
     * @param userId User ID who made the change
     * @param description Description of the change
     * @param metadata Additional metadata about the change
     */
    public void logConfigurationChange(
            AuditLog.EventType eventType,
            String userId,
            String description,
            Map<String, String> metadata) {
        
        AuditLog auditLog = AuditLog.builder()
                .timestamp(LocalDateTime.now())
                .eventType(eventType)
                .severity(AuditLog.Severity.INFO)
                .userId(userId)
                .description(description)
                .metadata(metadata)
                .build();
        
        store.saveAuditLog(auditLog);
        
        log.info("AuditLogService: logged configuration change - eventType={}, userId={}, description={}",
                eventType, userId, description);
    }
    
    /**
     * Logs an allocation execution event.
     * 
     * Requirements: 24.2
     * 
     * @param eventType Type of allocation event
     * @param allocationRunId ID of the allocation run
     * @param description Description of the event
     * @param metadata Additional metadata about the event
     */
    public void logAllocationExecution(
            AuditLog.EventType eventType,
            Long allocationRunId,
            String description,
            Map<String, String> metadata) {
        
        AuditLog auditLog = AuditLog.builder()
                .timestamp(LocalDateTime.now())
                .eventType(eventType)
                .severity(AuditLog.Severity.INFO)
                .description(description)
                .metadata(metadata)
                .allocationRunId(allocationRunId)
                .build();
        
        store.saveAuditLog(auditLog);
        
        log.info("AuditLogService: logged allocation execution - eventType={}, allocationRunId={}, description={}",
                eventType, allocationRunId, description);
    }
    
    /**
     * Logs allocation metrics.
     * 
     * Requirements: 24.3
     * 
     * @param allocationRunId ID of the allocation run
     * @param earningsVariance Earnings variance metric
     * @param crossRegionPercentage Cross-region percentage metric
     * @param averageShipmentsPerSr Average shipments per SR metric
     */
    public void logAllocationMetrics(
            Long allocationRunId,
            double earningsVariance,
            double crossRegionPercentage,
            double averageShipmentsPerSr) {
        
        Map<String, String> metadata = Map.of(
                "earningsVariance", String.format("%.4f", earningsVariance),
                "crossRegionPercentage", String.format("%.2f", crossRegionPercentage),
                "averageShipmentsPerSr", String.format("%.2f", averageShipmentsPerSr)
        );
        
        String description = String.format(
                "Allocation metrics - Earnings Variance: %.4f, Cross-Region: %.2f%%, Avg Shipments/SR: %.2f",
                earningsVariance, crossRegionPercentage, averageShipmentsPerSr);
        
        AuditLog auditLog = AuditLog.builder()
                .timestamp(LocalDateTime.now())
                .eventType(AuditLog.EventType.ALLOCATION_COMPLETED)
                .severity(AuditLog.Severity.INFO)
                .description(description)
                .metadata(metadata)
                .allocationRunId(allocationRunId)
                .build();
        
        store.saveAuditLog(auditLog);
        
        log.info("AuditLogService: logged allocation metrics - allocationRunId={}, variance={:.4f}, crossRegion={:.2f}%",
                allocationRunId, earningsVariance, crossRegionPercentage);
    }
    
    /**
     * Logs a warning event.
     * 
     * Requirements: 24.4
     * 
     * @param eventType Type of warning event
     * @param description Description of the warning
     * @param metadata Additional metadata about the warning
     */
    public void logWarning(
            AuditLog.EventType eventType,
            String description,
            Map<String, String> metadata) {
        
        AuditLog auditLog = AuditLog.builder()
                .timestamp(LocalDateTime.now())
                .eventType(eventType)
                .severity(AuditLog.Severity.WARN)
                .description(description)
                .metadata(metadata)
                .build();
        
        store.saveAuditLog(auditLog);
        
        log.warn("AuditLogService: logged warning - eventType={}, description={}", eventType, description);
    }
    
    /**
     * Logs a warning event with allocation run ID.
     * 
     * Requirements: 24.4
     * 
     * @param eventType Type of warning event
     * @param allocationRunId ID of the allocation run
     * @param description Description of the warning
     * @param metadata Additional metadata about the warning
     */
    public void logWarning(
            AuditLog.EventType eventType,
            Long allocationRunId,
            String description,
            Map<String, String> metadata) {
        
        AuditLog auditLog = AuditLog.builder()
                .timestamp(LocalDateTime.now())
                .eventType(eventType)
                .severity(AuditLog.Severity.WARN)
                .description(description)
                .metadata(metadata)
                .allocationRunId(allocationRunId)
                .build();
        
        store.saveAuditLog(auditLog);
        
        log.warn("AuditLogService: logged warning - eventType={}, allocationRunId={}, description={}",
                eventType, allocationRunId, description);
    }
    
    /**
     * Logs an error event.
     * 
     * Requirements: 24.4
     * 
     * @param eventType Type of error event
     * @param description Description of the error
     * @param metadata Additional metadata about the error
     */
    public void logError(
            AuditLog.EventType eventType,
            String description,
            Map<String, String> metadata) {
        
        AuditLog auditLog = AuditLog.builder()
                .timestamp(LocalDateTime.now())
                .eventType(eventType)
                .severity(AuditLog.Severity.ERROR)
                .description(description)
                .metadata(metadata)
                .build();
        
        store.saveAuditLog(auditLog);
        
        log.error("AuditLogService: logged error - eventType={}, description={}", eventType, description);
    }
    
    /**
     * Logs an error event with allocation run ID.
     * 
     * Requirements: 24.4
     * 
     * @param eventType Type of error event
     * @param allocationRunId ID of the allocation run
     * @param description Description of the error
     * @param metadata Additional metadata about the error
     */
    public void logError(
            AuditLog.EventType eventType,
            Long allocationRunId,
            String description,
            Map<String, String> metadata) {
        
        AuditLog auditLog = AuditLog.builder()
                .timestamp(LocalDateTime.now())
                .eventType(eventType)
                .severity(AuditLog.Severity.ERROR)
                .description(description)
                .metadata(metadata)
                .allocationRunId(allocationRunId)
                .build();
        
        store.saveAuditLog(auditLog);
        
        log.error("AuditLogService: logged error - eventType={}, allocationRunId={}, description={}",
                eventType, allocationRunId, description);
    }
    
    /**
     * Retrieves all audit logs for a specific allocation run.
     * 
     * Requirements: 24.5
     * 
     * @param allocationRunId ID of the allocation run
     * @return List of audit logs in chronological order
     */
    public List<AuditLog> getAuditTrail(Long allocationRunId) {
        log.info("AuditLogService: retrieving audit trail for allocationRunId={}", allocationRunId);
        
        List<AuditLog> auditLogs = store.findAuditLogsByAllocationRunId(allocationRunId);
        
        log.info("AuditLogService: found {} audit log entries for allocationRunId={}",
                auditLogs.size(), allocationRunId);
        
        return auditLogs;
    }
    
    /**
     * Retrieves all audit logs (for debugging/admin purposes).
     * 
     * @return List of all audit logs in chronological order
     */
    public List<AuditLog> getAllAuditLogs() {
        log.info("AuditLogService: retrieving all audit logs");
        
        List<AuditLog> auditLogs = store.getAllAuditLogs();
        
        log.info("AuditLogService: found {} total audit log entries", auditLogs.size());
        
        return auditLogs;
    }
}
