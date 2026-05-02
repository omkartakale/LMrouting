package com.example.LMrouting.service;

import com.example.LMrouting.dto.OverrideResult;
import com.example.LMrouting.exception.AllocationAlreadyFinalizedException;
import com.example.LMrouting.exception.AllocationNotFoundException;
import com.example.LMrouting.exception.SrNotPresentException;
import com.example.LMrouting.model.AllocationRun;
import com.example.LMrouting.model.AllocationStatus;
import com.example.LMrouting.model.OverrideAudit;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Manages supervisor overrides — fully in-memory.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OverrideManagerService {

    private final InMemoryStore store;
    private final AttendanceManagerService attendanceManagerService;
    private final RouteOptimizerService routeOptimizerService;

    public OverrideResult reassign(String shippingId, String targetSrName, LocalDate date) {
        String dateStr = date.toString();

        Shipment shipment = store.findShipmentById(shippingId)
                .orElseThrow(() -> new AllocationNotFoundException("Shipment not found: " + shippingId));

        validateNotFinalized(date);

        List<String> presentSrs = attendanceManagerService.getPresentSrNames(date);
        if (!presentSrs.contains(targetSrName)) {
            throw new SrNotPresentException("SR " + targetSrName + " is not present on " + date);
        }

        String sourceSrName = shipment.getAssignedSr();
        shipment.setOriginalSr(sourceSrName);
        shipment.setAssignedSr(targetSrName);
        shipment.setOverride(true);
        store.updateShipment(shipment);

        // Re-sequence both SRs
        if (sourceSrName != null && !sourceSrName.equals(targetSrName)) {
            resequenceSr(sourceSrName, dateStr);
        }
        resequenceSr(targetSrName, dateStr);

        OverrideAudit audit = OverrideAudit.builder()
                .allocationDate(date)
                .shippingId(shippingId)
                .fromSr(sourceSrName)
                .toSr(targetSrName)
                .overriddenAt(LocalDateTime.now())
                .undone(false)
                .build();
        store.saveOverrideAudit(audit);

        int newSeq = store.findShipmentById(shippingId)
                .map(Shipment::getRouteSequence).orElse(0);

        log.info("OverrideManagerService: reassigned '{}' from '{}' to '{}'", shippingId, sourceSrName, targetSrName);
        return new OverrideResult(shippingId, sourceSrName, targetSrName, newSeq);
    }

    public void undoLastOverride(LocalDate date) {
        String dateStr = date.toString();
        List<OverrideAudit> audits = store.findActiveOverrideAuditsForDate(date);
        if (audits.isEmpty()) {
            log.warn("OverrideManagerService: no override to undo for {}", date);
            return;
        }

        OverrideAudit audit = audits.get(0);
        Shipment shipment = store.findShipmentById(audit.getShippingId())
                .orElseThrow(() -> new AllocationNotFoundException(
                        "Shipment not found during undo: " + audit.getShippingId()));

        shipment.setAssignedSr(audit.getFromSr());
        shipment.setOverride(false);
        shipment.setOriginalSr(null);
        store.updateShipment(shipment);

        if (audit.getToSr() != null) resequenceSr(audit.getToSr(), dateStr);
        if (audit.getFromSr() != null) resequenceSr(audit.getFromSr(), dateStr);

        audit.setUndone(true);
        log.info("OverrideManagerService: undid override of '{}' — restored to '{}'",
                audit.getShippingId(), audit.getFromSr());
    }

    public void finalizeAllocation(LocalDate date) {
        AllocationRun run = store.findAllocationRun(date)
                .orElseThrow(() -> new AllocationNotFoundException("No allocation run found for: " + date));
        run.setStatus(AllocationStatus.FINALIZED);
        run.setFinalizedAt(LocalDateTime.now());
        store.saveAllocationRun(run);
        log.info("OverrideManagerService: finalized allocation for {}", date);
    }

    private void validateNotFinalized(LocalDate date) {
        Optional<AllocationRun> runOpt = store.findAllocationRun(date);
        if (runOpt.isPresent() && runOpt.get().getStatus() == AllocationStatus.FINALIZED) {
            throw new AllocationAlreadyFinalizedException("Allocation for " + date + " is already finalized");
        }
    }

    private void resequenceSr(String srName, String dateStr) {
        List<Shipment> srShipments = store.findShipmentsByDateAndSr(dateStr, srName);
        if (srShipments.isEmpty()) return;
        List<Shipment> optimized = routeOptimizerService.optimizeRoute(srName, srShipments);
        optimized.forEach(store::updateShipment);
    }
}
