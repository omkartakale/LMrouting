package com.example.LMrouting.service;

import com.example.LMrouting.dto.SrAttendanceDto;
import com.example.LMrouting.model.SrAttendanceRecord;
import com.example.LMrouting.store.InMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages SR registry and daily attendance — fully in-memory.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceManagerService {

    private final InMemoryStore store;

    @Value("${hub.name:PNQ HDP}")
    private String hubName;

    @Value("${hub.sr.names:SR-001,SR-002,SR-003,SR-004,SR-005,SR-006,SR-007,SR-008,SR-009,SR-010}")
    private String srNamesRaw;

    /**
     * Seed SR registry from config if not already populated.
     * Called on startup and after CSV upload (which may contain SR names).
     */
    public void ensureSrRegistrySeeded() {
        if (store.hasSrNames()) return;

        List<String> names = Arrays.stream(srNamesRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());

        store.setSrNames(names);
        log.info("AttendanceManagerService: seeded {} SRs from config", names.size());
    }

    /**
     * Seed SR registry from a custom list (e.g. extracted from uploaded CSV).
     */
    public void seedSrNames(List<String> names) {
        store.setSrNames(names);
        log.info("AttendanceManagerService: seeded {} SRs from provided list", names.size());
    }

    public List<SrAttendanceDto> getAttendanceForDate(LocalDate date) {
        ensureSrRegistrySeeded();
        return store.getAttendanceForDate(date).stream()
                .map(r -> new SrAttendanceDto(r.srName(), r.present()))
                .collect(Collectors.toList());
    }

    public void setAttendance(LocalDate date, String srName, boolean present) {
        store.setAttendance(date, srName, present);
        log.debug("AttendanceManagerService: {} on {} → {}", srName, date, present);
    }

    public List<String> getPresentSrNames(LocalDate date) {
        ensureSrRegistrySeeded();
        return store.getPresentSrNames(date);
    }

    public List<String> getAllSrNames() {
        ensureSrRegistrySeeded();
        return store.getSrNames();
    }

    public void addSr(String srName) {
        ensureSrRegistrySeeded();
        List<String> current = store.getSrNames();
        if (!current.contains(srName)) {
            current.add(srName);
            store.setSrNames(current);
            log.info("AttendanceManagerService: added new SR '{}'", srName);
        }
    }
}
