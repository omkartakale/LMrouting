package com.example.LMrouting.store;

import com.example.LMrouting.model.AllocationRun;
import com.example.LMrouting.model.OverrideAudit;
import com.example.LMrouting.model.Shipment;
import com.example.LMrouting.model.SrAttendanceRecord;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Pure in-memory store replacing all JPA/DB functionality.
 * All data lives in memory and is reset on app restart.
 * Thread-safe via ConcurrentHashMap.
 */
@Component
public class InMemoryStore {

    // ── Shipments ─────────────────────────────────────────────────────────────
    // Key: allocationDate (String) → list of shipments for that date
    private final Map<String, List<Shipment>> shipmentsByDate = new ConcurrentHashMap<>();
    private final AtomicLong shipmentIdSeq = new AtomicLong(1);

    // ── Attendance ────────────────────────────────────────────────────────────
    // Key: "date::srName" → isPresent
    private final Map<String, Boolean> attendance = new ConcurrentHashMap<>();

    // ── SR Registry ───────────────────────────────────────────────────────────
    // Ordered list of SR names for this hub
    private final List<String> srNames = Collections.synchronizedList(new ArrayList<>());

    // ── Allocation Runs ───────────────────────────────────────────────────────
    // Key: allocationDate (LocalDate) → AllocationRun
    private final Map<LocalDate, AllocationRun> allocationRuns = new ConcurrentHashMap<>();
    private final AtomicLong runIdSeq = new AtomicLong(1);

    // ── Override Audits ───────────────────────────────────────────────────────
    private final List<OverrideAudit> overrideAudits = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong auditIdSeq = new AtomicLong(1);

    // =========================================================================
    // Shipment operations
    // =========================================================================

    public void saveShipments(String dateStr, List<Shipment> shipments) {
        // Assign IDs
        shipments.forEach(s -> {
            if (s.getId() == null) s.setId(shipmentIdSeq.getAndIncrement());
        });
        shipmentsByDate.put(dateStr, new ArrayList<>(shipments));
    }

    public List<Shipment> findShipmentsByDate(String dateStr) {
        return new ArrayList<>(shipmentsByDate.getOrDefault(dateStr, Collections.emptyList()));
    }

    public List<String> findAllDates() {
        return new ArrayList<>(shipmentsByDate.keySet());
    }

    public Optional<Shipment> findShipmentById(String shippingId) {
        return shipmentsByDate.values().stream()
                .flatMap(List::stream)
                .filter(s -> shippingId.equals(s.getShippingId()))
                .findFirst();
    }

    public void updateShipment(Shipment updated) {
        shipmentsByDate.values().forEach(list -> {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getShippingId().equals(updated.getShippingId())) {
                    list.set(i, updated);
                    return;
                }
            }
        });
    }

    public List<Shipment> findShipmentsByDateAndSr(String dateStr, String srName) {
        return findShipmentsByDate(dateStr).stream()
                .filter(s -> srName.equals(s.getAssignedSr()))
                .sorted(Comparator.comparingInt(Shipment::getRouteSequence))
                .collect(Collectors.toList());
    }

    public boolean hasShipmentsForDate(String dateStr) {
        List<Shipment> list = shipmentsByDate.get(dateStr);
        return list != null && !list.isEmpty();
    }

    public void clearDate(String dateStr) {
        shipmentsByDate.remove(dateStr);
    }

    // =========================================================================
    // Attendance operations
    // =========================================================================

    private String attendanceKey(LocalDate date, String srName) {
        return date.toString() + "::" + srName;
    }

    public void setAttendance(LocalDate date, String srName, boolean present) {
        attendance.put(attendanceKey(date, srName), present);
    }

    public boolean getAttendance(LocalDate date, String srName) {
        return attendance.getOrDefault(attendanceKey(date, srName), false);
    }

    public List<SrAttendanceRecord> getAttendanceForDate(LocalDate date) {
        return srNames.stream()
                .map(name -> new SrAttendanceRecord(name, getAttendance(date, name)))
                .collect(Collectors.toList());
    }

    public List<String> getPresentSrNames(LocalDate date) {
        return srNames.stream()
                .filter(name -> getAttendance(date, name))
                .collect(Collectors.toList());
    }

    // =========================================================================
    // SR Registry operations
    // =========================================================================

    public void setSrNames(List<String> names) {
        srNames.clear();
        srNames.addAll(names);
    }

    public List<String> getSrNames() {
        return new ArrayList<>(srNames);
    }

    public boolean hasSrNames() {
        return !srNames.isEmpty();
    }

    // =========================================================================
    // Allocation Run operations
    // =========================================================================

    public void saveAllocationRun(AllocationRun run) {
        if (run.getId() == null) run.setId(runIdSeq.getAndIncrement());
        allocationRuns.put(run.getAllocationDate(), run);
    }

    public Optional<AllocationRun> findAllocationRun(LocalDate date) {
        return Optional.ofNullable(allocationRuns.get(date));
    }

    // =========================================================================
    // Override Audit operations
    // =========================================================================

    public void saveOverrideAudit(OverrideAudit audit) {
        if (audit.getId() == null) audit.setId(auditIdSeq.getAndIncrement());
        overrideAudits.add(audit);
    }

    public List<OverrideAudit> findOverrideAuditsForDate(LocalDate date) {
        return overrideAudits.stream()
                .filter(a -> date.equals(a.getAllocationDate()))
                .sorted(Comparator.comparing(OverrideAudit::getOverriddenAt).reversed())
                .collect(Collectors.toList());
    }

    public List<OverrideAudit> findActiveOverrideAuditsForDate(LocalDate date) {
        return overrideAudits.stream()
                .filter(a -> date.equals(a.getAllocationDate()) && !a.isUndone())
                .sorted(Comparator.comparing(OverrideAudit::getOverriddenAt).reversed())
                .collect(Collectors.toList());
    }
}
